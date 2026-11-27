"""r20のD1骨格。地域や水系とは独立した分類と、固定の稜線・谷を制作する。"""

import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

from layout_contract import validate as validate_contract


def smooth(t):
    t = np.clip(t, 0, 1)
    return t*t*(3-2*t)


def field(x, z, scale, seed):
    """回転・歪みを持つ連続場。セル単位の独立した点ノイズを使わない。"""
    u, v = (.8*x+.6*z)/scale, (-.6*x+.8*z)/scale
    ix, iz = np.floor(u).astype(np.int64), np.floor(v).astype(np.int64)
    a, b = u-ix, v-iz
    a = a*a*a*(a*(a*6-15)+10); b = b*b*b*(b*(b*6-15)+10)
    def value(dx, dz):
        q = (ix+dx).astype(np.uint64)*np.uint64(0x9e3779b185ebca87) ^ (iz+dz).astype(np.uint64)*np.uint64(0xc2b2ae3d27d4eb4f) ^ np.uint64(seed*7919)
        q ^= q >> np.uint64(30); q *= np.uint64(0xbf58476d1ce4e5b9)
        q ^= q >> np.uint64(27); q *= np.uint64(0x94d049bb133111eb)
        q ^= q >> np.uint64(31)
        return (q >> np.uint64(11)).astype(float)/(1<<53)*2-1
    return (value(0,0)*(1-a)+value(1,0)*a)*(1-b)+(value(0,1)*(1-a)+value(1,1)*a)*b


def polygon(unit, shape, x0, z0):
    from generate import curved_points
    points = unit['boundary']
    image = Image.new('L', shape[::-1])
    if points:
        curve = curved_points(points, unit['id'], spacing=24, closed=True)
        ImageDraw.Draw(image).polygon([((p[0]-x0)/16, (p[1]-z0)/16) for p in curve], fill=255)
    return image


def network(unit):
    """制御稜線を樹でつなぎ、異なる流下位置から不揃いな支稜を出す。"""
    pins = unit['pins']
    if pins[0]['role'] != 'ridge':
        return [], []
    rng = np.random.default_rng(520026+unit['id']*719)
    points = [[*p['xz'], p['control_y'], p['section_half_width']] for p in pins]
    edges = []
    if unit['kind'] == 'range':
        edges = [(i-1, i) for i in range(1, len(points))]
    elif len(points) > 1:
        reached = {0}
        while len(reached) < len(points):
            _, a, b = min((math.dist(points[a][:2], points[b][:2]), a, b)
                          for a in reached for b in range(len(points)) if b not in reached)
            reached.add(b)
            edges.append((a, b))
    else:
        p = points[0]
        angle = rng.uniform(.2, 2.8)
        dx, dz = math.cos(angle)*440, math.sin(angle)*440
        points += [[p[0]-dx, p[1]-dz, unit['base_y']+15, 110],
                   [p[0]+dx*.8, p[1]+dz*.8, unit['base_y']+9, 85]]
        edges = [(1, 0), (0, 2)]
    ridges = []
    valleys = []
    base = unit['base_y']
    for index, (ia, ib) in enumerate(edges):
        a, b = np.array(points[ia], float), np.array(points[ib], float)
        d = b[:2]-a[:2]; length = np.linalg.norm(d)
        n = np.array([-d[1], d[0]])/length
        t = rng.uniform(.35, .65)
        mid = a+(b-a)*t
        mid[:2] += n*rng.uniform(-130, 130)
        mid[2] = base+(mid[2]-base)*rng.uniform(.56, .76) if unit['id'] in (1,22,24) else base+(mid[2]-base)*rng.uniform(.76, .92)
        mid[3] *= rng.uniform(.72, 1.12)
        if unit['id'] in (1,22,24):
            if unit['id']==24:
                crest=[a.tolist()]
                fractions=[]
                for value in sorted(rng.uniform(.12,.90,size=rng.integers(4,7))):
                    if not fractions or value-fractions[-1]>.12:fractions.append(value)
                for station,fraction in enumerate(fractions):
                    q=a+(b-a)*fraction
                    q[:2]+=n*rng.uniform(-230,230)
                    scale=rng.uniform(.78,.98) if station%2==0 else rng.uniform(.40,.66)
                    q[2]=base+(q[2]-base)*scale
                    q[3]*=rng.uniform(.56,1.12)
                    crest.append(q.tolist())
            else:
                crest=[a.tolist()]
                for fraction,scale in zip((.22,.43,.66,.83),(.88,.55,.86,.63)):
                    q=a+(b-a)*fraction
                    q[:2]+=n*rng.uniform(-145,145)
                    q[2]=base+(q[2]-base)*scale
                    q[3]*=rng.uniform(.72,1.08)
                    crest.append(q.tolist())
            crest.append(b.tolist())
            ridges.append(crest)
        else:
            ridges.append([a.tolist(), mid.tolist(), b.tolist()])
        from generate import curved_points
        axis=np.array(curved_points(ridges[-1],0,spacing=24))
        arc=np.r_[0,np.cumsum(np.linalg.norm(np.diff(axis[:,:2],axis=0),axis=1))]
        station = rng.uniform(120, 310)
        axis_length=arc[-1] if unit['id']==24 else length
        while station < axis_length-100:
            t = station/axis_length
            root = np.array([np.interp(t*arc[-1],arc,axis[:,k]) for k in range(4)])
            root[3] *= .68
            side = rng.choice([-1, 1])
            if unit['id']==24:
                tangent=np.array([np.interp(min(station+24,arc[-1]),arc,axis[:,k])-np.interp(max(0,station-24),arc,axis[:,k]) for k in range(2)])
                tangent/=np.linalg.norm(tangent)
                direction=np.array([-tangent[1],tangent[0]])*side+tangent*rng.uniform(-.9,.9)
            else:
                direction = n*side + d/length*rng.uniform(-.65, .65)
            direction /= np.linalg.norm(direction)
            run = rng.uniform(160,850) if unit['id']==24 else rng.uniform(210, 650) if unit['kind'] in ('range', 'massif') else rng.uniform(150, 340)
            q = root.copy(); q[:2] += direction*run*.47+n*rng.uniform(-85, 85)
            q[2] = base+(root[2]-base)*rng.uniform(.42, .69); q[3] *= rng.uniform(.36, .62)
            tip = q.copy(); tip[:2] = root[:2]+direction*run
            tip[2] = base+3; tip[3] = rng.uniform(35, 65)
            end = tip.copy(); end[:2] += direction*rng.uniform(90, 160)+n*rng.uniform(-60, 60)
            end[2] = base; end[3] = 24
            ridges.append([root.tolist(), q.tolist(), tip.tolist(), end.tolist()])
            head = root.copy(); head[:2] += d/length*rng.uniform(90,180)+direction*root[3]*.24
            head[2] = base+(root[2]-base)*.82; head[3] = 4
            middle = head.copy(); middle[:2] += direction*run*.47+n*rng.uniform(-65,65)
            middle[2] = base+(root[2]-base)*.17; middle[3] = rng.uniform(55,110)
            outlet = middle.copy(); outlet[:2] += direction*run*.68
            outlet[2] = base-4; outlet[3] = 24
            valleys.append([head.tolist(),middle.tolist(),outlet.tolist()])
            station += np.clip(rng.gamma(1.2,350),110,1100) if unit['id']==24 else rng.uniform(290,700)
    # 軸の末端は固定点の外で下がり、平面の半円で閉じない。
    for endpoint, neighbor in [(points[edges[0][0]], points[edges[0][1]]),
                               (points[edges[-1][1]], points[edges[-1][0]])]:
        a = np.array(endpoint, float); b = np.array(neighbor, float)
        d = a[:2]-b[:2]; d /= np.linalg.norm(d)
        n = np.array([-d[1], d[0]])
        run = unit.get('terminal_transition', 280)*.85
        q = a.copy(); q[:2] += d*run*.48+n*run*.14; q[2] = base+(a[2]-base)*.42; q[3] *= .47
        tip = a.copy(); tip[:2] += d*run+n*run*.23; tip[2] = base; tip[3] = 24
        ridges.append([a.tolist(), q.tolist(), tip.tolist()])
    return ridges, valleys


def sample(height, x, z, x0=0, z0=0):
    """Javaと同じCatmull–Rom補間（画素北西角が標本）。"""
    px, pz = (x-x0)/16, (z-z0)/16
    ix, iz = math.floor(px), math.floor(pz); tx, tz = px-ix, pz-iz
    def cubic(a,b,c,d,t): return .5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t)
    rows = []
    for dz in range(-1, 3):
        row = height[min(max(iz+dz,0),height.shape[0]-1)]
        values = [row[min(max(ix+dx,0),len(row)-1)] for dx in range(-1,3)]
        rows.append(cubic(*values,tx))
    return cubic(*rows,tz)


def incise(height, valleys, x, z, strength=.55):
    """一つの谷を一度だけ合成し、曲線の分割数による掘り下げを避ける。"""
    for points in valleys:
        pad=max(p[3] for p in points)
        area=(x>=min(p[0] for p in points)-pad)&(x<=max(p[0] for p in points)+pad)&(z>=min(p[1] for p in points)-pad)&(z<=max(p[1] for p in points)+pad)
        px,pz=x[area],z[area]; nearest=np.full(px.shape,np.inf);floor=np.zeros(px.shape)
        for a,b in zip(points,points[1:]):
            dx,dz=b[0]-a[0],b[1]-a[1]
            t=np.clip(((px-a[0])*dx+(pz-a[1])*dz)/(dx*dx+dz*dz),0,1)
            ratio=np.hypot(px-a[0]-t*dx,pz-a[1]-t*dz)/(a[3]+t*(b[3]-a[3]))
            floor=np.where(ratio<nearest,a[2]+t*(b[2]-a[2]),floor)
            nearest=np.minimum(nearest,ratio)
        current=height[area]
        height[area]=current+np.minimum(0,floor-current)*smooth(1-nearest)*strength
    return height


def dissected_margin(unit, foundation, reference, x, z):
    """外形の内側へ入る乾いた谷。異なる谷頭と途中の合流で面を開析する。"""
    from generate import curved_points
    rng=np.random.default_rng(520026+unit['id']*1109)
    boundary=np.array(unit['boundary'],float);center=boundary.mean(0)
    valleys=[]
    for a,b in zip(boundary,np.roll(boundary,-1,axis=0)):
        length=np.linalg.norm(b-a)
        for _ in range(max(1,round(length/650))):
            edge=a+(b-a)*rng.uniform(.18,.86)
            direction=edge-center;direction/=np.linalg.norm(direction)
            normal=np.array([-direction[1],direction[0]])
            run=rng.uniform(560,1150) if unit['id']!=63 else rng.uniform(350,700)
            head=edge-direction*run+normal*rng.uniform(-180,180)
            outlet=edge+direction*rng.uniform(100,240)
            floor=sample(foundation,*outlet,int(x[0,0]),int(z[0,0]))
            head_y=sample(reference,*head,int(x[0,0]),int(z[0,0]))+3
            middle=(head+outlet)/2+normal*rng.uniform(-150,150)
            points=[[*head,head_y,48],[*middle,(head_y+floor)/2,rng.uniform(130,210)],
                    [*outlet,floor,rng.uniform(170,280)]]
            if unit['id']==3:
                points[0][3]=72
                points[1][3]=rng.uniform(260,440)
                points[2][3]=rng.uniform(340,540)
            parent=np.array(curved_points(points,unit['id'],spacing=32))
            valleys.append(parent.tolist())
            arc=np.r_[0,np.cumsum(np.linalg.norm(np.diff(parent[:,:2],axis=0),axis=1))]
            for t in sorted(rng.uniform(.22,.72,size=rng.integers(2,4))):
                joint=np.array([np.interp(t*arc[-1],arc,parent[:,k]) for k in range(4)])
                side=rng.choice([-1,1]);branch=joint[:2]-direction*rng.uniform(180,380)+normal*side*rng.uniform(210,480)
                bend=(branch+joint[:2])/2+direction*rng.uniform(-100,80)
                branch_y=sample(reference,*branch,int(x[0,0]),int(z[0,0]))+3
                width=160 if unit['id']==3 else 100
                valleys.append(curved_points([[*branch,branch_y,40],[*bend,(branch_y+joint[2])/2,width],joint.tolist()],unit['id']+1,spacing=32))
    return valleys


def build(input_dir):
    from generate import ridge_relief
    source = Path(__file__).parent/'input'
    labels = np.array(Image.open(source/'land.png'))
    nw = labels == 1
    yy, xx = np.where(nw)
    y0,y1,x0,x1 = max(0,yy.min()-4),min(2048,yy.max()+5),max(0,xx.min()-4),min(2048,xx.max()+5)
    mask = nw[y0:y1,x0:x1]
    z,x = np.mgrid[y0:y1,x0:x1]*16-16384
    doc = json.loads((input_dir/'landform.json').read_text(encoding='utf-8'))
    units = doc['units']
    from generate import neighbors
    # 海岸の分類と滑らかな移行に同じ距離場を使う。
    distance = np.zeros(mask.shape, np.float64); inland = mask.copy()
    for _ in range(48):
        distance += inland*16
        inland &= ~neighbors(~inland)
    low_north = smooth((-13312-z+field(x,z,700,8)*170)/420)
    wx=x+field(x,z,1100,401)*220
    wz=z+field(x,z,930,409)*200
    shallow=1-smooth(np.abs(field(wx,wz,570,419)+.38*field(wx,wz,230,421))/.24)
    foundation = 104+16*low_north + field(wx,wz,900,1)*13+field(wx,wz,310,2)*5-shallow*6
    shore_low = smooth((240-distance)/240)
    coastal=82+field(wx,wz,680,431)*6+field(wx,wz,250,433)*3-shallow*4
    foundation += (coastal-foundation)*shore_low
    height = foundation.copy()
    classification = np.where(z < -13312,92,91).astype(np.uint8)
    classification[distance < 240] = 90
    all_ridges = []
    all_valleys = []
    core_units = [u for u in units if u['id'] not in (90,91,92) and u['kind'] != 'detail']
    priorities = {'hills':1.5,'plateau':2.8,'basin':3.2,'massif':5,'range':16}
    for u in sorted(core_units,key=lambda u:priorities[u['kind']]):
        shape = polygon(u,mask.shape,int(x[0,0]),int(z[0,0]))
        raw = np.array(shape)>0
        weight = np.array(shape.filter(ImageFilter.GaussianBlur(8 if u['kind'] != 'range' else 5)),dtype=float)/255
        amp = {'hills':45,'plateau':25,'basin':9,'massif':(u['typical_y']-u['base_y'])*1.05,'range':45}[u['kind']]
        wx=x+field(x,z,623,u['id']+41)*190
        wz=z+field(x,z,457,u['id']+47)*170
        rugged = .55 + .65*field(wx,wz,410,u['id']) + .34*field(wx,wz,173,u['id']+15) + .17*field(wx,wz,71,u['id']+37)
        target = u['base_y'] + amp*rugged
        if u['id'] in (3,61,62,63):
            # 区分の包絡は保持し、実斜面の幅・高さと内部の起伏を変える。
            rim=.23*field(x,z,370,u['id']+151)+.12*field(x,z,147,u['id']+157)
            weight=np.clip(weight+rim*4*weight*(1-weight),0,1)
            broad=.55+.65*field(wx,wz,720,u['id'])+.32*field(wx,wz,290,u['id']+15)+.12*field(wx,wz,113,u['id']+37)
            amplitude={3:45,61:75,62:70,63:34}[u['id']]
            target=u['base_y']+amplitude*broad
            limit=u['peak_y']-12
            target=np.where(target>limit,limit+12*np.tanh((target-limit)/12),target)
        ridges, valleys = network(u)
        if ridges:
            rx=x+field(x,z,137,u['id']+101)*55+field(x,z,61,u['id']+103)*19
            rz=z+field(x,z,151,u['id']+107)*55+field(x,z,67,u['id']+109)*19
            relief = ridge_relief({'base_y':u['base_y'],'ridges':ridges,'valleys':[]},rx,rz,curved=True,sharp=True,section_power=2.6 if u['id'] in (1,22,24) else 1.65)
            # 山腹を異なる縮尺の侵食で刻む。稜線の支持幅と谷の間隔を周期化しない。
            relief += np.maximum(0,relief-u['base_y'])*(.15*field(x,z,211,u['id']+71)+.08*field(x,z,83,u['id']+79))
            # 起伏の面と稜線を滑らかな和でつなぐ。二つの斜面のmaxの切替えを作らない。
            from generate import join_relief
            joined = join_relief(target,relief,u['base_y'])
            target += (joined-target)*smooth((relief-u['base_y'])/24)
            from generate import curved_points
            valleys = [curved_points(v,u['id'],spacing=32) for v in valleys]
            target = incise(target,valleys,x,z)
            if u['id']==24:
                from generate import relax_relief
                target=relax_relief(target)
            all_ridges.extend({'unit_id':u['id'],'points':r} for r in ridges)
            all_valleys.extend({'unit_id':u['id'],'points':v} for v in valleys)
        if u['id'] in (3,61,62,63):
            # 谷の出口は周囲の低地へ抜ける。水面・河床の契約を配置しない。
            margin=height+(target-height)*smooth(weight/.78)
            valleys=dissected_margin(u,foundation,margin,x,z)
            from generate import relax_relief
            cut=incise(margin.copy(),valleys,x,z,strength=.88)
            height=margin-relax_relief(margin-cut)
            all_valleys.extend({'unit_id':u['id'],'points':v} for v in valleys)
        else:
            height += (target-height)*smooth(weight/.78)
        classification[raw] = u['id']
    coast_blend = smooth(distance/64)
    height = 63+(height-63)*coast_blend
    pins = [(u,p) for u in units if u['kind']!='detail' for p in u['pins']]
    # 稜線方向へ長い補正で共同の制御高を満たす。円形の保持パッドは置かない。
    kernels=[]
    for u,p in pins:
        px,pz = p['xz']; neighbors_p=[q for q in u['pins'] if q is not p]
        if neighbors_p:
            q=min(neighbors_p,key=lambda q:math.dist(q['xz'],p['xz']))
            dx,dz=np.array(q['xz'])-p['xz']; angle=math.atan2(dz,dx)
        else: angle=(u['id']*.71)%math.pi
        along=(x-px)*math.cos(angle)+(z-pz)*math.sin(angle)
        across=-(x-px)*math.sin(angle)+(z-pz)*math.cos(angle)
        kernel=np.exp(-.5*((along/260)**2+(across/110)**2))*coast_blend*mask
        if u['id'] in (1,22,24):
            kernel=np.exp(-.5*(along/230)**2-2*(np.sqrt((across/110)**2+.04)-.2))*coast_blend*mask
        kernels.append(kernel)
    matrix=np.array([[sample(k,*p['xz'],int(x[0,0]),int(z[0,0])) for k in kernels] for _,p in pins])
    residual=np.array([p['control_y']-sample(height,*p['xz'],int(x[0,0]),int(z[0,0])) for _,p in pins])
    corrections=np.linalg.solve(matrix,residual)
    for kernel,correction in zip(kernels,corrections): height+=kernel*correction
    height=np.where(height>500,500+20*np.tanh((height-500)/20),height)
    height=np.rint(height).astype(np.int16)
    classification[~mask]=0
    # 分類の包絡を先に確定し、細地形の面の上限の支持域を共有する。
    for u in units:
        if u['kind']=='detail':
            detail=np.array(polygon(u,mask.shape,int(x[0,0]),int(z[0,0])))>0
            classification[detail & mask]=u['id']
    from detail_relief import build_details
    hydro=json.loads((input_dir/'hydrology.json').read_text(encoding='utf-8'))
    print("DETAIL_RELIEF_START",flush=True)
    height,_=build_details(doc,hydro,height,x,z,mask,classification)
    print("DETAIL_RELIEF_COMPLETE",flush=True)
    return labels,(y0,y1,x0,x1),height,classification,all_ridges,all_valleys


def generate_assets(input_dir, output):
    from generate import ROOT, MAP_RESOURCES, LAYERS, RESOURCE_FILES, sha, write_json
    validate_contract(input_dir)
    config=json.loads((input_dir/'layout.json').read_text(encoding='utf-8'))
    background=ROOT/MAP_RESOURCES/'earth_001'
    labels,bounds,height,classification,ridges,valleys=build(input_dir)
    y0,y1,x0,x1=bounds; nw=labels==1
    output.mkdir(parents=True)
    layers={}
    for name in LAYERS:
        data=np.array(Image.open(background/(name+'.png')))
        if name=='height':
            tile=data[y0:y1,x0:x1]; tile[nw[y0:y1,x0:x1]]=height[nw[y0:y1,x0:x1]]+64
        else:
            data[nw]=255 if name=='region' else 1 if name=='coast_river' else 0
        layers[name]=data
        Image.fromarray(data).save(output/(name+'.png'))
    ids=np.zeros(labels.shape,np.uint8);ids[y0:y1,x0:x1]=classification
    Image.fromarray(ids).save(output/'landform.png')
    for name in ['landform.json','hydrology.json']:
        (output/name).write_bytes((input_dir/name).read_bytes())
    index=json.loads((background/'index.json').read_text(encoding='utf-8'))
    index['map_version']='earth_002'
    for key in ['facilities','routes','walkways','mountains','underground_surface_controls','underground_weathered_contacts','deposits']:
        index[key]=[]
    index['water_bodies']=index['water_bodies'][:1]
    index['terrain_basis']={'schema_version':1,'stage':'D1','runtime_relief_version':1,'background_map':'earth_001',
        'background_manifest_sha256':sha(background/'manifest.json'),'ridges':ridges,'valleys':valleys,'tile_bounds':list(map(int,bounds))}
    counts={str(uid):int(np.count_nonzero(classification==uid)) for uid in [u['id'] for u in json.loads((input_dir/'landform.json').read_text(encoding='utf-8'))['units']]}
    index['metrics']={'classification_pixels':counts,'northwest_land_pixels':int(nw.sum()),'stage':'D1; 細地形は予約分類、水系・雪氷・資源・施設は未制作'}
    from generate_hydrology import generate as generate_water, ASSETS as WATER_ASSETS
    index['hydrology_tiles']=generate_water(input_dir,output)
    for name in ['water','coast_river']:layers[name]=np.array(Image.open(output/(name+'.png')))
    hydrology=json.loads((input_dir/'hydrology.json').read_text(encoding='utf-8'))
    index['water_bodies'] += [{'id':r['id'],'kind':'river','inventory_id':r['inventory_id'],'hydrology_reach':r['id']} for r in hydrology['reaches']]
    index['water_bodies'] += [{'id':l['id'],'kind':'lake','inventory_id':l['inventory_id'],'surface_y':l['surface_y'],'hydrology_lake':l['id']} for l in hydrology['lakes']]
    from detail_relief import controls, dry_routes
    detail_doc=json.loads((input_dir/'landform.json').read_text(encoding='utf-8'))
    index['detail_controls']=[list(p) for p in controls(detail_doc)]
    index['detail_dry_routes']=dry_routes(detail_doc)
    index['metrics']['stage']='D3; 骨格・水系・細地形・静的雪氷。資源・施設は未制作'
    write_json(output/'index.json',index)
    geology=json.loads((background/'geology_profiles.json').read_text(encoding='utf-8'));geology['map_version']='earth_002'
    write_json(output/'geology_profiles.json',geology)
    write_json(output/'underground.json',{'map_version':'earth_002','format_version':1,'fixed_seed':520026,'salt':'earth_002_basis','deposits':[],'spaces':[]})
    base=json.loads((background/'manifest.json').read_text(encoding='utf-8'))
    names=(*RESOURCE_FILES,'landform.png','landform.json','hydrology.json',*WATER_ASSETS)
    base.update(map_version='earth_002',landform_schema_version=1,hydrology_schema_version=1,
                terrain_basis_schema_version=1,runtime_relief_version=1,stage='D3',
                background_manifest_sha256=sha(background/'manifest.json'),
                inputs={p.name:sha(p) for p in sorted(input_dir.iterdir()) if p.is_file()},
                files={name:sha(output/name) for name in names})
    base['tool']={'version':'2.0.0-D3','files':{f'tools/fixed-map/{name}':sha(Path(__file__).parent/name)
        for name in ['generate_basis.py','detail_relief.py','generate_hydrology.py','generate.py','layout_contract.py']}}
    sampler='modules/world/src/main/java/io/github/rs0325/ashenfrontier/world/TerrainBasis.java'
    base['tool']['files'][sampler]=sha(ROOT/sampler)
    for name in ['HydrologyTerrain.java','HydrologyNoise.java','FixedTerrain.java','TerrainDetails.java','FixedMapData.java']:
        path='modules/world/src/main/java/io/github/rs0325/ashenfrontier/world/'+name
        base['tool']['files'][path]=sha(ROOT/path)
    write_json(output/'manifest.json',base)
    print(json.dumps(index['metrics'],ensure_ascii=False),flush=True)
    return layers,index
