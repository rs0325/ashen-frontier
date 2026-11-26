"""D2の水面・床・所有列を1Bタイルへ固定する。worldの残水量は制作しない。"""
import json
import math
import numpy as np
from PIL import Image, ImageDraw
from generate_basis import smooth, field

TILE = 128
ASSETS = ('hydrology_owner.png', 'hydrology_surface.png', 'hydrology_bed.png')


def build(document):
    reaches = sorted(document['reaches'], key=lambda r:r['id'])
    names = [r['id'] for r in reaches] + [l['id'] for l in document['lakes']]
    inventory = [r['inventory_id'] for r in reaches] + [l['inventory_id'] for l in document['lakes']]
    tiles = {}
    def tile(tx,tz):
        key=(tx,tz)
        if key not in tiles:
            tiles[key]=[np.zeros((TILE,TILE),np.uint8), np.zeros((TILE,TILE),np.uint16), np.zeros((TILE,TILE),np.uint16)]
        return tiles[key]
    for owner,reach in enumerate(reaches,1):
        best={}
        points=np.array(reach['profile_x_z_surface_full_width_depth'],float)
        for a,b in zip(points,points[1:]):
            radius=max(a[3],b[3])/2+1
            xmin=math.floor(min(a[0],b[0])-radius);xmax=math.ceil(max(a[0],b[0])+radius)
            zmin=math.floor(min(a[1],b[1])-radius);zmax=math.ceil(max(a[1],b[1])+radius)
            dx,dz=b[:2]-a[:2];length=dx*dx+dz*dz
            for tx in range(xmin//TILE,xmax//TILE+1):
                for tz in range(zmin//TILE,zmax//TILE+1):
                    x0=max(xmin,tx*TILE);x1=min(xmax+1,(tx+1)*TILE);z0=max(zmin,tz*TILE);z1=min(zmax+1,(tz+1)*TILE)
                    x,z=np.meshgrid(np.arange(x0,x1),np.arange(z0,z1));t=np.clip(((x-a[0])*dx+(z-a[1])*dz)/length,0,1)
                    distance=np.hypot(x-a[0]-t*dx,z-a[1]-t*dz);width=(a[3]+(b[3]-a[3])*t)/2
                    key=(tx,tz)
                    if key not in best:best[key]=np.full((TILE,TILE),np.inf)
                    slices=(slice(z0-tz*TILE,z1-tz*TILE),slice(x0-tx*TILE,x1-tx*TILE))
                    nearest=best[key][slices];owner_tile,water_tile,bed_tile=tile(tx,tz)
                    own=owner_tile[slices];use=(distance<width)&(distance<nearest)&((own==0)|(own==owner))
                    if not use.any():continue
                    level=np.floor(a[2]+(b[2]-a[2])*t+1e-7).astype(int)
                    centre=a[4]+(b[4]-a[4])*t
                    depth=np.ceil(1+np.maximum(0,centre-1)*np.maximum(0,1-(distance/width)**2)-1e-7).astype(int)
                    own[use]=owner;water_tile[slices][use]=level[use]+64;bed_tile[slices][use]=level[use]-depth[use]+64;nearest[use]=distance[use]
        for x,z,level,width,depth in points:
            x,z=int(x),int(z);ids,levels,beds=tile(x//TILE,z//TILE);at=(z%TILE,x%TILE)
            if ids[at] in (0,owner):
                ids[at]=owner;levels[at]=int(level)+64;beds[at]=int(level)-math.ceil(depth-1e-7)+64
        print('HYDROLOGY_RASTER',reach['id'],flush=True)
    for offset,lake in enumerate(document['lakes'],len(reaches)+1):
        polygon=lake['outline_x_z'];xs,zs=zip(*polygon);x0=min(xs);z0=min(zs);x1=max(xs);z1=max(zs)
        x,z=np.meshgrid(np.arange(x0,x1+1),np.arange(z0,z1+1));mask=Image.new('L',x.shape[::-1]);ImageDraw.Draw(mask).polygon([(px-x0,pz-z0) for px,pz in polygon],fill=1);wet=np.array(mask)>0
        distance=np.full(x.shape,np.inf)
        for aa,bb in zip(polygon,polygon[1:]+polygon[:1]):
            a=np.array(aa);d=np.array(bb)-a;t=np.clip(((x-a[0])*d[0]+(z-a[1])*d[1])/(d@d),0,1);distance=np.minimum(distance,np.hypot(x-a[0]-t*d[0],z-a[1]-t*d[1]))
        shore_width=180+40*field(x,z,128,99146)+20*field(x,z,64,99147)
        potential=(1-np.exp(-distance/shore_width))*(.85+.15*field(x,z,160,99148))
        depth=1+24*potential/max(potential[wet].max(),1e-9)
        node=next(n for n in document['nodes'] if n['id']==lake['outlet_node']);ox,oz=node['xz']
        depth=np.minimum(depth,2+np.hypot(x-ox,z-oz)*.15);depth=np.ceil(np.clip(depth,1,25)-1e-7).astype(int)
        bed=lake['surface_y']-depth
        for tx in range(x0//TILE,x1//TILE+1):
            for tz in range(z0//TILE,z1//TILE+1):
                ax=max(x0,tx*TILE);bx=min(x1+1,(tx+1)*TILE);az=max(z0,tz*TILE);bz=min(z1+1,(tz+1)*TILE)
                src=(slice(az-z0,bz-z0),slice(ax-x0,bx-x0));dst=(slice(az-tz*TILE,bz-tz*TILE),slice(ax-tx*TILE,bx-tx*TILE));use=wet[src]
                ids,levels,beds=tile(tx,tz);ids[dst][use]=offset;levels[dst][use]=lake['surface_y']+64;beds[dst][use]=bed[src][use]+64
    tiles={key:value for key,value in tiles.items() if value[0].any()}
    keys=sorted(tiles);columns=16;rows=math.ceil(len(keys)/columns)
    atlases=[np.zeros((rows*TILE,columns*TILE),dtype) for dtype in (np.uint8,np.uint16,np.uint16)]
    for i,key in enumerate(keys):
        dest=(slice(i//columns*TILE,(i//columns+1)*TILE),slice(i%columns*TILE,(i%columns+1)*TILE))
        for image,values in zip(atlases,tiles[key]):image[dest]=values
    return atlases,{'schema_version':1,'tile_size':TILE,'atlas_columns':columns,'tiles':[list(k) for k in keys],
        'owners':names,'inventory_ids':inventory,'finite_volume':'海域を除き、所有列のsurface-bedをロード時に一回だけ集計するblock^3。#55の残量ではない。'}


def generate(input_dir,output):
    document=json.loads((input_dir/'hydrology.json').read_text(encoding='utf-8'))
    images,index=build(document)
    for name,values in zip(ASSETS,images):Image.fromarray(values).save(output/name)
    # 16Bの参照レイヤーも同じ所有列から更新する。実際の判定は1B資産を使う。
    land=np.array(Image.open(input_dir.parent/'land.png'))==1
    water=np.array(Image.open(output/'water.png'));coast=np.array(Image.open(output/'coast_river.png'))
    for i,(tx,tz) in enumerate(index['tiles']):
        ay=i//index['atlas_columns']*TILE;ax=i%index['atlas_columns']*TILE
        for z in range(0,TILE,16):
            for x in range(0,TILE,16):
                px=(tx*TILE+x+16384)//16;pz=(tz*TILE+z+16384)//16;owner=int(images[0][ay+z,ax+x])
                if owner and land[pz,px]:water[pz,px]=4;coast[pz,px]=1|(4 if owner==len(index['owners']) else 2)
    Image.fromarray(water).save(output/'water.png');Image.fromarray(coast).save(output/'coast_river.png')
    return index


def validate_assets(output):
    """制作済みの所有符号・型・水柱を検査し、欠落や逆転した床を拒否する。"""
    definition=json.loads((output/'index.json').read_text(encoding='utf-8'))['hydrology_tiles']
    images=[]
    for name,mode in zip(ASSETS,['L','I;16','I;16']):
        with Image.open(output/name) as image:
            if image.mode!=mode:raise ValueError('水列画像の型: '+name)
            images.append(np.array(image))
    owner,water,bed=images
    if owner.shape!=water.shape or owner.shape!=bed.shape or owner.shape[1]!=definition['atlas_columns']*128:
        raise ValueError('水列画像の寸法')
    if owner.max()>len(definition['owners']) or len({tuple(v) for v in definition['tiles']})!=len(definition['tiles']):
        raise ValueError('水列の所有者またはタイル重複')
    wet=owner>0
    if np.any(bed[wet]>=water[wet]) or np.any(water[wet]<127) or np.any(water[wet]>584):
        raise ValueError('水列の水面と床')
    return definition,images
