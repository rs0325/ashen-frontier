"""D3の制作高・排水口と、広域の平らな固定床の回帰。"""
import json
import heapq
import math
import unittest
import numpy as np
from PIL import Image
from generate import ROOT, MAP_RESOURCES
from generate_basis import sample
from detail_relief import controls

def drainage_spill(height, points):
    p=np.array(points,float);step=8
    x0=int(p[0,0]-math.ceil((p[0,0]-p[:,0].min()+112)/step)*step)
    z0=int(p[0,1]-math.ceil((p[0,1]-p[:,1].min()+112)/step)*step)
    xs=np.arange(x0,p[:,0].max()+113,step);zs=np.arange(z0,p[:,1].max()+113,step)
    xx,zz=np.meshgrid(xs,zs);distance=np.full(xx.shape,np.inf)
    for a,b in zip(p,p[1:]):
        dx,dz=b[0]-a[0],b[1]-a[1]
        t=np.clip(((xx-a[0])*dx+(zz-a[1])*dz)/(dx*dx+dz*dz),0,1)
        distance=np.minimum(distance,np.hypot(xx-a[0]-t*dx,zz-a[1]-t*dz))
    levels=np.array([sample(height,x,z,-16384,-16384) for x,z in zip(xx.ravel(),zz.ravel())]).reshape(xx.shape)
    start=(round((p[0,1]-z0)/step),round((p[0,0]-x0)/step))
    goal=(round((p[-1,1]-z0)/step),round((p[-1,0]-x0)/step))
    best=np.full(xx.shape,np.inf);best[start]=levels[start]
    queue=[(levels[start],*start)]
    while queue:
        cost,z,x=heapq.heappop(queue)
        if cost!=best[z,x]:continue
        if (z,x)==goal:return cost
        for dz,dx in ((a,b) for a in (-1,0,1) for b in (-1,0,1) if a or b):
            nx,nz=x+dx,z+dz
            if nx<0 or nz<0 or nx>=len(xs) or nz>=len(zs) or distance[nz,nx]>96:continue
            value=max(cost,levels[nz,nx])
            if value<best[nz,nx]:
                best[nz,nx]=value;heapq.heappush(queue,(value,nz,nx))
    return float('inf')


class DetailTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root=ROOT/MAP_RESOURCES/'earth_002'
        cls.land=json.loads((root/'landform.json').read_text(encoding='utf-8'))
        cls.height=np.array(Image.open(root/'height.png')).astype(float)-64

    def test_controls_and_open_drain_elevations(self):
        for x,z,y,uid in controls(self.land):
            self.assertLessEqual(abs(sample(self.height,x,z,-16384,-16384)-y),1,(uid,x,z,y))
        ids=np.array(Image.open(ROOT/MAP_RESOURCES/'earth_002'/'landform.png'))
        for unit in [31,33,34,35,36,38,40]:
            limit=next(u['peak_y'] for u in self.land['units'] if u['id']==unit)
            self.assertLessEqual(self.height[ids==unit].max(),limit,('制作面の局所上限',unit))
        for key,points in [('caldera',[[ -10000,-10800,350]]+self.land['caldera_drain']),
                           ('outwash',[self.land['glacier']['points'][-1][:3]]+self.land['glacier']['outwash']),
                           ('basin',[[-8000,-5600,145],[-7600,-5900,140]])]:
            heights=[sample(self.height,x,z,-16384,-16384) for x,z,*_ in points]
            self.assertTrue(all(a>b for a,b in zip(heights,heights[1:])),(key,heights))

    def test_detail_interiors_are_not_constant_floors(self):
        # 凹地・堆積面にも浅い開析が残り、同一標高だけの固定床にしない。
        for x,z in [(-8000,-5600),(-10000,-10800),(-8500,-10400),(-5100,-9000)]:
            xs,zs=np.meshgrid(np.arange(x-96,x+97,16),np.arange(z-96,z+97,16))
            ys=np.array([sample(self.height,int(px),int(pz),-16384,-16384) for px,pz in zip(xs.ravel(),zs.ravel())])
            self.assertGreater(np.ptp(ys),3,(x,z))
            self.assertGreater(len(np.unique(np.rint(ys))),4,(x,z))

    def test_glacial_shoulders_are_not_high_constant_floors(self):
        from collections import deque
        root=ROOT/MAP_RESOURCES/'earth_002'
        ids=np.array(Image.open(root/'landform.png'))
        selected=(ids==39)&(self.height>=420)
        pending=set(map(tuple,np.argwhere(selected)))
        maximum=0
        while pending:
            origin=pending.pop();todo=deque([origin]);count=0
            level=self.height[origin]
            while todo:
                z,x=todo.popleft();count+=1
                for point in [(z-1,x),(z+1,x),(z,x-1),(z,x+1)]:
                    if point in pending and self.height[point]==level:
                        pending.remove(point);todo.append(point)
            maximum=max(maximum,count)
        self.assertLessEqual(maximum,16,"G01の外側を固定した高い平面にしない")

    def test_basin_caldera_and_glacier_outwash_are_not_closed(self):
        routes=[
            ('38',[[-8000,-5600,145],[-7600,-5900,140],[-6900,-6700,133.5]]),
            ('40',[[-10000,-10800,350]]+self.land['caldera_drain']),
            ('G01',[self.land['glacier']['points'][-1][:3]]+self.land['glacier']['outwash'])]
        for name,points in routes:
            spill=drainage_spill(self.height,points)
            self.assertLessEqual(spill,points[0][2]+1,(name,spill,'指定した出口までの越流高'))

if __name__=='__main__':
    unittest.main()
