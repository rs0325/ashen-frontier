"""D4の残存量・母岩・連結形状・自然洞窟と再生成を検査する。"""
import json
import unittest
import tempfile
import copy
from collections import deque, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

from generate import ROOT, MAP_RESOURCES
from generate_geology import build, NEIGHBORS


def connected(points):
    if not points:return 0
    seen={next(iter(points))}
    queue=deque(seen)
    while queue:
        x,y,z=queue.popleft()
        for dx,dy,dz in NEIGHBORS:
            p=(x+dx,y+dy,z+dz)
            if p in points and p not in seen:
                seen.add(p);queue.append(p)
    return len(seen)


def ellipse_error(mask):
    """投影の境界を二次曲線へ当てはめ、旧楕円体の輪郭との違いをブロックで測る。"""
    points={(x,z) for x,_,z in mask}
    boundary=np.array([(x,z) for x,z in points if any((x+dx,z+dz) not in points for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)])],float)
    boundary-=boundary.mean(axis=0)
    x,z=boundary.T
    a=np.column_stack([x*x,x*z,z*z,x,z])
    c=np.linalg.lstsq(a,np.ones(len(a)),rcond=None)[0]
    gradient=np.hypot(2*c[0]*x+c[1]*z+c[3],c[1]*x+2*c[2]*z+c[4])
    return float(np.sqrt(np.mean(((a@c-1)/np.maximum(gradient,1e-8))**2)))


class GeologyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.input=ROOT/'tools/fixed-map/input/earth_002'
        cls.source=json.loads((cls.input/'geology.json').read_text(encoding='utf-8'))
        cls.doc=build(cls.input)
        cls.cells={}
        for column in cls.doc['d4']['columns']:
            for lo,hi,kind in np.array(column['runs']).reshape(-1,3):
                for y in range(int(lo),int(hi)+1):cls.cells[column['x'],y,column['z']]=int(kind)
        cls.masks={p['id']:{(c['x'],y,c['z']) for c in p['ore_columns'] for y in c['ys']} for p in cls.doc['deposits']}

    def test_registered_pairs_exact_residual_counts_and_d5_contract(self):
        palette=self.doc['d4']['palette']
        self.assertEqual(len(self.source['profiles']),11)
        self.assertEqual(len(self.doc['deposits']),40)
        self.assertEqual(sum(p['required'] for p in self.doc['deposits']),8)
        self.assertEqual(sum(p['residual_blocks'] for p in self.doc['deposits']),53611)
        pairs={p['block']:p for p in self.source['registered_pairs']}
        previous=set()
        for p,control,port in zip(self.doc['deposits'],self.source['deposits'],self.doc['d4']['handoffs']):
            mask=self.masks[p['id']]
            self.assertEqual(len(mask),control['target_blocks'],p['id'])
            self.assertFalse(previous&mask,p['id']);previous|=mask
            self.assertEqual([p['x'],p['z'],p['center_y']],[*control['xz'],control['center_y']])
            self.assertTrue(port['necessary_portals'])
            if not p['id'].startswith('oil_'):self.assertTrue(port['faces'])
            expected=p['block'] if ':' in p['block'] else 'ashen_frontier:'+p['block']
            host=p['host'] if ':' in p['host'] else 'ashen_frontier:'+p['host']
            if p['block'] in pairs:self.assertEqual(pairs[p['block']]['host'],host)
            for v in mask:
                self.assertEqual(palette[self.cells[v]],[expected,host],(p['id'],v))
            if p['id'].startswith('oil_'):
                self.assertEqual(len(mask),655)
                self.assertLess(max(v[1] for v in mask),0)
                self.assertTrue(port['oil_seal'])

    def test_bodies_are_connected_not_scattered_or_ellipsoidal(self):
        for p in self.doc['deposits']:
            mask=self.masks[p['id']]
            self.assertEqual(connected(mask),len(mask),p['id'])
        errors=[]
        for name in ['mining_iron','mining_copper','mining_coal','magnetite','aluminium','titanium']:
            error=ellipse_error(self.masks[name]);errors.append(error)
            print('D4_SHAPE',name,'ellipse_error_B',error,flush=True)
        self.assertGreater(min(errors),.8,'各型の代表輪郭を旧楕円体のままにしない')
        old={(x,y,z) for x in range(-18,19) for z in range(-12,13) for y in range(-5,6) if (x/18)**2+(y/5)**2+(z/12)**2<=1}
        self.assertLess(ellipse_error(old),.5,'同じ測定が旧楕円体を判別できる')

    def test_surfaces_exposure_budget_and_oil_caps(self):
        terrain=np.array(Image.open(self.input/'geology_surface.png')).astype(int)-128
        palette=self.doc['d4']['palette']
        for i,(p,control,port) in enumerate(zip(self.doc['deposits'],self.source['deposits'],self.doc['d4']['handoffs'])):
            x0,z0,_,_=control['sample_bounds'];mask=self.masks[p['id']]
            if control.get('exposure_budget_blocks',0):
                self.assertEqual(len(port['faces']),control['exposure_budget_blocks'])
                for x,y,z in port['faces']:
                    self.assertEqual(y,int(terrain[i*96+z-z0,x-x0])-2)
                    for yy in (y+1,y+2):self.assertEqual(palette[self.cells[x,yy,z]][0],'minecraft:air')
            if p['id'].startswith('oil_'):
                for column in p['ore_columns']:
                    x,z=column['x'],column['z'];top=max(column['ys'])
                    for y in range(top+3,top+15):
                        self.assertEqual(palette[self.cells[x,y,z]][0],'ashen_frontier:shale')

    def test_cave_roof_portal_and_air_component(self):
        palette=self.doc['d4']['palette'];cave=self.source['cave']
        x0,z0,_,_=cave['sample_bounds'];terrain=np.array(Image.open(self.input/'geology_cave_surface.png')).astype(int)-128
        xmin,zmin=x0,z0;xmax,zmax=cave['sample_bounds'][2:]
        air={v for v,k in self.cells.items() if palette[k][0]=='minecraft:air' and xmin<=v[0]<xmax and zmin<=v[2]<zmax}
        self.assertGreater(len(air),2000)
        self.assertEqual(connected(air),len(air),'自然洞を独立した穴の点列にしない')
        intervals=defaultdict(list)
        for xx,yy,zz in air:intervals[xx,zz].append(yy)
        self.assertLessEqual(max(max(v)-min(v)+1 for v in intervals.values()),7,'支洞の合成でも承認した高さ上限7Bを越えない')
        x,z=cave['entrance'];floor=cave['floor_y'][0]
        self.assertTrue(all((x,y,z) in air for y in (floor+1,floor+2)))
        self.assertNotIn((x,floor,z),air)
        # 最初64Bの開口遷移の外では、全空洞列の天井を実地表と照合する。
        cols={}
        for x,y,z in air:cols[x,z]=max(y,cols.get((x,z),-128))
        floors={}
        for xx,yy,zz in air:
            floors[xx,zz]=min(yy-1,floors.get((xx,zz),575))
        walk={point for point,floor in floors.items() if (point[0],floor+2,point[1]) in air}
        reached={tuple(cave['entrance'])};queue=deque(reached)
        while queue:
            xx,zz=queue.popleft()
            for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
                q=(xx+dx,zz+dz)
                if q in walk and q not in reached and abs(floors[q]-floors[xx,zz])<=1:
                    reached.add(q);queue.append(q)
        for point in self.doc['d4']['cave']['axes']:
            for xx,zz,floor,width,height in point:
                self.assertIn((xx,zz),reached,'主洞と支洞を入口から歩ける')
        a,b=np.array(cave['branches'][-1],float)
        direction=(b-a)/np.linalg.norm(b-a);normal=np.array([-direction[1],direction[0]])
        points=np.array(list(cols),float)-a
        station=points@direction;offset=points@normal
        bends=[]
        for distance in [15,20,25,30,35]:
            near=(abs(station-distance)<2)&(offset>-3)&(offset<12)
            if near.any():bends.append(float(np.mean(offset[near])))
        self.assertGreater(max(bends),2.5,'2制御点の支洞を49Bの直線坑へ戻さない')
        for (x,z),top in cols.items():
            if np.hypot(x-cave['entrance'][0],z-cave['entrance'][1])>70:
                self.assertGreaterEqual(int(terrain[z-z0,x-x0])-top,12,(x,z))

    def test_invalid_residual_type_and_host_pair_are_rejected(self):
        for field,value in [("target_blocks",True),("host","minecraft:stone")]:
            invalid=copy.deepcopy(self.source)
            invalid["deposits"][0][field]=value
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                (root/"geology.json").write_text(json.dumps(invalid),encoding="utf-8")
                with self.assertRaises(ValueError):build(root)

    def test_intervals_are_ordered_and_runtime_palette_is_bounded(self):
        palette=self.doc['d4']['palette']
        for c in self.doc['d4']['columns']:
            previous=-128
            self.assertEqual(len(c['runs'])%3,0)
            for lo,hi,p in np.array(c['runs']).reshape(-1,3):
                self.assertGreater(lo,previous);self.assertGreaterEqual(hi,lo)
                self.assertLessEqual(hi,575);self.assertLess(p,len(palette));previous=hi


if __name__=='__main__':unittest.main()
