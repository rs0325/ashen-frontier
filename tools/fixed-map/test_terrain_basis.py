"""D1の制作入力・分類・実標高と、過去に問題となった輪郭の回帰。"""

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

from generate import ROOT, MAP_RESOURCES, curved_points, generate, sha
from generate_basis import polygon, sample
from test_fixed_map import contour_lines, longest_circular_arc


def longest_straight(chains):
    longest = 0
    for chain in chains:
        distance = np.r_[0,np.cumsum(np.linalg.norm(np.diff(chain,axis=0),axis=1))]
        points = np.column_stack([np.interp(np.arange(0,distance[-1],32),distance,chain[:,k]) for k in (0,1)])
        for start in range(0,len(points)-24,4):
            p=points[start:start+25]; chord=p[-1]-p[0];length=np.linalg.norm(chord)
            if length<640:continue
            deviation=np.max(np.abs(chord[0]*(p[:,1]-p[0,1])-chord[1]*(p[:,0]-p[0,0])))/length
            if deviation<8:longest=max(longest,length)
    return longest


def circular_toe(levels):
    """一つの谷の曲がりではなく、複数の高さで同心円になる丸い末端を検出する。"""
    arcs=[]
    for level,chains in levels:
        for chain in chains:
            length,detail=longest_circular_arc([chain],minimum_angle=2)
            if length>=320:arcs.append((level,detail['center']))
    return max((len({level for level,center in arcs if np.linalg.norm(np.array(center)-origin)<80})
                for _,origin in arcs),default=0)


class TerrainBasisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=ROOT/MAP_RESOURCES/'earth_002'
        cls.land=json.loads((cls.root/'landform.json').read_text(encoding='utf-8'))
        cls.index=json.loads((cls.root/'index.json').read_text(encoding='utf-8'))
        cls.height=np.array(Image.open(cls.root/'height.png')).astype(float)-64
        cls.ids=np.array(Image.open(cls.root/'landform.png'))

    def test_classification_covers_only_the_approved_continent_and_preserves_controls(self):
        coast=np.array(Image.open(ROOT/'tools/fixed-map/input/land.png'))
        np.testing.assert_array_equal(self.ids!=0,coast==1)
        self.assertEqual(set(np.unique(self.ids))-{0},{u['id'] for u in self.land['units']})
        fixture=json.loads((ROOT/'tools/fixed-map/fixtures/earth_002/landform.json').read_text(encoding='utf-8'))
        self.assertEqual(self.land,fixture,'確定した外形・座標・高さ・幅を動かさない')
        for u in self.land['units']:
            if u['kind']=='detail':continue
            for p in u['pins']:
                self.assertLessEqual(abs(sample(self.height,*p['xz'],-16384,-16384)-p['control_y']),1,p['id'])
        self.assertGreaterEqual(self.height[coast==1].min(),63)
        self.assertLessEqual(self.height[coast==1].max(),520)
        for name in ['height','region','water','soil','geology','coast_river']:
            actual=np.array(Image.open(self.root/(name+'.png')))
            old=np.array(Image.open(self.root.parent/'earth_001'/(name+'.png')))
            np.testing.assert_array_equal(actual[coast!=1],old[coast!=1],err_msg=name)

    def test_branches_and_dry_valleys_are_not_regular_radial_fans(self):
        definition=self.index['terrain_basis']
        for uid in [1,2,21,22,23,24]:
            ridges=[np.array(r['points']) for r in definition['ridges'] if r['unit_id']==uid]
            valleys=[r for r in definition['valleys'] if r['unit_id']==uid]
            self.assertGreater(len(ridges),8,uid)
            self.assertGreater(len(valleys),3,uid)
            roots=[tuple(r[0,:2]) for r in ridges]
            self.assertLessEqual(max(roots.count(p) for p in roots),3,uid)
            lengths=[np.linalg.norm(np.diff(r[:,:2],axis=0),axis=1).sum() for r in ridges]
            self.assertGreater(np.std(lengths),100,uid)
            controls={tuple(p['xz']) for u in self.land['units'] if u['id']==uid for p in u['pins']}
            axes=[r for r in ridges if len(r)!=4 or (tuple(r[0,:2]) in controls and tuple(r[-1,:2]) in controls)]
            parents=[np.array(curved_points(r.tolist(),0,spacing=24)) for r in axes]
            stations=[[] for parent in parents]
            for branch in (r for r in ridges if not any(r is axis for axis in axes)):
                root=branch[0];closest=(float('inf'),0,0,0)
                for parent_id,parent in enumerate(parents):
                    a,b=parent[:-1],parent[1:];v=b[:,:2]-a[:,:2]
                    t=np.clip(((root[:2]-a[:,:2])*v).sum(1)/(v*v).sum(1),0,1)
                    distances=np.linalg.norm(a[:,:2]+t[:,None]*v-root[:2],axis=1);i=np.argmin(distances)
                    arc=np.r_[0,np.cumsum(np.linalg.norm(v,axis=1))]
                    closest=min(closest,(distances[i],abs(root[2]-(a[i,2]+t[i]*(b[i,2]-a[i,2]))),parent_id,
                                         arc[i]+t[i]*np.linalg.norm(v[i])))
                self.assertLess(closest[0],1e-5,'支稜の根元が曲がった本稜線から離れない')
                self.assertLess(closest[1],1e-5,'支稜が本稜線より高い孤立した刃にならない')
                stations[closest[2]].append(closest[3])
            if uid==24:
                gaps=np.concatenate([np.diff(sorted(values)) for values in stations if len(values)>1])
                knots=[r for r in axes if tuple(r[0,:2]) in controls and tuple(r[-1,:2]) in controls]
                intervals=np.concatenate([np.linalg.norm(np.diff(r[:,:2],axis=0),axis=1)/np.linalg.norm(r[-1,:2]-r[0,:2]) for r in knots])
                print('BASIS_SPACING',uid,'branch_cv',gaps.std()/gaps.mean(),'crest_cv',intervals.std()/intervals.mean(),flush=True)
                self.assertGreater(len(gaps),4,'支稜の間隔を比較できる標本数')
                self.assertGreater(gaps.std()/gaps.mean(),.45,'支稜をほぼ等間隔に並べない')
                self.assertGreater(intervals.std()/intervals.mean(),.22,'副峰・鞍部を同じ比率で繰り返さない')


    def test_contours_have_neither_long_cuts_nor_repeated_round_toes(self):
        for bounds in [(-11000,-13500,-9000,-11500),(-13400,-12300,-11400,-10300),
                       (-6100,-13000,-4100,-11000),(-9700,-6700,-7700,-4700),
                       (-13000,-7200,-11000,-5200),(-9300,-4800,-7300,-2800),
                       (-8000,-11000,-6000,-9000)]:
            x0,z0,x1,z1=bounds
            a=self.height[(z0+16384)//16:(z1+16384)//16,(x0+16384)//16:(x1+16384)//16]
            levels=[(level,contour_lines(a-level,x0,z0)) for level in [120,140,180,220,260,320]]
            chains=[chain for _,values in levels for chain in values]
            rounds=circular_toe(levels);straight=longest_straight(chains)
            print('BASIS_SHAPE',bounds,'concentric_levels',rounds,'straight',straight,flush=True)
            self.assertLess(rounds,3,bounds)
            self.assertEqual(straight,0,bounds)
        # 同じ検査が、旧来の半円末端・四角い固定床・斜めの切断面を検出する。
        t=np.linspace(0,np.pi,100)
        self.assertGreaterEqual(longest_circular_arc([np.column_stack([180*np.cos(t),180*np.sin(t)])],minimum_angle=2)[0],320)
        self.assertEqual(circular_toe([(level,[np.column_stack([r*np.cos(t),r*np.sin(t)])])
                                      for level,r in [(140,140),(180,165),(220,180)]]),3)
        for edge in [np.column_stack([np.arange(0,1100,16),np.zeros(69)]),
                     np.column_stack([np.arange(0,1100,16),np.arange(0,1100,16)*.6])]:
            self.assertGreater(longest_straight([edge]),640)

    def test_mountains_and_plateaus_have_no_rectangular_fixed_floor(self):
        views=np.lib.stride_tricks.sliding_window_view(self.height,(8,8))[::4,::4]
        delta=views.max(axis=(-2,-1))-views.min(axis=(-2,-1))
        classes=self.ids[4:4+delta.shape[0]*4:4,4:4+delta.shape[1]*4:4]
        mountain=np.isin(classes,[1,2,21,22,23,24,3,63])
        self.assertGreater(mountain.sum(),10000)
        self.assertEqual(np.count_nonzero(mountain & (delta==0)),0,'128B四方の固定床を山地・台地へ埋め込まない')

    def test_plateaus_and_hills_are_dissected_and_lowlands_roll(self):
        # 外形の移行帯を除いた面内の起伏と、外形内側の縁の高さを別々に評価する。
        limits={3:(18,28),61:(22,20),62:(20,22),63:(9,14),91:(5,0),92:(5,0)}
        for u in self.land['units']:
            if u['id'] not in limits:continue
            raw=polygon(u,self.ids.shape,-16384,-16384) if u['boundary'] else Image.fromarray((self.ids==u['id']).astype('uint8')*255)
            weight=np.array(raw.filter(ImageFilter.GaussianBlur(8)))/255
            core=(weight>.97)&(self.ids==u['id'])
            rim=(weight>.4)&(weight<.75)&(self.ids==u['id'])
            core_sd=float(self.height[core].std());rim_sd=float(self.height[rim].std())
            print('BASIS_DISSECTION',u['id'],'core_sd',core_sd,'rim_sd',rim_sd,flush=True)
            minimum,rim_minimum=limits[u['id']]
            self.assertGreater(core_sd,minimum,'面内の起伏が不足: '+str(u['id']))
            if rim_minimum:self.assertGreater(rim_sd,rim_minimum,'同じ高・幅の楕円状の縁: '+str(u['id']))
            if u['kind']=='lowland':
                both=core[:,16:]&core[:,:-16]
                difference=np.abs(self.height[:,16:]-self.height[:,:-16])[both]
                self.assertGreater(np.percentile(difference,75),4,'256B規模の緩いうねりを保つ')
                self.assertLess(np.percentile(difference,90),18,'低地の起伏を山地や段差にしない')
        outline=[]
        for x in range(-8000,-4999,32):
            zs=np.arange(-10800,-7600,16)
            high=(self.height[(zs+16384)//16,(x+16384)//16]>=185)&(self.ids[(zs+16384)//16,(x+16384)//16]==3)
            outline.append(float(zs[high][-1]) if high.any() else np.nan)
        edge=np.array(outline)
        dents=(edge[:-32]+edge[32:])/2-edge[16:-16]
        run=longest=0
        for dent in dents:
            run=run+1 if dent>96 else 0;longest=max(longest,run)
        self.assertGreaterEqual(longest*32,256,'高原の185等高線に、幅256B以上の谷の入り込みを保つ')

    def test_large_crests_have_sloping_shoulders_and_directional_valleys(self):
        for x,z,minimum in [(-9900,-11400,60),(-8600,-6200,80)]:
            peak=sample(self.height,x,z,-16384,-16384)
            rim=np.array([sample(self.height,x+128*np.cos(t),z+128*np.sin(t),-16384,-16384)
                          for t in np.linspace(0,2*np.pi,32,endpoint=False)])
            drop=peak-rim
            print('BASIS_SHOULDER',x,z,'mean_drop',drop.mean(),'directional_range',np.ptp(drop),flush=True)
            self.assertGreater(drop.mean(),minimum,'256B径の頂部に広い丸肩を残さない')
            self.assertGreater(np.ptp(drop),100,'全方向に同じ断面のドームにしない')

    def test_regeneration_is_byte_identical_and_does_not_touch_earth_001(self):
        original=self.root.parent/'earth_001'
        before={p.name:sha(p) for p in original.iterdir() if p.is_file()}
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'earth_002'
            generate(ROOT/'tools/fixed-map/input/earth_002',output)
            for p in self.root.iterdir():
                if p.is_file():self.assertEqual(sha(p),sha(output/p.name),p.name)
        self.assertEqual(before,{p.name:sha(p) for p in original.iterdir() if p.is_file()})


if __name__=='__main__':unittest.main()
