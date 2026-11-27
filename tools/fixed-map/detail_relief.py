"""r20の細地形。16Bの基盤へ制作し、細部の起伏と雪材は列サンプラーで扱う。"""
import math
import numpy as np
from PIL import Image, ImageFilter


def nearest(points, x, z):
    distance = np.full(x.shape, np.inf)
    level = np.zeros(x.shape)
    width = np.zeros(x.shape)
    for a, b in zip(points, points[1:]):
        dx, dz = b[0]-a[0], b[1]-a[1]
        t = np.clip(((x-a[0])*dx+(z-a[1])*dz)/max(1, dx*dx+dz*dz), 0, 1)
        d = np.hypot(x-a[0]-t*dx, z-a[1]-t*dz)
        chosen = d < distance
        distance = np.minimum(distance, d)
        level = np.where(chosen, a[2]+t*(b[2]-a[2]), level)
        width = np.where(chosen, (a[3]+t*(b[3]-a[3])) if len(a)>3 else 20, width)
    return distance, level, width


def controls(doc):
    result = []
    for u in doc['units']:
        if u['kind']=='detail' and u['id']!=37:
            result += [(p['xz'][0], p['xz'][1], p['control_y'], u['id']) for p in u['pins']]
    result += [(v['xz'][0],v['xz'][1],v['rim_y'],22) for v in doc['volcanoes'] if v['id']!='V2']
    result += [(*p,40) for p in doc['caldera_rim']+doc['caldera_drain']]
    result += [(p[0],p[1],p[2],39) for p in doc['glacier']['points']]
    result += [(*p,39) for p in doc['glacier']['moraine']+doc['glacier']['outwash']]
    result += [(-6000,-10400,139,33),(-6400,-10100,140,33),(-7600,-5900,140,38)]
    return list({(p[0],p[1]):p for p in result}.values())


def dry_routes(doc):
    from generate import curved_points
    definitions=[([[-8000,-5600,145],[-7600,-5900,140],[-6900,-6700,133.5]],38,44),
                 ([[-10000,-10800,350]]+doc['caldera_drain'],40,48),
                 ([doc['glacier']['points'][-1][:3]]+doc['glacier']['outwash'],39,45)]
    result=[]
    for points,uid,span in definitions:
        curve=curved_points([p+[span] for p in points],uid,amplitude=11,spacing=24)
        begin=0
        for a,b in zip(points,points[1:]):
            end=next(i for i in range(begin+1,len(curve)) if curve[i][:2]==b[:2])
            xy=np.array(curve[begin:end+1])[:,:2]
            arc=np.r_[0,np.cumsum(np.hypot(np.diff(xy[:,0]),np.diff(xy[:,1])))]
            for i,distance in enumerate(arc):
                curve[begin+i][2]=float(a[2]+(b[2]-a[2])*distance/arc[-1])
            begin=end
        result.append({'unit_id':uid,'half_width':span,'controls':points,'points':curve})
    return result


def build_details(doc, hydrology, original, x, z, mask, classes):
    from generate_basis import field, smooth, polygon, sample
    from generate import ridge_relief, curved_points
    height = original.astype(float).copy()
    x0,z0=int(x[0,0]),int(z[0,0])
    extra = controls(doc)
    drains=dry_routes(doc)
    canyon=np.zeros(x.shape)
    rivers = {r['id']:r['profile_x_z_surface_full_width_depth'] for r in hydrology['reaches']}
    routes = {}
    for name in ['R05B','R05C','R15']:
        values=rivers[name]
        routes[name]=[values[i] for i in range(0,len(values),32)]+[values[-1]]
    # 火山は曲がる主稜と異なる位置から分かれる支稜で作り、回転体を使わない。
    for n,v in enumerate(doc['volcanoes']):
        if v['id']=='V2':continue
        cx,cz=v['xz'];w=v['width'];base=v['base_y'];peak=v['rim_y']
        angle=.48+n*.37
        c,s=math.cos(angle),math.sin(angle)
        def point(a,b,h,r):
            return [cx+a*c-b*s,cz+a*s+b*c,h,r]
        spine=[point(-w*.48,-w*.09,base,35),point(-w*.19,w*.035,peak-65,w*.19),
               point(0,0,peak,w*.21),point(w*.22,-w*.08,peak-95,w*.16),point(w*.46,w*.04,base,30)]
        branch=[point(-w*.17,w*.035,peak-80,w*.12),point(-w*.24,w*.19,peak-145,w*.11),point(-w*.13,w*.43,base,25)]
        another=[point(w*.14,-w*.04,peak-70,w*.14),point(w*.25,-w*.23,peak-170,w*.10),point(w*.33,-w*.37,base,20)]
        relief=ridge_relief({'base_y':base,'ridges':[spine,branch,another],'valleys':[]},x,z,curved=True,sharp=True,section_power=1.7)
        support=smooth((relief-base)/24)*mask
        target=relief+5*field(x,z,83,811+n)*support
        # 火山の付加も幅のある勾配でつなぎ、二つの面のmaxの折れを残さない。
        height+=np.maximum(0,target-height)*support
    for u in doc['units']:
        if u['kind']!='detail':continue
        print("DETAIL_RELIEF",u["id"],flush=True)
        uid=u['id']
        shape=polygon(u,x.shape,x0,z0)
        weight=np.array(shape.filter(ImageFilter.GaussianBlur(5)),float)/255
        # 高さの移行を区分の縁から外し、開析で幅と入り込みを変える。
        edge=field(x,z,211,uid+701)*.24+field(x,z,79,uid+709)*.09
        weight=smooth(np.clip(weight+edge*4*weight*(1-weight),0,1)/.86)
        wx=x+field(x,z,173,uid+101)*37
        wz=z+field(x,z,139,uid+103)*31
        a=field(wx,wz,211,uid+11);b=field(wx,wz,61,uid+17)
        if uid==37:canyon=weight.copy()
        if uid==31:
            transverse=(x+13650)+field(wx,wz,270,727)*110+field(wx,wz,93,733)*28
            target=66+19*smooth(transverse/125)+24*smooth((transverse-230)/135)+19*smooth((transverse-570)/160)
            target+=5*a+2*b
            target-=9*(1-smooth(np.abs(field(wx,wz,147,739))/.17))
        elif uid==32:
            target=200-(x+8500)*.032+(z+10400)*.019+9*a+5*b
            channel=1-smooth(np.abs(field(wx*.7+wz*.3,wz*.6-wx*.2,83,751))/.20)
            target-=12*channel
            target=200+29*np.tanh((target-200)/29)
            weight*=1-smooth((original-230)/75)
        elif uid==33:
            plane=np.linalg.solve(np.array([[1,-6600,-10500],[1,-6000,-10400],[1,-6400,-10100]],float),[152,139,140])
            target=plane[0]+plane[1]*x+plane[2]*z+1.8*a+.8*b
            ceiling=u["peak_y"]-2+a
            target=ceiling-2*np.logaddexp(0,(ceiling-target)/2)
            outside=np.full(x.shape,np.inf)
            for p,q in zip(u['boundary'],u['boundary'][1:]+u['boundary'][:1]):
                dx,dz=q[0]-p[0],q[1]-p[1]
                t=np.clip(((x-p[0])*dx+(z-p[1])*dz)/(dx*dx+dz*dz),0,1)
                outside=np.minimum(outside,np.hypot(x-p[0]-t*dx,z-p[1]-t*dz))
            weight=np.where(np.array(shape)>0,1,1-smooth((outside-48)/(112+32*a)))
            for toe in [[-6000,-10400,139],[-6400,-10100,140]]:
                axis=curved_points([[-6600,-10500,152,40],toe+[40]],33,amplitude=11,spacing=24)
                distance,_,_=nearest(axis,x,z)
                weight=np.maximum(weight,1-smooth((distance-44-12*a)/80))
        elif uid in (34,36):
            _,water,_=nearest(routes['R05B'] if uid==34 else routes['R05C'],x,z)
            target=water+(26+7*a+2*b if uid==34 else 3+1.7*a+.7*b)
            if uid==34:target=157-3*np.logaddexp(0,(157-target)/3)
        elif uid==35:
            target=70+3.6*a+1.3*b
            target-=2*(1-smooth(np.abs(field(wx,wz,86,761))/.18))
        elif uid==37:
            shoulder=310+40*np.tanh((original-260)/100)+12*a+6*b
            target=350-5*np.logaddexp(0,(350-shoulder)/5)
        elif uid==38:
            variation=5*a+2*b+(x+8000)*.004+(z+5600)*.003
            variation+=4*field(.35*x+.94*z,-.94*x+.35*z,37,uid+521)
            target=146+4*np.tanh(variation/4)
        elif uid==39:
            tail=next(r['points'] for r in drains if r['unit_id']==39)
            arc=np.r_[0,np.cumsum(np.hypot(np.diff(np.array(tail)[:,0]),np.diff(np.array(tail)[:,1])))]
            valley=doc['glacier']['points'][:-1]+[[p[0],p[1],p[2],70+70*t/arc[-1]] for p,t in zip(tail,arc)]
            dist,ice,width=nearest(valley,x,z)
            last,previous=valley[-1],valley[-2]
            dx,dz=last[0]-previous[0],last[1]-previous[1]
            length=math.hypot(dx,dz)
            past=np.maximum(0,((x-last[0])*dx+(z-last[1])*dz)/length)
            dist=np.sqrt(np.maximum(0,dist*dist-past*past))
            ice-=(previous[2]-last[2])*past/length
            # 氷河末端を閉じた円形の土手にせず、既定の乾いた砂礫原へ開く。
            wall=np.maximum(0,dist-width*.35)
            rise=.48*wall+.003*wall*wall
            ceiling=u['peak_y']-ice
            headroom=ceiling*np.tanh(np.maximum(height-ice,32+10*a+6*b)/ceiling)
            profile=ice+headroom*np.tanh(rise/headroom)
            gully=1-smooth(np.abs(field(wx*.7+wz*.3,wz*.6-wx*.2,43,uid+959))/.3)
            profile-=np.minimum(8,np.maximum(0,profile-ice-6)*.5)*gully*smooth((dist-width*.5-20)/32)
            support=(1-smooth((dist-width*.5-90)/150))*(1-smooth(past/160))
            target=height+(profile-height)*support
        else:
            rim=[p+[75] for p in doc['caldera_rim']]
            rim=curved_points(rim,40,spacing=24,closed=True)
            dist,rim_y,_=nearest(rim+[rim[0]],x,z)
            band=np.exp(-(dist/83)**2)
            floor=350+3*a+1.2*b
            crest=rim_y-2*np.abs(a)-np.abs(b)
            target=floor+(crest-floor)*band
            weight=np.maximum(weight,band)
        height+=(target-height)*weight*mask
    drain_profiles=[]
    for route in drains:
        uid,span=route['unit_id'],route['half_width']
        dist,y,_=nearest(route['points'],x,z)
        cross=dist*(1+.5*field(x,z,37,uid+919)) if uid==38 else dist
        profile=y+.045*cross+.0011*cross*cross+field(x,z,93,uid+901)*1.8*smooth(dist/16)
        if uid==38:
            profile+=3*(1+field(.7*x-.7*z,.7*x+.7*z,37,uid+929))*smooth(dist/12)
        weight=(1-smooth(dist/(span+20*field(x,z,167,uid+907))))*mask
        drain_profiles.append((profile,weight))
        height-=np.maximum(0,height-profile)*weight
    limits=[]
    for uid in [31,34,35,36,38]:
        unit=next(u for u in doc['units'] if u['id']==uid)
        region=Image.fromarray(((classes==uid)*255).astype(np.uint8)).filter(ImageFilter.MaxFilter(3))
        full=np.array(region)>0
        fade=np.array(region.filter(ImageFilter.GaussianBlur(2.5))).astype(float)/255
        support=np.where(full,1,fade)
        ceiling=unit['peak_y']-3+3*field(wx,wz,71,uid+947)
        limits.append((unit['peak_y'],ceiling,full,support))
    def limit_faces(values, exterior):
        for peak,ceiling,full,support in limits:
            target=ceiling-2*np.logaddexp(0,(ceiling-values)/2)
            blend=smooth((values-(peak-8))/4)
            values+=(target-values)*blend*(support if exterior else full)*mask
        return values
    height=limit_faces(height,True)
    print("DETAIL_CONTROLS",flush=True)
    # 旧骨格の制御高は基準面のまま、新制御高は同じ補間で共同に解く。
    pins=[]
    for u in doc['units']:
        if u['kind']!='detail':
            pins += [(p['xz'][0],p['xz'][1],float(sample(original,*p['xz'],x0,z0)),u['id']) for p in u['pins']]
    pins+=extra
    deposits={(p[0],p[1]) for p in doc["glacier"]["moraine"]+doc["glacier"]["outwash"]}
    kernels=[]
    for px,pz,_,uid in pins:
        angle=-1.05 if uid==39 else -.65 if uid==38 else .35 if uid==33 else uid*.71
        c,s=math.cos(angle),math.sin(angle)
        along=((x-px)*c+(z-pz)*s)/(32 if (px,pz) in deposits else 105)
        across=(-(x-px)*s+(z-pz)*c)/(20 if (px,pz) in deposits else 64)
        radius=np.hypot(along,across)
        kernels.append(np.exp(-.5*radius*radius)*(1-smooth((radius-1.5)/1.5))*mask)
    matrix=np.array([[sample(k,px,pz,x0,z0) for k in kernels] for px,pz,_,_ in pins])
    residual=np.array([h-sample(height,px,pz,x0,z0) for px,pz,h,_ in pins])
    print("DETAIL_MAX_CONTROL_CORRECTION",float(np.max(np.abs(residual))),flush=True)
    for k,c in zip(kernels,np.linalg.solve(matrix,residual)):height+=k*c
    height=np.clip(height,63,520)
    for _ in range(12):
        residual=np.array([h-sample(height,px,pz,x0,z0) for px,pz,h,_ in pins])
        if np.max(np.abs(residual))<.25:break
        correction=np.linalg.solve(matrix,residual)
        for k,c in zip(kernels,correction):height+=k*c
        height=np.clip(height,63,520)
    # 峰の補正後にも、同じ排水断面で経路を開く。
    for profile,weight in drain_profiles:
        height-=np.maximum(0,height-profile)*weight
    # 広い峰の補正で排水口を塞がない。排水路の点だけ、小さい支持域で高さを戻す。
    drain_controls={(q[0],q[1]) for q in extra}
    drain_pins=list({(p[0],p[1]):tuple(p[:3]) for route in drains for p in route["controls"] if (p[0],p[1]) in drain_controls}.values())
    del kernels,matrix
    kernels=[]
    for px,pz,_ in drain_pins:
        radius=np.hypot((x-px)/24,(z-pz)/16)
        kernels.append(np.exp(-.5*radius*radius)*(1-smooth(radius-1))*mask)
    matrix=np.array([[sample(k,px,pz,x0,z0) for k in kernels] for px,pz,_ in drain_pins])
    residual=np.array([h-sample(height,px,pz,x0,z0) for px,pz,h in drain_pins])
    for k,c in zip(kernels,np.linalg.solve(matrix,residual)):height+=k*c
    # R15の細谷は既設の水列の支持地盤を残す。氷河・他流域は横断しない。
    points=np.array(rivers['R15'],float)
    ix=np.floor((points[:,0]-x0)/16).astype(int);iz=np.floor((points[:,1]-z0)/16).astype(int)
    retained=np.zeros(height.shape)
    for dz in range(-6,7):
        for dx in range(-6,7):
            xx=ix+dx;zz=iz+dz
            valid=(xx>=0)&(xx<height.shape[1])&(zz>=0)&(zz<height.shape[0])
            px,pz=xx[valid],zz[valid];p=points[valid]
            distance=np.hypot(x0+px*16-p[:,0],z0+pz*16-p[:,1])-p[:,3]*.5
            weight=1-smooth((distance-12)/48)
            floor=p[:,2]+14
            change=np.maximum(0,floor-height[pz,px])*weight
            np.maximum.at(retained,(pz,px),change)
    height+=retained*mask*smooth(canyon*4)
    height=limit_faces(height,False)
    return np.rint(height).astype(np.int16),extra
