"""D2の制作水列と、承認済み主要点・不正資産の回帰。"""
import json
import tempfile
import unittest
from pathlib import Path
import numpy as np
from PIL import Image
from generate import ROOT, MAP_RESOURCES
from generate_hydrology import validate_assets
from test_fixed_map import contour_lines

def sampled_curve(points, spacing=4, closed=False):
    a=np.asarray(points,float)
    if closed:a=np.vstack([a,a[0]])
    station=np.r_[0,np.cumsum(np.linalg.norm(np.diff(a[:,:2],axis=0),axis=1))]
    at=np.arange(0,station[-1],spacing)
    return np.column_stack([np.interp(at,station,a[:,c]) for c in range(a.shape[1])])


def bend_deviations(points, window, closed=False):
    q=sampled_curve(points,4,closed)[:,:2];n=window//4
    if closed:q=np.vstack([q,q[:n+1]])
    values=[]
    for i in range(0,len(q)-n,4):
        chord=q[i+n]-q[i];length=np.linalg.norm(chord)
        if length<1:continue
        delta=q[i:i+n+1]-q[i]
        values.append(np.max(np.abs(delta[:,0]*chord[1]-delta[:,1]*chord[0]))/length)
    return np.array(values)


def longest_nearly_straight(points, tolerance=2, closed=False):
    q=sampled_curve(points,4,closed)[:,:2]
    if closed:q=np.vstack([q,q[:129]])
    longest=0
    for n in range(8,min(129,len(q)),4):
        windows=np.lib.stride_tricks.sliding_window_view(q,n+1,axis=0)[::4].transpose(0,2,1)
        chord=windows[:,-1]-windows[:,0];length=np.linalg.norm(chord,axis=1)
        delta=windows-windows[:,0,None,:]
        deviation=np.max(np.abs(delta[:,:,0]*chord[:,None,1]-delta[:,:,1]*chord[:,None,0]),axis=1)/np.maximum(length,1e-9)
        if np.any((deviation<=tolerance)&(length>n*3)):longest=n*4
    return longest


def separated_channel_clearance(points):
    q=sampled_curve(points,8)
    delta=q[:,None,:2]-q[None,:,:2];distance=np.linalg.norm(delta,axis=2)
    separation=np.abs(np.arange(len(q))[:,None]-np.arange(len(q))[None,:])*8
    width=(q[:,None,3]+q[None,:,3])/2
    distant=separation>np.maximum(96,width*4)
    return float(np.min(distance[distant]/width[distant]))



class BankPerspectiveTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = np.load(ROOT/'tools/fixed-map/fixtures/hydrology-bank-faces-before.npz')
        cls.metadata = json.loads(str(cls.fixture['metadata']))

    @classmethod
    def tearDownClass(cls):
        cls.fixture.close()

    def samples(self, view):
        a = np.zeros(self.fixture[view['name']+'_ground'].shape, dtype=[('ground', '<i2'), ('top', '<i2'), ('flags', 'u1')])
        for field in a.dtype.names:
            a[field] = self.fixture[view['name']+'_'+field]
        return a

    def test_same_camera_rays_match_twelve_saved_anvil_columns(self):
        from render_perspective import trace
        for v in self.metadata['views']:
            pixels = [p['pixel'] for p in v['columns']]
            _, hits = trace(self.samples(v), v['job'], v['camera'], 88, 1920, 1080, pixels=pixels)
            self.assertEqual([p['block'] for p in v['columns']], hits[:, :3].tolist(), v['name'])
        for v in self.metadata['fix7_views']:
            columns = [p for p in self.metadata['fix7_columns'] if p['view']==v['photo_id']]
            _, hits = trace(self.samples(v), v['job'], v['camera'], 88, 1920, 1080,
                            pixels=[p['pixel'] for p in columns])
            self.assertEqual([p['hit']['block'] for p in columns], hits[:, :3].tolist(), v['photo_id'])
        from render_perspective import bank_faces, validate_bank_faces
        v = self.metadata['views'][0]
        shifted = dict(step=1, bounds=[a+1024 for a in v['job']['bounds']])
        with self.assertRaisesRegex(ValueError, '実列範囲'):
            validate_bank_faces(bank_faces(self.samples(v), shifted, v['camera'], 88), parallel=True)
        samples = self.samples(v)
        ix = int(np.floor(v['camera']['x']))-v['job']['bounds'][0]
        iz = int(np.floor(v['camera']['z']))-v['job']['bounds'][1]
        embedded = dict(v['camera'], y=float(max(samples['ground'][iz, ix], samples['top'][iz, ix]))-1.12)
        with self.assertRaisesRegex(ValueError, '地表'):
            validate_bank_faces(bank_faces(samples, v['job'], embedded, 88), parallel=True)


    def test_saved_source_parallel_shelves_fail_the_live_ten_block_limit(self):
        h = self.fixture['source_parallel_slope']
        longest = 0
        for x in range(h.shape[1]-1):
            run, previous = 0, None
            for z in range(h.shape[0]):
                pair = tuple(map(int, h[z, x:x+2]))
                edge = pair[0] > 302 and abs(pair[0]-pair[1]) == 1
                run = (run+1 if pair == previous else 1) if edge else 0
                longest = max(longest, run)
                previous = pair
        self.assertEqual(12, longest)
        self.assertGreater(longest, 10, '撮影前の実列をライブの源頭検査が拒否する')
        seam = self.fixture['join_segment_cut_before']
        self.assertGreater(float(np.abs(np.diff(seam, 2, axis=1)).max()), .75,
                           '撮影前の区間切替えをライブの連続面検査が拒否する')

    def test_fix6_lake_margin_and_single_source_face_are_rejected(self):
        from render_perspective import bank_faces, validate_bank_faces
        ground = self.fixture['fix6_outlet_ground'].astype(int)
        top = self.fixture['fix6_outlet_top'].astype(int)
        wet = (self.fixture['fix6_outlet_flags'] & 3) > 0
        maximum = 0
        for axis in [0, 1]:
            a = [slice(None), slice(None)]
            b = [slice(None), slice(None)]
            a[axis], b[axis] = slice(None, -1), slice(1, None)
            a, b = tuple(a), tuple(b)
            maximum = max(maximum, int(np.max(np.where(wet[a] & ~wet[b], ground[b]-top[a], 0))),
                          int(np.max(np.where(wet[b] & ~wet[a], ground[a]-top[b], 0))))
        self.assertGreater(maximum, 8, 'fix6の水際の切断をライブの8B検査が拒否する')
        v = next(v for v in self.metadata['views'] if v['name']=='01-source')
        samples = self.samples(v)
        for field in samples.dtype.names:
            samples[field] = self.fixture['fix6_source_'+field]
        measured = bank_faces(samples, json.loads(str(self.fixture['fix6_source_job'])), v['camera'], 88)
        self.assertEqual(19, measured['longest_visible_face'])
        with self.assertRaises(ValueError):
            validate_bank_faces(measured, straight=True)

    def test_fix7_rectangular_hollows_straight_edges_and_slope_corners_are_rejected(self):
        from render_perspective import rectangular_hollows, bank_faces, validate_bank_faces
        source, join = self.metadata['fix7_views']
        samples = self.samples(source)
        x0, z0 = source['job']['bounds'][:2]
        patch = samples[-12800-z0:-12740-z0, -10248-x0:-10210-x0]
        self.assertEqual(5, rectangular_hollows(patch))
        self.assertGreater(rectangular_hollows(patch), 3, '同幅の矩形切れ込みをライブの3B検査が拒否する')
        measured = bank_faces(samples, source['job'], source['camera'], 88)
        self.assertEqual(12, measured['longest_visible_face'])
        with self.assertRaises(ValueError):
            validate_bank_faces(measured, straight=True)
        measured = bank_faces(self.samples(join), join['job'], join['camera'], 88)
        self.assertEqual(6385, max(c['pixels'] for c in measured['corners']))
        with self.assertRaises(ValueError):
            validate_bank_faces(measured, corners=True)

    def test_visible_source_bands_reject_fix9_and_accept_fix10(self):
        from render_perspective import source_parallel_bands, validate_source_bands
        view = self.metadata['fix9_source']
        measured = source_parallel_bands(self.samples(view), view['job'], view['camera'], 88)
        self.assertEqual(3, measured['banks']['left']['maximum'])
        self.assertEqual(4, measured['banks']['right']['maximum'])
        self.assertTrue(measured['banks']['left']['long_faces'])
        with self.assertRaisesRegex(ValueError, '平行な可視帯'):
            validate_source_bands(measured)
        view = self.metadata['fix10_source']
        measured = source_parallel_bands(self.samples(view), view['job'], view['camera'], 88)
        validate_source_bands(measured)

    def test_fix8_parallel_stacks_and_small_corner_clusters_are_rejected(self):
        from render_perspective import parallel_step_stack, bank_faces, validate_bank_faces
        source, join = self.metadata['fix8_views']
        samples = self.samples(source)
        x0, z0 = source['job']['bounds'][:2]
        steps = parallel_step_stack(samples['ground'][-12792-z0:-12760-z0, -10218-x0:-10204-x0])
        self.assertEqual(7, steps['maximum'])
        self.assertEqual(7, steps['q90'])
        measured = bank_faces(samples, source['job'], source['camera'], 88)
        with self.assertRaisesRegex(ValueError, '平行に重なって'):
            validate_bank_faces(measured, steps=steps)
        measured = bank_faces(self.samples(join), join['job'], join['camera'], 88)
        self.assertEqual(15, measured['maximum_corner_cluster'])
        with self.assertRaisesRegex(ValueError, '密集'):
            validate_bank_faces(measured, dots=True)

    def test_before_images_have_long_slender_step_faces(self):
        from render_perspective import bank_faces, validate_bank_faces
        for v in self.metadata['views']:
            measured = bank_faces(self.samples(v), v['job'], v['camera'], 88)
            self.assertGreaterEqual(measured['nearfield_coverage'], .95, '形の検査に十分な前景を含む')
            with self.assertRaises(ValueError):
                validate_bank_faces(measured, parallel=v['name']=='01-source', thin=v['name']!='01-source')
            self.assertTrue(measured['slender'], v['name']+' 撮影前の端面を検出する')

        v = next(v for v in self.metadata['views'] if v['name']=='05-join15')
        rejected = self.samples(v)
        for field in rejected.dtype.names:
            rejected[field] = self.fixture['rejected_join15_'+field]
        measured = bank_faces(rejected, v['job'], v['camera'], 88)
        with self.assertRaises(ValueError):
            validate_bank_faces(measured, thin=True)
        self.assertIn((0, -6175, 141, -1), [p['face'] for p in measured['slender']], '低解像度で見逃した候補13の端面を拒否する')


class HydrologyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root=ROOT/MAP_RESOURCES/'earth_002'
        cls.definition,cls.images=validate_assets(cls.root)
        cls.document=json.loads((cls.root/'hydrology.json').read_text(encoding='utf-8'))

    def test_approved_major_points_are_present_and_duplicate_columns_have_one_level(self):
        fixture=json.loads((ROOT/'tools/fixed-map/fixtures/earth_002/hydrology.json').read_text(encoding='utf-8'))
        self.assertEqual(fixture['nodes'],self.document['nodes'])
        tiles={tuple(t):i for i,t in enumerate(self.definition['tiles'])}
        for expected in fixture['reaches']:
            actual=next(r for r in self.document['reaches'] if r['id']==expected['id'])
            rows=actual['profile_x_z_surface_full_width_depth']
            for p in expected['profile_x_z_surface_full_width_depth']:self.assertIn(p,rows,expected['id'])
            columns={}
            for p in rows:
                key=tuple(p[:2]);self.assertIn(p[2],{columns.get(key,p[2])},'同じブロックへ別水位を指定しない');columns[key]=p[2]
            self.assertTrue(all(a[2]>=b[2] for a,b in zip(rows,rows[1:])))
            levels=[]
            for x,z,*_ in rows:
                i=tiles[(x//128,z//128)];px=i%self.definition['atlas_columns']*128+x%128;pz=i//self.definition['atlas_columns']*128+z%128
                self.assertGreater(int(self.images[0][pz,px]),0,expected['id']+' 水列が欠ける')
                levels.append(int(self.images[1][pz,px])-64)
            self.assertTrue(all(a>=b for a,b in zip(levels,levels[1:])),expected['id']+' 合流・重複列を含む実水位の逆勾配')

    def test_water_steps_vary_and_the_north_mouth_has_a_long_flat_surface(self):
        for rid in ['R05A','R05B','R16']:
            r=next(r for r in self.document['reaches'] if r['id']==rid)
            q=np.asarray(r['profile_x_z_surface_full_width_depth'],float)
            station=np.r_[0,np.cumsum(np.linalg.norm(np.diff(q[:,:2],axis=0),axis=1))]
            drops=np.flatnonzero(np.diff(q[:,2])<0)+1
            intervals=np.diff(station[drops])
            self.assertGreater(float(intervals.std()/intervals.mean()),.2,rid+' 等間隔の水面段')
            self.assertLessEqual(float((-np.diff(q[:,2])).max()),3,'落差は1～3B')
            if rid=='R05A':
                self.assertGreater(len(set((-np.diff(q[:,2]))[drops-1])),1,'落差も変える')
            if rid=='R16':
                self.assertTrue(np.all(q[station>=station[-1]-76,2]==63),'河口直前の緩流に段を置かない')

    def test_every_serialized_water_column_has_one_owner_and_a_lower_bed(self):
        owner,water,bed=self.images;wet=owner>0
        self.assertEqual(9,len(self.definition['owners']))
        self.assertGreater(int(wet.sum()),1000000)
        self.assertTrue(np.all(water[wet]>bed[wet]))
        lake=owner==9;self.assertEqual({209},set(np.unique(water[lake])))
        self.assertEqual(184,int(bed[lake].min()),'最深床Y120')
        for id in range(1,10):self.assertGreater(int(np.count_nonzero(owner==id)),0)

    def test_channels_break_long_straights_without_periodic_meanders(self):
        for reach in self.document['reaches']:
            points=reach['profile_x_z_surface_full_width_depth']
            deviation=bend_deviations(points,300)
            self.assertGreater(float(deviation.min()),8,reach['id']+' 300Bのほぼ直線の河道')
            if reach['id']=='R05U':
                self.assertEqual(0,longest_nearly_straight(points[:61],tolerance=.1),'源頭の先頭61点に32B以上の直線を戻さない')
            checked=sampled_curve(points,4)[64:] if reach['id']=='R05U' else points
            straight=longest_nearly_straight(checked)
            print('HYDROLOGY_STRAIGHT',reach['id'],straight)
            self.assertLessEqual(straight,160,reach['id']+' 2B以内のずれで続く直線が長い')
            q=sampled_curve(points,8)[:,:2]
            if len(q)<100:continue
            direction=np.unwrap(np.arctan2(*(q[4:]-q[:-4])[:,::-1].T))
            direction=np.convolve(direction,np.ones(7)/7,'valid')
            turn=np.diff(direction);cross=np.flatnonzero(turn[1:]*turn[:-1]<0)*8
            intervals=np.diff(cross);intervals=intervals[intervals>=32]
            self.assertGreater(len(intervals),3,reach['id'])
            self.assertGreater(float(np.std(intervals)/np.mean(intervals)),.2,reach['id']+' 等間隔の蛇行')
        self.assertEqual(0,float(bend_deviations([[0,0],[1000,0]],300).min()))
        self.assertGreater(longest_nearly_straight([[0,0],[60,0]],tolerance=.1),20,'旧源頭の60B直線を検出する')

    def test_shore_has_bays_without_polygon_edges_or_circular_outline(self):
        shore=self.document['lakes'][0]['outline_x_z']
        for point in [[-7450,-10400],[-7150,-10750],[-6800,-10680],[-6490,-10300],[-6850,-9980],[-7260,-10110]]:
            self.assertIn(point,shore,'主要岸点を維持する')
        deviation=bend_deviations(shore,120,True)
        self.assertGreater(float(np.percentile(deviation,10)),5,'短い直線の岸が反復する')
        self.assertGreater(float(bend_deviations(shore,300,True).min()),15,'300B続くほぼ直線の湖岸')
        self.assertGreater(float(deviation.std()/deviation.mean()),.35,'円弧が一様な湖岸')
        self.assertLessEqual(longest_nearly_straight(shore,2,True),128,'岸の最大直線長')
        polygon=np.array(shore);x0=int(polygon[:,0].min()//2*2-4);z0=int(polygon[:,1].min()//2*2-4)
        x1=int(polygon[:,0].max()//2*2+6);z1=int(polygon[:,1].max()//2*2+6)
        mask=np.zeros(((z1-z0)//2,(x1-x0)//2),np.uint8)
        columns=self.definition['atlas_columns']
        for i,(tx,tz) in enumerate(self.definition['tiles']):
            ax=max(x0,tx*128);bx=min(x1,(tx+1)*128);az=max(z0,tz*128);bz=min(z1,(tz+1)*128)
            if ax>=bx or az>=bz:continue
            src=self.images[0][i//columns*128+az%128:i//columns*128+bz-tz*128:2,
                               i%columns*128+ax%128:i%columns*128+bx-tx*128:2]
            mask[(az-z0)//2:(bz-z0)//2,(ax-x0)//2:(bx-x0)//2]=src==9
        contours=contour_lines(mask.astype(float)-.5,x0,z0,2)
        self.assertTrue(contours,'実水列の湖岸がない')
        maximum=max(longest_nearly_straight(c,2,np.linalg.norm(c[0]-c[-1])<4) for c in contours)
        print('HYDROLOGY_SHORE maximum_pixel_straight',maximum)
        self.assertLessEqual(maximum,144,'生成水列に直線・多角形の岸が残る')
        failures=json.loads((ROOT/'tools/fixed-map/fixtures/hydrology-shape-failures.json').read_text(encoding='utf-8'))
        self.assertGreater(longest_nearly_straight(failures['shore_x_z'],2,True),144,'報告画像の岸を拒否する')
        self.assertGreater(longest_nearly_straight(failures['outlet_x_z']),160,'報告画像の出口河道を拒否する')

        polygon=[[0,0],[800,0],[800,600],[0,600]]
        self.assertEqual(0,float(bend_deviations(polygon,120,True).min()))
        t=np.linspace(0,2*np.pi,1000,endpoint=False)
        circle=np.column_stack([500*np.cos(t),500*np.sin(t)])
        circular=bend_deviations(circle,120,True)
        self.assertLess(float(circular.std()/circular.mean()),.02)

    def test_distant_parts_of_one_channel_do_not_overlap_into_blind_arms(self):
        for reach in self.document['reaches']:
            self.assertGreater(separated_channel_clearance(reach['profile_x_z_surface_full_width_depth']),1.1,reach['id']+' 折返しの水面が接触する盲端')
        loop=[[0,0,150,16],[200,0,150,16],[400,0,150,16],[200,4,150,16],[0,4,140,16]]
        self.assertLess(separated_channel_clearance(loop),1)

    def test_lake_bed_is_gradual_without_shallow_fixed_floor(self):
        columns=self.definition['atlas_columns']
        lake={}
        for i,(tx,tz) in enumerate(self.definition['tiles']):
            owner=self.images[0][i//columns*128:(i//columns+1)*128,i%columns*128:(i%columns+1)*128]
            beds=self.images[2][i//columns*128:(i//columns+1)*128,i%columns*128:(i%columns+1)*128].astype(int)-64
            for z,x in np.argwhere(owner==9):lake[(tx*128+int(x),tz*128+int(z))]=int(beds[z,x])
        jump=max(abs(h-lake.get((x+dx,z+dz),h)) for (x,z),h in lake.items() for dx,dz in [(1,0),(0,1)])
        shallow=sum(h==144 for h in lake.values())/len(lake)
        self.assertLessEqual(jump,1,'湖底が水平1Bで急落する')
        self.assertLess(shallow,.08,'湖の内部に広いY144の固定床を作らない')
        self.assertEqual(120,min(lake.values()))
        print('LAKE_BED maximum_jump',jump,'shallow_fraction',shallow)
        self.assertLessEqual(abs(lake[(-7184,-10724)]-lake[(-7184,-10723)]),1,'撮影で見つかった16Bの急落')

    def test_inverted_water_column_and_unknown_owner_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp);(output/'index.json').write_bytes((self.root/'index.json').read_bytes())
            arrays=[a.copy() for a in self.images];z,x=np.argwhere(arrays[0]>0)[0]
            for name,a in zip(['hydrology_owner.png','hydrology_surface.png','hydrology_bed.png'],arrays):Image.fromarray(a).save(output/name)
            arrays[2][z,x]=arrays[1][z,x];Image.fromarray(arrays[2]).save(output/'hydrology_bed.png')
            with self.assertRaisesRegex(ValueError,'水面と床'):validate_assets(output)
            Image.fromarray(self.images[2]).save(output/'hydrology_bed.png');arrays[0][z,x]=250;Image.fromarray(arrays[0]).save(output/'hydrology_owner.png')
            with self.assertRaisesRegex(ValueError,'所有者'):validate_assets(output)

if __name__=='__main__':unittest.main()
