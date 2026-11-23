"""固定マップの整数ラスター・索引・manifestを生成する。実行時のゲームには依存しない。"""

import argparse
import hashlib
import heapq
import json
import math
import shutil
import sys
import tempfile
from collections import deque
from write_underground import document as underground_document
from pathlib import Path

import numpy as np
import PIL
from PIL import Image, ImageDraw, ImageFilter

TOOL = Path(__file__).resolve().parent
ROOT = TOOL.parents[1]
MAP_RESOURCES = Path("modules/world/src/main/resources/data/ashen_frontier/fixed_map")
OUTPUT = ROOT / MAP_RESOURCES / "earth_001"
SIZE = 2048
LAYERS = ("height", "region", "water", "soil", "geology", "coast_river")
RESOURCE_FILES = tuple(f"{name}.png" for name in LAYERS) + ("index.json", "geology_profiles.json", "underground.json")
TOOL_VERSION = "1.16.0"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def pixel(point):
    return tuple((v + 16384) // 16 for v in point)


def raster_line(points, width):
    image = Image.new("L", (SIZE, SIZE))
    ImageDraw.Draw(image).line([pixel(p) for p in points], fill=255, width=width)
    return np.array(image) != 0


def neighbors(mask):
    padded = np.pad(mask, 1)
    return (padded[:-2, 1:-1] | padded[2:, 1:-1] |
            padded[1:-1, :-2] | padded[1:-1, 2:])


def curved_points(points, seed, amplitude=0, spacing=16):
    """制約点を通る滑らかな曲線。蛇行は端点と接線を保つ固定シードの変位とする。"""
    rng = np.random.Generator(np.random.PCG64(seed))
    values = np.array(points, dtype=float)
    result = []
    for i in range(len(values)-1):
        a, b = values[max(0,i-1)], values[i]
        c, d = values[i+1], values[min(len(values)-1,i+2)]
        length = np.linalg.norm(c-b)
        count = max(2,math.ceil(length/spacing))
        phase = rng.uniform(0,2*math.pi)
        normal = np.array([-(c-b)[1],(c-b)[0]]) / max(length,1)
        for t in np.linspace(0,1,count,endpoint=False):
            p = .5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t)
            p += normal*amplitude*math.sin(2*math.pi*t+phase)*math.sin(math.pi*t)**2
            point = np.rint(p).astype(int).tolist()
            if not result or point != result[-1]:
                result.append(point)
    result.append(list(points[-1]))
    return result


def terrain_route(waypoints, height, nw):
    """64ブロック格子のA*で海と大きな高度差を避け、保護幅内で角を丸める。"""
    safe = nw.copy()
    for _ in range(3):
        safe &= ~neighbors(~safe)
    step = 4
    allowed = safe[::step,::step]
    elevations = height[::step,::step]
    h,w = allowed.shape
    result = []
    for start,end in zip(waypoints,waypoints[1:]):
        sx,sy = pixel(start)
        ex,ey = pixel(end)
        source,target = (sy//step,sx//step),(ey//step,ex//step)
        if not allowed[source] or not allowed[target]:
            raise ValueError(f"経路端点の陸地幅が不足しています: {start}, {end}")
        queue = [(0.,source)]
        costs = {source:0.}
        parent = {}
        while queue:
            _,current = heapq.heappop(queue)
            if current == target:
                break
            y,x = current
            for dy,dx in [(-1,0),(0,-1),(0,1),(1,0),(-1,-1),(-1,1),(1,-1),(1,1)]:
                a,b = y+dy,x+dx
                if not (0 <= a < h and 0 <= b < w and allowed[a,b]):
                    continue
                if dy and dx and not (allowed[y,b] and allowed[a,x]):
                    continue
                cost = costs[current]+math.hypot(dy,dx)+abs(int(elevations[a,b])-int(elevations[y,x]))*.18
                node = (a,b)
                if cost < costs.get(node,math.inf):
                    costs[node] = cost
                    parent[node] = current
                    heapq.heappush(queue,(cost+math.dist(node,target),node))
        if target not in costs:
            raise ValueError(f"陸上経路を結べません: {start}, {end}")
        path = [target]
        while path[-1] != source:
            path.append(parent[path[-1]])
        path.reverse()
        raw = [list(start)]+[[x*64-16384,y*64-16384] for y,x in path[1:-1]]+[list(end)]
        # 隣り合う安全セルの内部に収まる二次曲線で角を丸める。
        rounded = [raw[0]]
        for a,b,c in zip(raw,raw[1:],raw[2:]):
            p = (np.array(a)+b)/2
            q = (np.array(c)+b)/2
            for t in [0.,.25,.5,.75,1.]:
                rounded.append(np.rint((1-t)**2*p+2*t*(1-t)*np.array(b)+t*t*q).astype(int).tolist())
        rounded.append(raw[-1])
        result.extend(rounded if not result else rounded[1:])
    if np.any(raster_line(result,5) & ~nw):
        raise ValueError("丸めた経路が海へ出ています")
    return result


def drainage(height, land):
    """海岸からpriority floodで窪地を埋め、同高面でも海へ向かう木と集水面積を得る。"""
    h, w = height.shape
    seen = ~land.copy()
    coast = land & neighbors(~land)
    queue = []
    parent = np.full((h, w), -1, np.int32)
    for y, x in zip(*np.nonzero(coast)):
        seen[y, x] = True
        heapq.heappush(queue, (int(height[y, x]), int(y*w+x)))
        for a, b in ((y-1,x), (y+1,x), (y,x-1), (y,x+1)):
            if 0 <= a < h and 0 <= b < w and not land[a, b]:
                parent[y, x] = a*w+b
                break
    order = []
    while queue:
        level, pos = heapq.heappop(queue)
        order.append(pos)
        y, x = divmod(pos, w)
        for a, b in ((y-1,x), (y+1,x), (y,x-1), (y,x+1)):
            if 0 <= a < h and 0 <= b < w and not seen[a, b]:
                seen[a, b] = True
                parent[a, b] = pos
                value = max(level, int(height[a, b]))
                height[a, b] = value
                heapq.heappush(queue, (value, a*w+b))
    if not seen.all():
        raise ValueError("海へ排水できない陸地があります")
    area = land.astype(np.int32).ravel()
    flat_parent = parent.ravel()
    for pos in reversed(order):
        target = flat_parent[pos]
        if target >= 0:
            area[target] += area[pos]
    return parent, area.reshape((h, w))


def noise(seed):
    rng = np.random.Generator(np.random.PCG64(seed))
    result = np.zeros((SIZE, SIZE), np.float32)
    for n, amplitude in [(8, 22), (32, 12), (128, 4)]:
        grid = rng.integers(0, 256, (n, n), dtype=np.uint8)
        result += (np.array(Image.fromarray(grid).resize((SIZE, SIZE), Image.Resampling.BILINEAR), dtype=np.float32)/255-.5)*amplitude
    return result


def load_inputs(input_dir):
    config = json.loads((input_dir / "layout.json").read_text(encoding="utf-8"))
    labels = np.array(Image.open(input_dir / "land.png"))
    if labels.shape != (SIZE, SIZE) or labels.dtype != np.uint8 or not np.isin(labels, range(5)).all():
        raise ValueError("陸海入力は2048²・8bit・値0〜4が必要です")
    return config, labels


def mountain_coordinates(mountain, bx, bz):
    """固定折れ線の最近点から、距離と稜線に沿う位置を求める。"""
    squared = np.full(bx.shape, np.inf, np.float32)
    station = np.zeros(bx.shape, np.float32)
    cross = np.zeros(bx.shape, np.float32)
    offset = 0
    for a,b in zip(mountain["points"],mountain["points"][1:]):
        vx,vz = b[0]-a[0],b[1]-a[1]
        length = math.hypot(vx,vz)
        projection=((bx-a[0])*vx+(bz-a[1])*vz)/(length*length)
        t = np.clip(projection,0,1)
        across=np.abs((vx*(bz-a[1])-vz*(bx-a[0]))/length)
        outside=np.abs(projection-t)*length
        distance = (np.maximum(across,outside)+.5*np.minimum(across,outside))**2
        nearer = distance < squared
        station = np.where(nearer,offset+t*length,station)
        cross = np.where(nearer,(vx*(bz-a[1])-vz*(bx-a[0]))/length,cross)
        squared = np.minimum(squared,distance)
        offset += length
    return np.sqrt(squared),station,cross


def ridge_variation(station, scale, seed):
    rng = np.random.Generator(np.random.PCG64(seed))
    values = rng.random(math.ceil(float(station.max())/scale)+2)
    cell = (station/scale).astype(int)
    t = station/scale-cell
    t = t*t*(3-2*t)
    return values[cell]+(values[cell+1]-values[cell])*t


def termination_relief(termination, bx, bz):
    """西端の支尾根と、その間を海へ抜ける沢の固定断面。"""
    foundation = termination["base_y"]
    height = np.full(bx.shape, foundation, np.float32)
    for points in termination["ridges"]:
        for a,b in zip(points,points[1:]):
            dx,dz = b[0]-a[0],b[1]-a[1]
            t = np.clip(((bx-a[0])*dx+(bz-a[1])*dz)/(dx*dx+dz*dz),0,1)
            distance = np.hypot(bx-a[0]-t*dx,bz-a[1]-t*dz)
            width = a[3]+(b[3]-a[3])*t
            crest = a[2]+(b[2]-a[2])*t
            section = np.maximum(0,1-(distance/width)**1.5)
            height = np.maximum(height,foundation+(crest-foundation)*section)
    for points in termination["valleys"]:
        for a,b in zip(points,points[1:]):
            dx,dz = b[0]-a[0],b[1]-a[1]
            t = np.clip(((bx-a[0])*dx+(bz-a[1])*dz)/(dx*dx+dz*dz),0,1)
            distance = np.hypot(bx-a[0]-t*dx,bz-a[1]-t*dz)
            width = a[3]+(b[3]-a[3])*t
            cut = np.clip(1-distance/width,0,1)
            cut = cut*cut*(3-2*cut)
            floor = a[2]+(b[2]-a[2])*t
            height += np.minimum(0,floor-height)*cut
    weight = np.clip((termination["join_x"]-bx)/termination["transition_blocks"],0,1)
    return height,weight*weight*(3-2*weight)


def mountain_relief(config, bx, bz, height, nw):
    """承認済みの範囲内にだけ、主稜線・峰・支稜と谷を作る。"""
    rugged = np.clip(1-np.abs(noise(config["fixed_seed"]+91)/12),0,1)**2
    warp = noise(config["fixed_seed"]+92)/12
    influence = np.zeros(height.shape,bool)
    for i,mountain in enumerate(config["mountains"]):
        distance,station,cross = mountain_coordinates(mountain,bx,bz)
        radius = mountain["radius_blocks"]
        height += np.clip(1-distance/(radius*2.4),0,1)*(mountain["foothill_y"]-config["terrain_base_y"])*nw
        envelope = np.clip(1-distance/radius,0,1)
        phase = station/230+2.2*ridge_variation(station,600,config["fixed_seed"]+i+96)+distance/180+np.sign(cross)*.8+warp*2
        spurs = np.clip(1-np.abs(np.sin(phase))/(.18+.20*rugged),0,1)*(.5+.5*rugged)
        terminal = np.clip(station/900,0,1) if i==0 else np.ones_like(station)
        target = 110+60*terminal+(55+50*terminal)*envelope+55*spurs*envelope
        for summit in mountain["summits"]:
            px,pz = summit["xz"]
            angle = math.radians(summit["axis_degrees"])
            u = ((bx-px)*math.cos(angle)+(bz-pz)*math.sin(angle))/summit["radius_blocks"]
            v = (-(bx-px)*math.sin(angle)+(bz-pz)*math.cos(angle))/(summit["radius_blocks"]*summit["aspect"])
            shoulder=np.clip((np.abs(u)+np.abs(v))/.2,0,1)
            v=v+(.12*np.sin(u*5)+warp*.08)*shoulder
            r = .58*np.abs(u)+.72*np.abs(v)+.24*np.abs(v+u*.7)
            cone = np.clip(1-r,0,1)**1.05
            target = np.maximum(target,170+(summit["peak_y"]-170)*cone)
        if i == 0:
            terminal_height,terminal_weight = termination_relief(mountain["termination"],bx,bz)
            target += (terminal_height-target)*terminal_weight
        active = (distance < radius) & nw
        blend = np.clip((radius-distance)/(radius*.25),0,1)
        blend = blend*blend*(3-2*blend)
        shaped = height+(target-height)*blend
        height = np.where(active,shaped if i==0 else np.maximum(height,shaped),height)
        influence |= active
    for mountain in config["mountains"]:
        for saddle in mountain["passes"]:
            along=bz-saddle["xz"][1]
            across=np.abs(bx-saddle["xz"][0]+30*np.sin(along/150))
            blend = np.clip((480-np.maximum(across*.9,np.abs(along)*.6))/200,0,1)
            blend = blend*blend*(3-2*blend)
            target = saddle["surface_y"]+np.abs(along)*.10+across*.35
            height += np.minimum(0,target-height)*blend
    return height,influence


def grade_walkway(points, height):
    """徒歩経路の基準高を1/8以下にし、配置図に平坦な路面を焼き込まない。"""
    points = np.array(points,dtype=float)
    lengths = np.linalg.norm(np.diff(points,axis=0),axis=1)
    levels = np.array([height[int(pixel(p)[1]),int(pixel(p)[0])] for p in points],dtype=float)
    station = np.r_[0,np.cumsum(lengths)]
    start_distance = np.maximum(0,station-128)/8
    end_distance = np.maximum(0,station[-1]-station-128)/8
    lower = np.maximum(levels[0]-start_distance,levels[-1]-end_distance)
    upper = np.minimum(levels[0]+start_distance,levels[-1]+end_distance)
    levels = np.clip(levels,lower,upper)
    for i in range(1,len(levels)):
        levels[i] = np.clip(levels[i],levels[i-1]-lengths[i-1]/8,levels[i-1]+lengths[i-1]/8)
    for i in range(len(levels)-2,-1,-1):
        levels[i] = np.clip(levels[i],levels[i+1]-lengths[i]/8,levels[i+1]+lengths[i]/8)
    return height,[[int(p[0]),int(p[1]),round(float(y),4)] for p,y in zip(points,levels)]


def island_component(labels, anchor):
    """指定した既存島の4近傍連結成分。輪郭や陸海ラベルは変更しない。"""
    x,y = pixel(anchor)
    if labels[y,x] != 4:
        raise ValueError(f"島の峰のアンカーは既存島に置きます: {anchor}")
    component = np.zeros(labels.shape,bool)
    queue = deque([(y,x)])
    component[y,x] = True
    while queue:
        y,x = queue.popleft()
        for a,b in [(y-1,x),(y+1,x),(y,x-1),(y,x+1)]:
            if 0<=a<SIZE and 0<=b<SIZE and labels[a,b]==4 and not component[a,b]:
                component[a,b] = True
                queue.append((a,b))
    return component


def island_relief(config, labels, height, mountain_mask):
    peaks = []
    for peak in config["mountain_islands"]:
        component = island_component(labels,peak["anchor_xz"])
        ys,xs = np.nonzero(component)
        x0,x1,y0,y1 = xs.min(),xs.max()+1,ys.min(),ys.max()+1
        mask = component[y0:y1,x0:x1]
        depth = np.zeros(mask.shape,np.float32)
        interior = mask & ~neighbors(~mask)
        # 島の外は必ず海として侵食する。
        interior[[0,-1],:] = False
        interior[:,[0,-1]] = False
        while interior.any():
            depth[interior] += 16
            interior &= ~neighbors(~interior)
        y,x = np.mgrid[:mask.shape[0],:mask.shape[1]]
        target = np.full(mask.shape,63.,np.float32)
        for summit in peak["summits"]:
            angle=math.radians(summit["axis_degrees"])
            dx=(x+x0)*16-16384-summit["xz"][0]
            dz=(y+y0)*16-16384-summit["xz"][1]
            along=(dx*math.cos(angle)+dz*math.sin(angle))/summit["radius_blocks"]
            across=(-dx*math.sin(angle)+dz*math.cos(angle))/(summit["radius_blocks"]*.65)
            shoulder=np.clip((np.abs(along)+np.abs(across))/.3,0,1)
            across-=.16*np.sin(along*5)*shoulder
            profile=np.clip(1-.56*np.abs(along)-.72*np.abs(across)-.22*np.abs(across+along*.65),0,1)
            target=np.maximum(target,63+(summit["peak_y"]-63)*profile)
        target=np.maximum(64,target)
        cx,cy=pixel(peak["summits"][0]["xz"])
        cx-=x0;cy-=y0
        area = height[y0:y1,x0:x1]
        area[mask] = target[mask]
        mountain_mask |= component
        spans = []
        for row in range(y0,y1):
            columns = np.flatnonzero(component[row])
            start = previous = int(columns[0])
            for column in [*columns[1:],-1]:
                column = int(column)
                if column != previous+1:
                    spans.append([int(row),start,previous+1])
                    start = column
                previous = column
        peaks.append(dict(peak,summit_xz=[int((cx+x0)*16-16384),int((cy+y0)*16-16384)],
                          inland_depth_blocks=int(depth.max()),pixel_spans_y_x0_x1ex=spans))
    return peaks


def validate_geology(definition):
    """カタログで確認済みの岩石参照と、地層の正の厚さ範囲を検査する。"""
    if not -127 <= definition["island_reference_y"] <= 575:
        raise ValueError("島の地層の基準Yが生成範囲外です")
    rocks = set(definition["catalog"]["rock_ids"]) | {"minecraft:stone", "minecraft:granite", "minecraft:sandstone"}
    for profile in definition["profiles"].values():
        if not -127 <= profile["reference_y"] <= 575:
            raise ValueError("地質の絶対基準Yが生成範囲外です")
        if "uplift" in profile and (profile["uplift"]["transition_blocks"] <= 0 or profile["uplift"]["drop_blocks"] < 0):
            raise ValueError("地質の隆起の移行幅・変位が不正です")
        if not profile["layers_top_to_bottom"] or profile["basement"] not in rocks:
            raise ValueError("地質プロファイルの地層・基盤岩が不正です")
        for layer in profile["layers_top_to_bottom"]:
            low, high = layer["thickness_blocks"]
            if layer["rock"] not in rocks or not 1 <= low <= high:
                raise ValueError(f"地層の母岩・厚さ範囲が不正です: {layer['id']}")


def generate(input_dir, output):
    """検査済みの資産一式を置き換える。生成中・通常の置換失敗では旧出力を保持する。"""
    input_dir, output = Path(input_dir).resolve(), Path(output).resolve()
    if output.exists() and any(not p.is_file() or p.name not in (*RESOURCE_FILES,"manifest.json") for p in output.iterdir()):
        raise ValueError("出力先に生成資産以外のファイルがあります")
    output.parent.mkdir(parents=True,exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{output.name}-",dir=output.parent))
    previous = staging/"previous"
    published = False
    try:
        layers,index = generate_assets(input_dir,staging/"map")
        validate(staging/"map",index["map_version"])
        if output.exists():
            output.rename(previous)
        try:
            (staging/"map").rename(output)
        except OSError:
            if previous.exists():
                previous.rename(output)
            raise
        published = True
        return layers,index
    finally:
        # 復旧まで失敗した場合は旧出力を含む一時ディレクトリを保全する。
        if published or not previous.exists():
            shutil.rmtree(staging)


def generate_assets(input_dir, output):
    config, labels = load_inputs(input_dir)
    geology_definition = json.loads((input_dir/"geology.json").read_text(encoding="utf-8"))
    validate_geology(geology_definition)
    land = labels > 0
    nw = labels == 1
    z, x = np.mgrid[:SIZE, :SIZE]
    bx, bz = x*16-16384, z*16-16384
    print("標高ノイズ → 山脈 → 海岸", flush=True)
    height = np.where(land, config["terrain_base_y"] + noise(config["fixed_seed"])*.65, 40).astype(np.float32)
    height,mountain_mask = mountain_relief(config,bx,bz,height,nw)
    # 源流は主脈と分岐に挟まれる谷頭。北で閉じ、南へ開く谷にする。
    source_x,source_z = config["river"]["points"][0]
    head = np.abs(bx-source_x+40*np.sin((bz-source_z)/160))*.8
    head += np.maximum(0,source_z-bz)*.4+np.maximum(0,bz-source_z)*.06
    height = np.where(nw,np.minimum(height,110+head),height)
    # 谷頭の底は雪を溜める浅いカール。水面は細い河道だけで決める。
    source_distance = np.hypot(bx-source_x,bz-source_z)
    cirque = 114+(bx-source_x)**2/500+np.maximum(0,source_z-bz)**2/300
    cirque += np.maximum(0,bz-source_z)*.025
    cirque_weight = np.clip((400-source_distance)/200,0,1)
    cirque_weight = cirque_weight**2*(3-2*cirque_weight)*nw
    height += (cirque-height)*cirque_weight
    coast = land & neighbors(~land)
    # 海岸の平滑な遷移。元の陸海輪郭は変えない。
    shore = np.array(Image.fromarray((~land).astype("uint8")*255).filter(ImageFilter.GaussianBlur(5)), dtype=np.float32)/255
    height = np.where(land, 64 + (height-64)*(1-shore), height)
    height[coast] = 64
    terminal_height,terminal_weight = termination_relief(config["mountains"][0]["termination"],bx,bz)
    height += np.maximum(0,terminal_height-config["mountains"][0]["termination"]["base_y"])*terminal_weight*shore*nw
    island_peaks = island_relief(config,labels,height,mountain_mask)
    print("窪地処理と流路", flush=True)
    river = config["river"]
    river_mask = np.zeros(land.shape, bool)
    surface = np.full(land.shape, 63, np.int16)
    points = curved_points(river["points"], config["fixed_seed"]+101, river["meander_blocks"])
    lengths = [math.dist(a,b) for a,b in zip(points,points[1:])]
    total = sum(lengths)
    distance = 0
    profile = []
    radius = max(1, river["width_blocks"]//32)
    previous_level = 110
    for a, b, length in zip(points, points[1:], lengths):
        steps = max(abs(pixel(a)[0]-pixel(b)[0]), abs(pixel(a)[1]-pixel(b)[1]))
        for i in range(steps+1):
            t = i / max(steps,1)
            p = [round(v+(u-v)*t) for v,u in zip(a,b)]
            px, py = pixel(p)
            level = round(110-47*(distance+length*t)/total)
            bank_height = min(height[np.clip(py+dz,0,SIZE-1),np.clip(px+dx,0,SIZE-1)]
                              for dx,dz in [(radius+2,0),(-radius-2,0),(0,radius+2),(0,-radius-2)])
            level = max(63,min(level,previous_level,math.floor(bank_height)-3))
            previous_level = level
            profile.append([px,py,level])
            river_mask[max(0,py-radius):py+radius+1,max(0,px-radius):px+radius+1] = True
            surface[max(0,py-radius):py+radius+1,max(0,px-radius):px+radius+1] = level
        distance += length
    valley_image = Image.new("L",(SIZE,SIZE))
    ImageDraw.Draw(valley_image).line([pixel(p) for p in points],fill=255,width=25)
    valley = np.array(valley_image.filter(ImageFilter.GaussianBlur(10)),dtype=np.float32)/255
    valley_y = np.interp(bz[:,0],[p[1] for p in river["points"]],np.linspace(110,63,len(river["points"])))[:,None]+6
    height = np.where(land,height*(1-valley)+np.minimum(height,valley_y)*valley,height)
    height[river_mask & land] = surface[river_mask & land]-2
    # 固定入口を高い峰で塞がず、既存の短い地下経路へ下りられる谷口を残す。
    underground = underground_document()
    approaches = []
    for deposit in underground["deposits"]:
        if deposit["id"].startswith("oil_") or deposit["vanilla"]:
            continue
        length = max(96,deposit["depth"]+24)
        ex,ez = deposit["x"]-length,deposit["z"]
        approaches.append((ex,ez,deposit["x"],deposit["depth"]))
    for space in underground["spaces"]:
        ex = space["x"]-int(np.sign(space["end_x"]-space["x"]))* (space["depth"]+16)
        approaches.append((ex,space["z"],space["x"],space["depth"]))
    for ex,ez,tx,depth in approaches:
        length = abs(tx-ex)
        px,py = pixel([tx,ez])
        limit = height[py,px]-depth+length-40
        for along in range(16,length,16):
            sx,sy = pixel([ex+int(np.sign(tx-ex))*along,ez])
            limit = min(limit,height[sy,sx]+along*.65-16)
        x0,y0 = pixel([ex-96,ez-96]);x1,y1 = pixel([ex+96,ez+96])
        area = np.s_[max(0,y0):min(SIZE,y1+1),max(0,x0):min(SIZE,x1+1)]
        distance = np.hypot(bx[area]-ex,bz[area]-ez)
        blend = np.clip((96-distance)/80,0,1)
        blend = blend*blend*(3-2*blend)
        along = (bx[area]-ex)*np.sign(tx-ex)
        blend *= np.clip((length-along)/(length*.6),0,1)
        cut = np.minimum(height[area],max(64,limit))
        height[area] += (cut-height[area])*blend
    # 施設と歩行経路は流路を避けて整地し、最後の排水解析にも反映する。
    for facility in config["facilities"]:
        px, py = pixel(facility["xz"])
        if not nw[py,px]:
            raise ValueError(f"施設が北西大陸外です: {facility['id']} {facility['xz']}")
        r = facility["protection_radius"]
        distance_to_anchor = np.sqrt((bx-facility["xz"][0])**2+(bz-facility["xz"][1])**2)
        footprint = distance_to_anchor <= r
        if np.any(footprint & (~land | river_mask)):
            raise ValueError(f"施設の保護域が水域と重なります: {facility['id']}")
        blend = np.clip((r+128-distance_to_anchor)/128,0,1)
        height = np.where(land & ~river_mask, height*(1-blend)+facility["surface_y"]*blend,height)
    route_land = nw.copy()
    for deposit in underground_document()["deposits"]:
        if deposit["id"].startswith("oil_"):
            rx,_,rz = deposit["radii"]
            route_land &= ((bx-deposit["x"])/(rx+32))**2+((bz-deposit["z"])/(rz+32))**2>1
    routes = []
    walkways = []
    for route in config["routes"]:
        route_points = terrain_route(route["points"],height,route_land)
        routes.append(dict(route,points=route_points,length_blocks=sum(math.dist(a,b) for a,b in zip(route_points,route_points[1:]))))
        if route["id"] == "north_path":
            height,graded = grade_walkway(route_points,height)
            walkways.append({"id": "north_path", "points_x_z_y": graded})
    mining = next(f["xz"] for f in config["facilities"] if f["id"]=="mining")
    spawn = next(f["xz"] for f in config["facilities"] if f["id"]=="spawn")
    access = terrain_route([spawn,mining],height,route_land)
    height,graded = grade_walkway(access,height)
    walkways.append({"id": "mining_access", "points_x_z_y": graded})
    height = np.rint(height).astype(np.int16)
    parent, accumulation = drainage(height, land)
    print("地質 → 土壌・水域 → 施設・経路", flush=True)
    base_region = np.full(land.shape,255,np.uint8)
    for polygon in config["region_polygons"]:
        image = Image.new("L", (SIZE, SIZE))
        ImageDraw.Draw(image).polygon([pixel(p) for p in polygon["points"]], fill=1)
        base_region[np.array(image)>0] = polygon["id"]
    boundary = config["region_boundaries"]
    terrain = np.array(Image.fromarray(np.clip(height,0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(18)),dtype=np.float32)
    main = config["mountains"][0]
    ridge_z = np.interp(bx[0],[p[0] for p in main["points"]],[p[1] for p in main["points"]])
    offset_z = (np.mean(ridge_z)-ridge_z)[None,:]+noise(config["fixed_seed"]+73)*boundary["north_south_noise_scale"]+(terrain-90)*boundary["elevation_z_scale"]
    offset_x = noise(config["fixed_seed"]+74)*boundary["east_west_noise_scale"]+(terrain-90)*boundary["elevation_x_scale"]+180*np.sin(bz/1800)
    sample_x = np.clip(np.rint(x+offset_x/16).astype(int),0,SIZE-1)
    sample_z = np.clip(np.rint(z+offset_z/16).astype(int),0,SIZE-1)
    region = np.where(land,255,0).astype(np.uint8)
    region[nw] = base_region[sample_z[nw],sample_x[nw]]
    river_z = [p[1] for p in river["points"]]
    river_x = [p[0] for p in river["points"]]
    farm_divide = np.interp(bz[:,0],river_z,river_x)[:,None]-boundary["agriculture_river_offset"]
    temperate = np.isin(region,[1,2])
    region[temperate & (bx < farm_divide)] = 1
    region[temperate & (bx >= farm_divide)] = 2
    divide_points = boundary["southern_divide"]
    divide = np.interp(bz[:,0],[p[1] for p in divide_points],[p[0] for p in divide_points])[:,None]+(terrain-90)*10+noise(config["fixed_seed"]+75)*8
    southern = np.isin(region,[4,5,6])
    region[southern & (bx < divide)] = 4
    region[southern & (bx >= divide)] = 5
    near_sea = ~land.copy()
    for _ in range(boundary["coastal_distance_blocks"]//16):
        near_sea |= neighbors(near_sea)
    region[southern & (bx >= divide+2500) & (bz+offset_z > -6144) & near_sea] = 6
    geology = np.where(land,1,0).astype(np.uint8)
    geology[region==3] = 3
    geology[np.isin(region,[1,2,5])] = 2
    geology[region==4] = 4
    geology[region==6] = 5
    geology[mountain_mask] = 3
    for deposit in config["deposits"]:
        px, py = pixel(deposit["xz"])
        if not nw[py,px] or geology[py,px] != deposit["geology_profile"]:
            raise ValueError(f"鉱床アンカーと地質プロファイルが一致しません: {deposit['id']}")
    soil = np.zeros(land.shape, np.uint8)
    for rid, value in [(1,2),(2,3),(3,2),(4,3),(5,3),(6,3),(7,2)]:
        soil[region==rid] = value
    water = np.where(land,0,8).astype(np.uint8)
    water[river_mask & land] = 4
    water[river_mask & np.isin(region,[5,6])] = 6
    flags = coast.astype(np.uint8) | np.where(~land,8,0).astype(np.uint8)
    flags[river_mask & land] |= 2
    height[~land] = 40
    layers = dict(height=(height+64).astype(np.uint16), region=region, water=water,
                  soil=soil, geology=geology, coast_river=flags)
    output.mkdir(parents=True, exist_ok=True)
    for name, data in layers.items():
        Image.fromarray(data).save(output / f"{name}.png", compress_level=9)
    counts = np.bincount(labels.ravel(), minlength=5)[1:]
    ratios = (counts/counts.sum()).tolist()
    spawn = config["facilities"][0]["xz"]
    facilities = []
    for facility in config["facilities"]:
        px, py = pixel(facility["xz"])
        facilities.append(dict(facility, distance_blocks=math.dist(spawn,facility["xz"]),
                               actual_surface_y=int(height[py,px])))
    spans = []
    wet = (water>0) & land
    for y in range(SIZE):
        row = np.flatnonzero(wet[y])
        if not len(row):
            continue
        start = previous = int(row[0])
        for p in [*row[1:], -1]:
            p = int(p)
            if p != previous+1 or surface[y,p] != surface[y,start]:
                spans.append([y,start,previous+1,int(surface[y,start])])
                start = p
            previous = p
    tables = {
        "region": config["regions"],
        "soil": {"0": ["露岩・未割当て", "Bare or unassigned"], "1": ["肥沃", "Fertile"], "2": ["貧栄養", "Poor"], "3": ["劣化", "Degraded"]},
        "water": {str(i): name for i,name in enumerate(["dry", "ultrapure_water", "pure_water", "clean_water", "raw_water", "turbid_water", "contaminated_water", "toxic_water", "polluted_seawater"])},
        "geology": {"0": "ocean", "1": "unclassified_land", "2": "plain_profile", "3": "mountain_profile", "4": "arid_profile", "5": "coastal_profile"},
        "coast_river_bits": {"1": "land_coast", "2": "river", "4": "lake", "8": "ocean"}}
    index = {"map_version": config["map_version"], "format_version": 1,
             "geology_profile_version": geology_definition["profile_version"],
             "coordinate_system": {"origin_xz": [-16384,-16384], "blocks_per_pixel": 16, "sample": "northwest_corner", "north": "-Z", "height_offset": -64, "latitude": "-80 * z / 16384"},
             "tables": tables, "water_bodies": [{"id": "ocean", "surface_y": 63, "quality": 8, "membership_bit": 8},
                 {"id": river["id"], "membership_bit": 2, "surface_spans_y_x0_x1ex_y": spans, "centerline_pixel_x_y_surface": profile}],
             "facilities": facilities, "routes": routes, "walkways": walkways, "mountains": config["mountains"],
             "mountain_islands": island_peaks,
             "underground_surface_controls": config["underground_surface_controls"],
             "underground_weathered_contacts": config["underground_weathered_contacts"],
             "ocean_features": config["ocean_features"],
             "deposits": config["deposits"], "deferred": config["deferred"],
             "metrics": {"land_pixels": counts.tolist(), "land_area_ratios": ratios,
                         "area_ratio_reference": config["area_ratio_reference"], "area_ratio_tolerance": config["area_ratio_tolerance"],
                         "area_ratio_within_reference": bool(np.all(np.abs(np.array(ratios)-config["area_ratio_reference"]) <= config["area_ratio_tolerance"])),
                         "max_catchment_pixels": int(accumulation[land].max()),
                         "drainage_land_cells": int(np.count_nonzero(parent[land]>=0))}}
    write_json(output / "index.json", index)
    write_json(output / "geology_profiles.json",dict(geology_definition,map_version=config["map_version"]))
    underground = underground_document()
    underground.update(map_version=config["map_version"], fixed_seed=config["fixed_seed"])
    write_json(output / "underground.json", underground)
    inputs = {p.relative_to(input_dir).as_posix(): sha(p) for p in sorted(input_dir.iterdir()) if p.is_file()}
    tool_files = {f"tools/fixed-map/{p}": sha(TOOL/p) for p in ["generate.py", "extract_mask.py", "requirements.txt", "write_underground.py"]}
    manifest = {"map_version": config["map_version"], "format_version": config["format_version"],
                "fixed_seed": config["fixed_seed"], "width": 32768, "depth": 32768,
                "min_y": -128, "height": 704, "raster_width": SIZE, "raster_depth": SIZE,
                "catalog_version": config["catalog_version"], "inputs": inputs,
                "geology_profile_version": geology_definition["profile_version"],
                "tool": {"version": TOOL_VERSION, "python": "3.12", "numpy": np.__version__, "pillow": PIL.__version__, "files": tool_files},
                "files": {name: sha(output/name) for name in RESOURCE_FILES}}
    write_json(output / "manifest.json", manifest)
    print(json.dumps(index["metrics"], ensure_ascii=False), flush=True)
    return layers, index


def preview(layers, index, path):
    palette = np.zeros((256,3), np.uint8)
    for i, color in {0:(15,43,62),1:(143,153,100),2:(146,170,72),3:(151,125,100),4:(199,160,107),5:(118,133,132),6:(96,135,168),7:(186,211,225),255:(120,120,120)}.items():
        palette[i] = color
    elevation = layers["height"].astype(np.float32)
    dz, dx = np.gradient(elevation)
    shade = np.clip(.90+(-dx-dz)*.05,.55,1.2)
    rgb = np.clip(palette[layers["region"]]*shade[:,:,None],0,255).astype(np.uint8)
    rgb[layers["coast_river"] & 2 != 0] = [55,157,220]
    image = Image.fromarray(rgb)
    draw = ImageDraw.Draw(image)
    for route in index["routes"]:
        draw.line([pixel(p) for p in route["points"]], fill=(240,170,85) if route["kind"]=="rail_remains" else (195,195,195), width=3)
    for facility in index["facilities"]:
        x,y = pixel(facility["xz"])
        draw.ellipse((x-6,y-6,x+6,y+6),fill=(255,50,50) if facility["id"]=="spawn" else (255,255,255))
        draw.text((x+10,y),facility["id"],fill="white")
    for latitude in [-60,-40,-20,0,20,40,60,80]:
        y = round((-latitude*16384/80+16384)/16)
        draw.line((0,y,SIZE,y),fill=(110,160,180),width=1)
        draw.text((5,y+3),f"{latitude:+} deg",fill="white")
    path.parent.mkdir(parents=True,exist_ok=True)
    image.save(path)


def validate(output, expected_version):
    """指定版に対し、資産の完全性・型と地質プロファイルの参照を検査する。"""
    manifest = json.loads((output/"manifest.json").read_text(encoding="utf-8"))
    if manifest["map_version"] != expected_version or manifest["format_version"] != 1:
        raise ValueError("マップ版・形式が一致しません")
    required = set(RESOURCE_FILES)
    if not required <= manifest["files"].keys():
        raise ValueError("レイヤーが不足しています")
    for name, digest in manifest["files"].items():
        if not (output/name).is_file() or sha(output/name) != digest:
            raise ValueError(f"不足・ハッシュ不一致: {name}")
    for name in LAYERS:
        expected_mode = "I;16" if name=="height" else "L"
        with Image.open(output/f"{name}.png") as image:
            if image.size != (SIZE,SIZE) or image.mode != expected_mode:
                raise ValueError(f"寸法・型が不正です: {name}")
    definition = json.loads((output/"geology_profiles.json").read_text(encoding="utf-8"))
    if definition["map_version"] != expected_version or definition["format_version"] != 1 or definition["profile_version"] != manifest["geology_profile_version"]:
        raise ValueError("地質プロファイルの版・形式が一致しません")
    validate_geology(definition)
    with Image.open(output/"geology.png") as image:
        if not set(np.unique(np.array(image))) <= {int(key) for key in definition["profiles"]}:
            raise ValueError("地質図が未定義のプロファイルを参照しています")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input",type=Path,default=TOOL/"input")
    parser.add_argument("--output",type=Path,help="省略時は入力map_versionの同梱ディレクトリ")
    parser.add_argument("--preview",type=Path)
    parser.add_argument("--validate",action="store_true")
    parser.add_argument("--update-preset",action="store_true",help="同梱プリセットを生成した版とmanifestハッシュへ更新する")
    args = parser.parse_args(argv)
    if sys.version_info[:2] != (3,12):
        parser.error("再現環境はPython 3.12です")
    args.input = args.input.resolve()
    config = json.loads((args.input/"layout.json").read_text(encoding="utf-8"))
    output = args.output.resolve() if args.output else ROOT/MAP_RESOURCES/config["map_version"]
    if args.validate:
        validate(output,config["map_version"])
    else:
        layers,index = generate(args.input,output)
        if args.preview:
            preview(layers,index,args.preview)
        if args.update_preset:
            preset_path = ROOT/"modules/world/src/main/resources/data/ashen_frontier/worldgen/world_preset/fixed_map.json"
            preset = json.loads(preset_path.read_text(encoding="utf-8"))
            preset["dimensions"]["minecraft:overworld"]["type"] = "ashen_frontier:fixed_map"
            preset["dimensions"]["minecraft:overworld"]["generator"]["map"] = {
                "map_version": index["map_version"], "format_version": 1,
                "manifest_hash": sha(output/"manifest.json")}
            generator = preset["dimensions"]["minecraft:overworld"]["generator"]
            if "map" in generator["biome_source"]:
                generator["biome_source"]["map"] = generator["map"].copy()
            write_json(preset_path,preset)


if __name__ == "__main__":
    main()
