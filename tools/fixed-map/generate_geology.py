"""earth_002の承認地質・残鉱・自然洞窟を、実列でクリップした疎な区間へ固定する。"""
import hashlib
import heapq
import json
import math
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def block_id(value):
    return value if ":" in value else ("minecraft:" if value in ("granite", "andesite", "sandstone") else "ashen_frontier:") + value


def curved(points, step=1):
    p = np.array(points, float)
    if len(p)==2:
        middle=p[0]*.43+p[1]*.57
        dx,dz=p[1,:2]-p[0,:2]
        length=math.hypot(dx,dz)
        middle[:2]+=np.array([-dz,dx])*min(4.5,length*.1)/length
        p=np.vstack([p[0],middle,p[1]])
    result = []
    for i in range(len(p)-1):
        a, b, c, d = p[max(0,i-1)], p[i], p[i+1], p[min(len(p)-1,i+2)]
        count = max(2, math.ceil(np.linalg.norm(c[:2]-b[:2])/step))
        for t in np.linspace(0,1,count,endpoint=False):
            result.append(.5*(2*b+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t))
    return np.array(result+[p[-1]])


def hash_value(x, y, z, seed):
    n = (x*73856093 ^ y*19349663 ^ z*83492791 ^ seed*2654435761) & 0xffffffff
    n = ((n ^ (n >> 16))*2246822519) & 0xffffffff
    return ((n ^ (n >> 13)) & 65535)/65535


def field(x, y, z, seed):
    q = [x/9, y/6, z/11]
    base = [math.floor(v) for v in q]
    t = [v-b for v,b in zip(q,base)]
    t = [v*v*(3-2*v) for v in t]
    return sum(hash_value(base[0]+i,base[1]+j,base[2]+k,seed)
               *(t[0] if i else 1-t[0])*(t[1] if j else 1-t[1])*(t[2] if k else 1-t[2])
               for i in (0,1) for j in (0,1) for k in (0,1))


NEIGHBORS = [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]


def path(a, b):
    a = list(a)
    yield tuple(a)
    while tuple(a) != tuple(b):
        i = max(range(3), key=lambda k:abs(b[k]-a[k]))
        a[i] += 1 if b[i]>a[i] else -1
        yield tuple(a)


def body(p, ground, flags):
    cx, cz = p["xz"]
    cy = p["center_y"]
    x0,z0,_,_ = p["sample_bounds"]
    seed = p["number"]
    def height(x,z):
        return int(ground[z-z0,x-x0])
    def permitted(v):
        x,y,z = v
        return x0+2<=x<x0+94 and z0+2<=z<z0+94 and -123<y<=height(x,z)-2 and flags[z-z0,x-x0]<2
    chosen = {(cx,cy,cz)}
    exposure = set()
    stripped = {}
    count = p.get("exposure_budget_blocks",0)
    if count:
        ex,ez = p["outcrop_xz"] if "outcrop_xz" in p else p["xz"]
        # 曲がる露出帯。一定幅の矩形や円形の保持パッドにしない。
        patch = []
        for dx in range(-9,10):
            for dz in range(-9,10):
                x,z = ex+dx,ez+dz
                if not(x0+3<=x<x0+93 and z0+3<=z<z0+93) or flags[z-z0,x-x0]>=2:
                    continue
                cross = dz-.32*dx-1.6*math.sin(dx*.23+.4)
                score = (dx/7)**2+(cross/2.5)**2+.65*field(x,0,z,seed)
                if p["target_blocks"]<500:score+=((height(x,z)-height(ex,ez))/1.7)**2
                patch.append((score,x,z))
        for _,x,z in sorted(patch)[:count]:
            top = height(x,z)-2
            exposure.add((x,top,z))
            stripped[x,z] = top
        if len(exposure)!=count:
            raise ValueError("露出面の候補不足: "+p["id"])
        anchor = min(exposure, key=lambda v:math.dist(v,(cx,cy,cz)))
        for v in path((cx,cy,cz),anchor):
            for dx,dy,dz in ([(0,0,0),(1,0,0),(0,-1,0),(0,0,1)] if p["target_blocks"]<500 else [(0,0,0),(1,0,0),(-1,0,0),(0,-1,0),(0,0,1),(0,0,-1)]):
                q = (v[0]+dx,v[1]+dy,v[2]+dz)
                if permitted(q):chosen.add(q)
        # 隣接する面を地下側で連続させ、地表を鉱石の点列にしない。
        for v in exposure:
            for q in path(anchor,v):
                if permitted(q):chosen.add(q)
        chosen |= exposure
    # 地表の窪みで切れた露出帯は、母岩内の最短の接続を補い、飛び石にしない。
    def component():
        found={(cx,cy,cz)};queue=[(cx,cy,cz)]
        for x,y,z in queue:
            for dx,dy,dz in NEIGHBORS:
                v=(x+dx,y+dy,z+dz)
                if v in chosen and v not in found:found.add(v);queue.append(v)
        return found
    core=component()
    while len(core)<len(chosen):
        target=min(chosen-core,key=lambda v:(math.dist(v,(cx,cy,cz)),v))
        start=min(core,key=lambda v:(math.dist(v,target),v))
        frontier=[(sum(abs(a-b) for a,b in zip(start,target)),0,start)]
        cost={start:0};previous={}
        while frontier:
            _,distance,v=heapq.heappop(frontier)
            if v==target:break
            for delta in NEIGHBORS:
                q=tuple(a+b for a,b in zip(v,delta))
                if permitted(q) and distance+1<cost.get(q,1000000):
                    cost[q]=distance+1;previous[q]=v
                    heapq.heappush(frontier,(distance+1+sum(abs(a-b) for a,b in zip(q,target)),distance+1,q))
        else:raise ValueError("露出帯を母岩内で接続できません: "+p["id"])
        v=target
        while v!=start:chosen.add(v);v=previous[v]
        core=component()
    if len(chosen)>p["target_blocks"]:
        raise ValueError("接続脈が残存量を超えます: "+p["id"]+" "+str(len(chosen)))
    rx,ry,rz = p["radii"]
    angle = seed*.873
    c,s = math.cos(angle),math.sin(angle)
    kind = p["deposit_type"]
    def score(v):
        x,y,z = v
        dx,dz = x-cx,z-cz
        u,w = dx*c+dz*s,-dx*s+dz*c
        warped = w-2.3*math.sin(u/8+seed)-1.4*math.sin(u/17-seed*.7)
        layer = y-cy-.12*u-1.6*math.sin(u/13+seed)
        rough = field(x,y,z,seed)
        if "鉱脈" in kind:
            # 急傾斜の脈が分岐・膨縮する。水平な層の係数だけでは代用しない。
            trace=warped-.28*(y-cy)-1.3*math.sin((y-cy)/7+seed)
            a=(u/(rx*1.5))**2+(trace/max(2,rz*.3))**2+((y-cy-.3*u)/max(6,ry*1.8))**2
            branch=(u/(rx*1.1))**2+((trace-u*.63-3)/max(1.5,rz*.22))**2+((y-cy+2)/max(5,ry*1.5))**2
        elif "風化残留" in kind:
            # 風化面に沿う不定形の浅い窪地。上面を固定高さの平板にしない。
            weathered=y-cy-(height(x,z)-height(cx,cz))*.55
            a=(u/(rx*1.8))**2+(warped/(rz*.65))**2+((weathered+rough*5)/max(2,ry*.48))**2+rough*1.2
            branch=((u-7)/(rx*.75))**2+((warped-u*.72-5)/(rz*.45))**2+((weathered+2)/max(2,ry*.48))**2
        elif "砂鉱" in kind:
            channel=warped-3.4*math.sin(u/11+seed*.4)
            thickness=max(1.2,ry*(.38+.25*field(x*.7,cy,z*.7,seed+13)))
            a=(u/(rx*1.7))**2+(channel/max(2,rz*.48))**2+(layer/thickness)**2
            branch=(u/(rx*1.1))**2+((channel-u*.42-4)/max(2,rz*.35))**2+((layer+1)/thickness)**2
        elif "鉱染" in kind:
            # 残った濃集部を連続したストックワークとして持ち、単独点を散布しない。
            a=(u/(rx*1.2))**2+(warped/(rz*1.05))**2+(layer/max(4,ry*1.1))**2+rough*.8
            branch=((u+5)/(rx*.9))**2+((warped-u*.32-5)/(rz*.75))**2+((layer-3)/max(3,ry*.8))**2
        elif "塊状" in kind:
            a=(u/(rx*1.05))**2+(warped/(rz*.95))**2+(layer/max(4,ry*1.5))**2
            branch=((u-4)/(rx*.75))**2+((warped-u*.46-3)/(rz*.65))**2+((layer+2)/max(3,ry))**2
        else:
            vertical=max(1.4,ry*.62)
            a=(u/(rx*1.3))**2+(warped/(rz*.72))**2+(layer/vertical)**2
            branch=(u/(rx*.9))**2+((warped-u*.46-3)/(rz*.55))**2+((layer+1.5)/max(1.5,vertical*.8))**2
        return min(a,branch+.2)+.62*rough+.08*field(x*.47,y*.71,z*.53,seed+71)
    visited=set(chosen)
    heap=[]
    def add(v):
        if v in visited:return
        visited.add(v)
        if permitted(v):heapq.heappush(heap,(score(v),v))
    for x,y,z in sorted(chosen):
        for dx,dy,dz in NEIGHBORS:add((x+dx,y+dy,z+dz))
    while len(chosen)<p["target_blocks"]:
        if not heap:raise ValueError("鉱体の支持域不足: "+p["id"])
        _,v=heapq.heappop(heap);chosen.add(v)
        x,y,z=v
        for dx,dy,dz in NEIGHBORS:add((x+dx,y+dy,z+dz))
    return chosen,exposure,stripped


def build(input_dir):
    data=json.loads((input_dir/"geology.json").read_text(encoding="utf-8"))
    if data["schema_version"]!=1 or len(data["profiles"])!=11 or len(data["deposits"])!=40:
        raise ValueError("D4の地質/鉱床契約が不正です")
    catalog=json.loads((Path(__file__).resolve().parents[2]/"core/src/main/resources/catalog/nature.json").read_text(encoding="utf-8"))
    entries={p["id"]:p for p in catalog["entries"]}
    if {p["block"] for p in data["registered_pairs"]}!={p["id"] for p in catalog["entries"] if p["kind"]=="ore"}:
        raise ValueError("登録鉱石30組の対応が一致しません")
    vanilla={"minecraft:"+p+"_ore" for p in ["iron","copper","gold","redstone","lapis","diamond","emerald"]}
    for p in data["deposits"]:
        if type(p["target_blocks"]) is not int or p["target_blocks"]<=0:
            raise ValueError("残存Nは正の整数です")
        if p["vanilla"]:
            if p["block"] not in vanilla:raise ValueError("バニラ鉱石の局所枠が不正です")
        else:
            entry=entries.get(p["block"])
            if entry is None or entry.get("host") and block_id(entry["host"])!=block_id(p["host"]):
                raise ValueError("鉱石・母岩が登録と一致しません: "+p["id"])
    ground=np.array(Image.open(input_dir/"geology_surface.png")).astype(int)-128
    flags=np.array(Image.open(input_dir/"geology_surface_flags.png"))
    cells={}
    reserved=set()
    palette=[]
    palette_index={}
    def material(block,host):
        key=(block_id(block),block_id(host))
        if key not in palette_index:
            palette_index[key]=len(palette);palette.append(list(key))
        return palette_index[key]
    def put(v,block,host,priority):
        if v[1]<=-128:raise ValueError("岩盤を置換しません")
        old=cells.get(v)
        if old is None or priority>old[0]:cells[v]=(priority,material(block,host))
    deposits=[];handoffs=[]
    for i,p in enumerate(data["deposits"]):
        tile=ground[i*96:(i+1)*96]
        mask,exposure,stripped=body(p,tile,flags[i*96:(i+1)*96])
        if reserved & mask:raise ValueError("残鉱が重なります: "+p["id"])
        reserved|=mask
        xs,ys,zs=zip(*mask)
        bounds=[min(xs),min(ys),min(zs),max(xs)+1,max(ys)+1,max(zs)+1]
        host=p["host"];oil=p["id"].startswith("oil_")
        # 母岩の縁を固定高さの箱にせず、実際の鉱体の区間を包む。
        columns=defaultdict(list)
        for x,y,z in mask:columns[x,z].append(y)
        radius=4 if oil else 2
        x0,z0,_,_=p["sample_bounds"]
        for (x,z),values in columns.items():
            bottom,top=min(values),max(values)
            for dx in range(-radius,radius+1):
                for dz in range(-radius,radius+1):
                    if dx*dx+dz*dz>radius*radius:continue
                    xx,zz=x+dx,z+dz
                    if not(x0<=xx<x0+96 and z0<=zz<z0+96):continue
                    cap=int(tile[zz-z0,xx-x0])-2
                    for y in range(bottom-radius,min(cap,top+radius)+1):put((xx,y,zz),host,host,1)
            if oil:
                for y in range(top+3,top+15):put((x,y,z),"shale","shale",2)
        for v in mask:put(v,p["block"],host,4)
        for (x,z),floor in stripped.items():
            for y in range(floor+1,int(tile[z-z0,x-x0])+1):put((x,y,z),"minecraft:air",host,5)
        # 接面以外にも、母岩の露出を手掛かりとして地表へ示す。量Nに追加しない。
        if not oil and not exposure:
            ex,ez=p["xz"]
            patch=[];seen=set();queue=[(0,ex,ez)]
            while queue and len(patch)<18:
                _,x,z=heapq.heappop(queue)
                if (x,z) in seen:continue
                seen.add((x,z))
                if not(x0+2<=x<x0+94 and z0+2<=z<z0+94) or flags[i*96+z-z0,x-x0]!=1:continue
                patch.append((x,z))
                for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
                    xx,zz=x+dx,z+dz
                    cost=((xx-ex)/4)**2+((zz-ez-.4*(xx-ex))/2)**2+field(xx,0,zz,p["number"])
                    heapq.heappush(queue,(cost,xx,zz))
            for x,z in patch:
                put((x,int(tile[z-z0,x-x0]),z),host,host,1)
        item=dict(id=p["id"],block=p["block"],host=host,x=p["xz"][0],z=p["xz"][1],depth=int(tile[48,48])-p["center_y"],center_y=p["center_y"],
                  radii=[max(abs(min(xs)-p["xz"][0]),abs(max(xs)-p["xz"][0])),max(abs(min(ys)-p["center_y"]),abs(max(ys)-p["center_y"])),max(abs(min(zs)-p["xz"][1]),abs(max(zs)-p["xz"][1]))],
                  shape="fixed_intervals",required=p["required"],lens=True,vanilla=p["vanilla"],residual_blocks=len(mask),deposit_type=p["deposit_type"],
                  ore_columns=[dict(x=x,z=z,ys=sorted(values)) for (x,z),values in sorted(columns.items())])
        deposits.append(item)
        faces=sorted(exposure) if exposure else sorted(v for v in mask if (v[0]-1,v[1],v[2]) not in mask)
        face=min(faces,key=lambda v:math.dist(v,(p["xz"][0],p["center_y"],p["xz"][1])))
        portal=[face[0]-3,face[1]-1,face[2]]
        if oil:
            faces=[];portal=[p["xz"][0],int(tile[48,48]),p["xz"][1]]
            bounds=[bounds[0]-4,bounds[1]-4,bounds[2]-4,bounds[3]+4,bounds[4]+15,bounds[5]+4]
        handoffs.append(dict(id=p["id"],residual_blocks=len(mask),bounds=bounds,faces=[list(v) for v in faces],
                             necessary_portals=[portal],surface_clue=p["surface_clue"],mine_id=p["mine_id"],oil_seal=oil))
    cave=data["cave"]
    source=np.array(Image.open(input_dir/"geology_cave_surface.png")).astype(int)-128
    x0,z0,_,_=cave["sample_bounds"]
    axes=[]
    axes.append([[x,z,y,w,h] for (x,z),y,w,h in zip(cave["axis"],cave["floor_y"],cave["axis_full_width_B"],cave["axis_height_B"])])
    for points,floors in zip(cave["branches"],cave["branch_floor_y"]):
        axes.append([[x,z,y,4+i%2,4] for i,((x,z),y) in enumerate(zip(points,floors))])
    void=set();floors={}
    for route_index,axis in enumerate(axes):
        points=curved(axis,.6);station=0
        for prev,p in zip(points,points[1:]):
            station+=math.dist(prev[:2],p[:2])
            x,z,y,width,height=p
            width*=.85+.3*field(x,0,z,613)
            height+=(field(x,0,z,619)-.5)*1.4
            span=math.ceil(width*.5)+1
            for xx in range(math.floor(x)-span,math.ceil(x)+span+1):
                for zz in range(math.floor(z)-span,math.ceil(z)+span+1):
                    dx,dz=xx-x,zz-z
                    cross=math.hypot(dx/(width*.5),dz/(width*.43))
                    if cross>1:continue
                    floor=round(y+max(0,cross-.35)*width*.12)
                    top=floor+max(2,round(height*(1-.34*cross*cross)))
                    ground_y=int(source[zz-z0,xx-x0])
                    if (route_index>0 or station>=64) and ground_y-top<12:continue
                    floors[xx,zz]=min(floors.get((xx,zz),floor),floor)
                    for yy in range(floor+1,top+1):void.add((xx,yy,zz))
    void={v for v in void if v[1]<=floors[v[0],v[2]]+7}
    if void & reserved:raise ValueError("天然洞窟と残鉱が交差します")
    for (x,z),floor in floors.items():
        for y in range(floor-3,floor+1):put((x,y,z),"limestone","limestone",2)
    for v in void:put(v,"minecraft:air","limestone",6)
    result=defaultdict(list)
    for (x,y,z),(_,mat) in cells.items():result[x,z].append((y,mat))
    encoded=[]
    for (x,z),values in sorted(result.items()):
        runs=[]
        for y,mat in sorted(values):
            if runs and y==runs[-2]+1 and mat==runs[-1]:runs[-2]=y
            else:runs.extend([y,y,mat])
        encoded.append(dict(x=x,z=z,runs=runs))
    return dict(map_version="earth_002",format_version=1,fixed_seed=520026,salt="underground_002",
                deposits=deposits,spaces=[],d4=dict(schema_version=1,palette=palette,columns=encoded,handoffs=handoffs,
                cave=dict(id="fixed_cave",entrance=[*cave["entrance"],cave["floor_y"][0]],axes=axes,minimum_roof=12,portal_transition=64)))


def generate(input_dir,output):
    from generate import write_json
    doc=build(input_dir)
    write_json(output/"underground.json",doc)
    source=json.loads((input_dir/"geology.json").read_text(encoding="utf-8"))
    old=json.loads((output/"geology_profiles.json").read_text(encoding="utf-8"))
    profiles=dict(old["profiles"])
    for i,p in enumerate(source["profiles"],1):
        profiles[str(i+5)]=dict(name=p["name"],layers_top_to_bottom=[dict(id=f"{p['id']}_{j}",rock=block_id(rock),thickness_blocks=[low,high]) for j,(rock,low,high) in enumerate(p["layers"])],
            basement=block_id(p["basement"]),reference_y=p["reference_y"],dip=dict(anchor_xz=p["anchor_xz"],gradient_xz=p["dip_xz"]),faults=[])
    # 同じG06系統の局所変種。承認されたC01の厚い炭酸塩岩層を絶対基準Yで持つ。
    cave=source["cave"]
    profiles["17"]=dict(name="G06_C01 炭酸塩岩の局所層",system="G06",
        layers_top_to_bottom=[dict(id="cave_limestone",rock="ashen_frontier:limestone",thickness_blocks=cave["formation_limestone_thickness_B"])],
        basement="ashen_frontier:gneiss",reference_y=cave["formation_reference_y"],
        dip=dict(anchor_xz=cave["entrance"],gradient_xz=[-.011,.005]),faults=[])
    old.update(profile_version="geology_003",profiles=profiles,bottom_y=-127,bedrock_y=-128)
    write_json(output/"geology_profiles.json",old)
    classes=np.array(Image.open(output/"landform.png"));geology=np.array(Image.open(output/"geology.png"))
    for i,p in enumerate(source["profiles"],1):geology[np.isin(classes,p["physical_units"])]=i+5
    # 接触線は制作ポリゴンの直線をそのまま使わず、固定場で変形する。
    from generate_basis import field
    zz,xx=np.indices(classes.shape);x=xx*16-16384;z=zz*16-16384
    wx=x+field(x,z,193,413)*40+field(x,z,71,419)*12
    wz=z+field(x,z,211,421)*38+field(x,z,83,431)*14
    for region in source["overrides"]:
        poly=region["polygon"];inside=np.zeros(classes.shape,bool)
        for a,b in zip(poly,poly[1:]+poly[:1]):
            if a[1]==b[1]:continue
            inside^=((a[1]>wz)!=(b[1]>wz))&(wx<(b[0]-a[0])*(wz-a[1])/(b[1]-a[1])+a[0])
        geology[inside&(classes>0)]=17 if region["id"]=="C-cave" else int(region["profile"][1:])+5
    Image.fromarray(geology).save(output/"geology.png")
    index=json.loads((output/"index.json").read_text(encoding="utf-8"))
    index["geology_profile_version"]="geology_003"
    index["deposits"]=[dict(id=p["id"],xz=[p["x"],p["z"]],radius_blocks=max(p["radii"][0],p["radii"][2]),required=p["required"],residual_blocks=p["residual_blocks"]) for p in doc["deposits"]]
    index["geology_handoffs"]=doc["d4"]["handoffs"]
    index["d5_mines"]=source["d5_mines"]
    index["metrics"]["stage"]="D4; 地質・残鉱・露頭・自然洞窟。人工採掘跡と施設接続はD5"
    write_json(output/"index.json",index)
    print("D4_GEOLOGY",len(doc["d4"]["columns"]),sum(p["residual_blocks"] for p in doc["deposits"]),flush=True)
