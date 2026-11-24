"""配置図の制作契約を、同梱資産と再生成の両方で検査する。"""

import hashlib
import json
import math
import shutil
import tempfile
import unittest
from collections import deque
from contextlib import chdir
from pathlib import Path
from unittest.mock import patch

import numpy as np
from PIL import Image

from generate import (LAYERS, MAP_RESOURCES, OUTPUT, RESOURCE_FILES, ROOT, SIZE, TOOL,
                      drainage, generate, island_component, island_relief, load_inputs, main, mountain_coordinates, neighbors, pixel,
                      raster_line, sha, termination_relief, headwater_relief, curved_points, ridge_relief, join_relief, validate, write_json)


def contour_lines(field,x0,z0,step=16):
    """配置標高の等高線を画素間で補間し、連続した線へつなぐ。"""
    edges={};adj={}
    for y in range(field.shape[0]-1):
        for x in range(field.shape[1]-1):
            v=[field[y,x],field[y,x+1],field[y+1,x+1],field[y+1,x]]
            corners=[(x,y),(x+1,y),(x+1,y+1),(x,y+1)]
            ids=[]
            for k in range(4):
                j=(k+1)%4
                if (v[k]>=0)==(v[j]>=0):continue
                a,b=corners[k],corners[j]
                key=tuple(sorted([a,b]));t=v[k]/(v[k]-v[j])
                edges[key]=(x0+(a[0]+(b[0]-a[0])*t)*step,z0+(a[1]+(b[1]-a[1])*t)*step)
                ids.append((k,key))
            if len(ids)==2:pairs=[(ids[0][1],ids[1][1])]
            elif len(ids)==4:
                e=dict(ids);pairs=[(e[0],e[1]),(e[2],e[3])] if (v[0]>=0)==(sum(v)>=0) else [(e[0],e[3]),(e[1],e[2])]
            else:continue
            for a,b in pairs:adj.setdefault(a,[]).append(b);adj.setdefault(b,[]).append(a)
    visited=set();result=[]
    for start in sorted(adj,key=lambda k:len(adj[k])):
        if start in visited:continue
        chain=[];previous=None;current=start
        while current not in visited:
            visited.add(current);chain.append(edges[current]);options=[q for q in adj[current] if q!=previous]
            if not options:break
            previous,current=current,options[0]
        if len(chain)>3:result.append(np.array(chain))
    return result
def longest_circular_arc(chains):
    """一定曲率の長い円弧を、円への残差と半径で見つける。"""
    best=0;detail=None
    for chain in chains:
        dist=np.r_[0,np.cumsum(np.linalg.norm(np.diff(chain,axis=0),axis=1))]
        if dist[-1]<160:continue
        points=np.column_stack([np.interp(np.arange(0,dist[-1],8),dist,chain[:,k]) for k in [0,1]])
        for count in range(25,50,4):
            for start in range(0,len(points)-count+1,4):
                p=points[start:start+count];local=p-p.mean(0)
                coef=np.linalg.lstsq(np.column_stack([2*local[:,0],2*local[:,1],np.ones(count)]),(local*local).sum(1),rcond=None)[0]
                r2=coef[2]+coef[0]**2+coef[1]**2
                if r2<=0:continue
                radius=math.sqrt(r2);arc=(count-1)*8
                if not 64<radius<256 or arc/radius<math.pi/3:continue
                residual=np.linalg.norm(local-coef[:2],axis=1)-radius
                rms=float(np.sqrt(np.mean(residual**2)))
                if rms<3.5 and max(abs(residual))<8 and arc>best:
                    best=arc;detail={'radius':radius,'rms':rms,'start':p[0].tolist(),'end':p[-1].tolist()}
    return best,detail


class FixedMapTests(unittest.TestCase):
    def test_headwater_has_asymmetric_shoulders_and_joining_gullies(self):
        x=np.array([-65,-200,200,0,0,0])
        z=np.array([-150,-150,-150,-320,0,-500])
        height,weight=headwater_relief(x,z,0,0,np.zeros(6))
        self.assertGreater(weight[0],.9)
        self.assertTrue(np.all(weight[[1,2,3,5]]==0),"広い谷頭や四角い雪原を彫らない")
        self.assertGreater(height[0],200,"北へ登る沢筋が主稜線を低く切り開かない")
        np.testing.assert_allclose(headwater_relief(np.array([0]),np.array([0]),0,0,np.array([0]))[0],[114])

    def test_curved_ridges_keep_their_fixed_positions_heights_and_widths(self):
        for mountain in self.config["mountains"]:
            for ridge in mountain["flanks"]["ridges"]+mountain["flanks"]["toes"]:
                curve=curved_points(ridge,0,spacing=48)
                self.assertEqual(curve[0],ridge[0])
                self.assertEqual(curve[-1],ridge[-1])
                self.assertTrue(set(map(tuple,ridge))<=set(map(tuple,curve)),"曲線の途中の固定点も移動しない")
                self.assertTrue(all(p[3]>0 for p in curve))

    def test_flank_toes_join_the_plain_without_a_slope_step(self):
        ridge={"base_y":104,"ridges":[[[0,0,264,240],[0,320,144,160]]],"valleys":[]}
        x=np.array([0.,180,220,232,236,240,244])
        z=np.zeros(x.shape)
        height=ridge_relief(ridge,x,z,curved=True)
        self.assertEqual(height[0],264,"支稜の固定した頂部を変えない")
        np.testing.assert_array_equal(height[-2:],[104,104])
        self.assertTrue(np.all(np.diff(height)<=0),"山すそへ連続して低くなる")
        self.assertLess((height[-3]-height[-2])/4,.05,"端の勾配を平野のゼロへ近づける")
        self.assertLess(height[3]-height[4],height[2]-height[3],"山すそ側で勾配が緩くなる")

    def test_flank_ridges_stay_inside_the_approved_mountain_ranges(self):
        for mountain,count in zip(self.config["mountains"],[33,8]):
            ridges=mountain["flanks"]["ridges"]
            self.assertEqual(len(ridges),count)
            lengths=[]
            for ridge in ridges:
                x,z=np.array([p[0] for p in ridge]),np.array([p[1] for p in ridge])
                distance,_,_=mountain_coordinates(mountain,x,z)
                self.assertTrue(np.all(distance<mountain["radius_blocks"]),mountain["id"])
                self.assertTrue(all(a[2]>b[2] for a,b in zip(ridge,ridge[1:])))
                lengths.append(sum(math.dist(a[:2],b[:2]) for a,b in zip(ridge,ridge[1:])))
            self.assertGreater(max(lengths)-min(lengths),200,"支稜は同じ長さで並べない")

    def test_flank_toes_split_and_do_not_end_in_long_circular_arcs(self):
        for mountain in self.config["mountains"]:
            terrain=mountain["flanks"]
            self.assertEqual(len(terrain["toes"]),len(terrain["ridges"])*2)
            self.assertGreaterEqual(len(terrain["valleys"]),len(terrain["toes"]))
            roots=[np.linalg.norm(np.array(terrain["toes"][i][0][:2])-np.array(terrain["toes"][i+1][0][:2]))
                   for i in range(0,len(terrain["toes"]),2)]
            self.assertGreater(sum(d>=80 for d in roots),len(roots)*.7,"枝分かれ点を分散し、同じ点から放射させない")

            for valley in terrain["valleys"]:
                points=np.array(valley)
                distance,_,_=mountain_coordinates(mountain,points[:,0],points[:,1])
                self.assertGreater(distance[-1],distance[0],"支沢は山すそへ抜ける")
                self.assertTrue(np.all(np.linalg.norm(np.diff(points[:,:2],axis=0),axis=1)>0))
                self.assertTrue(np.all(np.diff(points[:,2])<0))
            for ridge in terrain["toes"]:
                points=np.array(ridge)
                distance,_,_=mountain_coordinates(mountain,points[:,0],points[:,1])
                self.assertTrue(np.all(distance<mountain["radius_blocks"]))
                self.assertEqual(ridge[-1][2],terrain["base_y"])
                self.assertTrue(all(a[2]>b[2] for a,b in zip(ridge,ridge[1:])))
        for bounds in [(-10400,-11200,-9856,-10704),(-9968,-11328,-9296,-10736)]:
            x0,z0,x1,z1=bounds
            height=self.layers["height"][(z0+16384)//16:(z1+16384)//16+1,(x0+16384)//16:(x1+16384)//16+1].astype(float)-64
            for level in (120,140,160):
                arc,detail=longest_circular_arc(contour_lines(height-level,x0,z0))
                self.assertLess(arc,192,f"支稜の先端・谷側を長い一定曲率の円弧にしない: {bounds} Y{level} arc={arc} {detail}")

    def test_flank_valleys_do_not_become_level_terraces(self):
        x0,z0,x1,z1=-10560,-11600,-10240,-11008
        height=self.layers["height"][(z0+16384)//16:(z1+16384)//16+1,(x0+16384)//16:(x1+16384)//16+1].astype(float)-64
        self.assertTrue(height.min()<108 and height.max()>180,"谷と周囲の山地を含む範囲を検査する")
        seen=np.zeros(height.shape,bool)
        largest=0
        for y,x in np.ndindex(height.shape):
            if seen[y,x] or not 108<height[y,x]<160:continue
            queue=deque([(y,x)]);seen[y,x]=True;area=0
            while queue:
                z,c=queue.popleft();area+=1
                for dz,dx in ((0,1),(0,-1),(1,0),(-1,0)):
                    nz,nx=z+dz,c+dx
                    if 0<=nz<height.shape[0] and 0<=nx<height.shape[1] and not seen[nz,nx] and height[nz,nx]==height[y,x]:
                        seen[nz,nx]=True;queue.append((nz,nx))
            largest=max(largest,area)
        self.assertLessEqual(largest*256,4096,"排水評価で谷を大きな水平面へ埋め戻さない")
        x0,z0,x1,z1=-10608,-11616,-10224,-10976
        height=self.layers["height"][(z0+16384)//16:(z1+16384)//16+1,(x0+16384)//16:(x1+16384)//16+1].astype(float)-64
        for level in (108,112,120,140,160):
            longest=0
            for chain in contour_lines(height-level,x0,z0):
                distance=np.r_[0,np.cumsum(np.linalg.norm(np.diff(chain,axis=0),axis=1))]
                points=np.column_stack([np.interp(np.arange(0,distance[-1],16),distance,chain[:,k]) for k in (0,1)])
                for start in range(len(points)):
                    for end in range(start+1,len(points)):
                        if min(np.ptp(points[start:end+1],axis=0))>16:break
                        longest=max(longest,(end-start)*16)
            self.assertLess(longest,160,f"谷の縁を幅16の帯で長く座標軸へ揃えない: Y{level} length={longest}")


    def test_relief_intersections_keep_the_plain_and_join_slopes(self):
        x=np.linspace(-4,4,17)
        height=join_relief(130+x,130-x,104)
        self.assertTrue(np.all(height<=np.maximum(130+x,130-x)))
        self.assertEqual(float(join_relief(np.array([104.]),np.array([104.]),104)[0]),104)
        self.assertLess(abs(height[9]-2*height[8]+height[7]),.2,"交差部に鋭い勾配の折れを作らない")
        terrain={"base_y":104,"ridges":[[[0,0,185,86],[-246,62,158,120],[-285,85,104,22]]],"valleys":[]}
        folds=[]
        for step in (2,.5):
            z,x=np.mgrid[-40:200:step,-344:-144:step]
            shape=ridge_relief(terrain,x,z,curved=True)
            folds.append(max(np.max(abs(np.diff(shape,n=2,axis=0))),np.max(abs(np.diff(shape,n=2,axis=1)))))
        self.assertLess(folds[1],2,"最近点の切替えに標高ジャンプを残さない")
        self.assertLess(folds[1],folds[0]*.35,"格子を細かくすると自然な曲率の差分は縮み、継ぎ目のジャンプは縮まない")


    def test_soil_quality_has_no_contaminated_or_toxic_category(self):
        self.assertLessEqual(int(self.layers["soil"].max()), 3)
        self.assertEqual(set(self.index["tables"]["soil"]), {"0", "1", "2", "3"})
        for region in (5, 6):
            self.assertTrue(np.all(self.layers["soil"][self.layers["region"] == region] == 3))

    def test_western_termination_has_separate_descending_ridges_and_valleys(self):
        terminal=self.config["mountains"][0]["termination"]
        self.assertEqual(terminal,self.index["mountains"][0]["termination"])
        self.assertEqual(len(terminal["ridges"]),4)
        self.assertEqual(len(terminal["valleys"]),3)
        for ridge in terminal["ridges"]:
            self.assertTrue(all(a[2]>b[2] for a,b in zip(ridge[1:],ridge[2:])))
        x=np.array([-13500,-13740,-13920,-13600,-13200])
        z=np.array([-11712,-11760,-11770,-11970,-12500])
        height,weight=termination_relief(terminal,x,z)
        self.assertGreater(height[0]-height[1],150)
        self.assertGreater(height[1]-height[2],100)
        self.assertLess(height[3],height[1]-100)
        self.assertGreater(height[4],140)
        np.testing.assert_array_equal(weight[:4],np.ones(4))
        self.assertTrue(.8<weight[4]<1)

    def test_underground_surface_controls_cover_centers_and_entries(self):
        layout=json.loads((OUTPUT/"underground.json").read_text(encoding="utf-8"))
        expected=set()
        for deposit in layout["deposits"]:
            x,z=deposit["x"],deposit["z"]
            expected.add((x,z))
            if not deposit["id"].startswith("oil_") and not deposit["vanilla"]:
                expected.add((x-max(96,deposit["depth"]+24),z))
        for space in layout["spaces"]:
            x,z=space["x"],space["z"]
            expected.add((x,z))
            expected.add((x-int(np.sign(space["end_x"]-x))*(space["depth"]+16),z))
        controls=self.config["underground_surface_controls"]
        self.assertEqual(self.index["underground_surface_controls"],controls)
        self.assertEqual(len(controls),77)
        self.assertEqual({(x,z) for x,z,y in controls},expected)
        contacts=self.config["underground_weathered_contacts"]
        self.assertEqual(self.index["underground_weathered_contacts"],contacts)
        self.assertEqual(len(contacts),22)
        self.assertEqual(len({tuple(p) for p in contacts}),22)
        self.assertTrue({tuple(p) for p in contacts}<={(v["x"],v["z"]) for v in layout["deposits"]})
        for x,z,y in controls:
            px,pz=pixel([x,z])
            self.assertGreater(self.land[pz,px],0)
            self.assertTrue(64<=y<=520)

    def test_small_island_summits_stay_inside_the_approved_components(self):
        for island in self.config["mountain_islands"]:
            component=island_component(self.land,island["anchor_xz"])
            summits=island["summits"]
            self.assertGreaterEqual(len(summits),2)
            self.assertEqual(max(s["peak_y"] for s in summits),island["peak_y"])
            for summit in summits:
                x,z=pixel(summit["xz"])
                self.assertTrue(component[z,x])
                self.assertGreaterEqual(summit["peak_y"],123)
                self.assertLessEqual(summit["peak_y"],205)
            self.assertEqual(self.index["mountain_islands"][self.config["mountain_islands"].index(island)]["summits"],summits)
        self.assertEqual(self.index["geology_profile_version"],"geology_002")

    @classmethod
    def setUpClass(cls):
        cls.config, cls.land = load_inputs(TOOL / "input")
        cls.layers = {name: np.array(Image.open(OUTPUT/f"{name}.png")) for name in LAYERS}
        cls.index = json.loads((OUTPUT/"index.json").read_text(encoding="utf-8"))

    def test_dimensions_types_and_hashes(self):
        validate(OUTPUT,self.config["map_version"])
        self.assertLessEqual(self.layers["height"].max(),639)
        self.assertEqual(self.layers["height"].dtype,np.uint16)
        for name in LAYERS[1:]:
            self.assertEqual(self.layers[name].dtype,np.uint8)
        manifest=json.loads((OUTPUT/"manifest.json").read_text(encoding="utf8"))
        dimension=json.loads((ROOT/"modules/world/src/main/resources/data/ashen_frontier/dimension_type/fixed_map.json").read_text(encoding="utf8"))
        self.assertEqual((manifest["min_y"],manifest["height"]),(-128,704))
        self.assertEqual((dimension["min_y"],dimension["height"],dimension["logical_height"]),(-128,704,704))

    def test_fixed_summits_and_confirmed_ocean_features(self):
        for mountain in self.config["mountains"]:
            self.assertGreaterEqual(len(mountain["summits"]),10)
            for summit in mountain["summits"]:
                x,z=summit["xz"]
                distance,_,_=mountain_coordinates(mountain,np.array([[x]]),np.array([[z]]))
                self.assertLess(distance.item(),mountain["radius_blocks"])
                self.assertLessEqual(summit["peak_y"],520)
        self.assertEqual(self.index["ocean_features"],self.config["ocean_features"])
        self.assertEqual([f["kind"] for f in self.config["ocean_features"]],["trench","ridge","seamount"])
        for feature in self.config["ocean_features"]:
            self.assertEqual(feature["status"],"confirmed")
            for point in feature["points"]:
                x,y=pixel(point)
                self.assertEqual(self.land[y,x],0,"固定海底起伏の中心は海面下")

    def test_fixed_mountain_ranges_geology_and_passes(self):
        mountains = self.config["mountains"]
        self.assertEqual([m["id"] for m in mountains],["north_main","north_branch"])
        self.assertEqual(self.index["mountains"],mountains)
        self.assertEqual(mountains[0]["radius_blocks"],1100)
        self.assertEqual(mountains[1]["radius_blocks"],700)
        self.assertEqual([m["peak_y"] for m in mountains],[520,440])
        self.assertGreaterEqual(mountains[1]["points"][-1][1]+16384,1000)
        z,x = np.mgrid[:SIZE,:SIZE]
        bx,bz = x*16-16384,z*16-16384
        for mountain in mountains:
            distance,_,_ = mountain_coordinates(mountain,bx,bz)
            active = (distance<mountain["radius_blocks"]) & (self.land==1)
            self.assertTrue(np.all(self.layers["geology"][active]==3),mountain["id"])
            self.assertGreater(self.layers["height"][active].max()-64,200)
            for a,b in zip(mountain["points"],mountain["points"][1:]):
                for t in np.linspace(0,1,101):
                    px,py = pixel(np.rint(np.array(a)+(np.array(b)-a)*t).astype(int))
                    self.assertEqual(self.land[py,px],1,"主脈・移動した分岐の陸上連続性")
        branch=mountains[1]
        self.assertEqual(branch["points"],[[-3800,-12020],[-2368,-14992]])
        self.assertEqual(branch["passes"],[])
        path=next(r for r in self.config["routes"] if r["id"]=="north_path")
        self.assertNotIn([-5448,-13264],path["points"])
        for point in self.index["walkways"][0]["points_x_z_y"]:
            distance,_,_=mountain_coordinates(branch,np.array([[point[0]]]),np.array([[point[1]]]))
            self.assertGreater(distance.item(),branch["radius_blocks"],"north_pathは分岐を横切らない")
        cold = next(f for f in self.config["facilities"] if f["id"]=="cold_north")
        px,py = pixel(cold["xz"])
        self.assertEqual(self.layers["height"][py,px]-64,cold["surface_y"])
        for mountain in mountains:
            for saddle in mountain["passes"]:
                points = next(w["points_x_z_y"] for w in self.index["walkways"] if w["id"]==saddle["route"])
                self.assertIn(saddle["xz"],[p[:2] for p in points])
                self.assertEqual(next(p[2] for p in points if p[:2]==saddle["xz"]),209.1739,"既存の峠の路面高を維持する")

    def test_approved_mask_changes_only_by_the_two_island_channels(self):
        delta = json.loads((TOOL/"input/island_channels.json").read_text(encoding="utf-8"))
        self.assertEqual(delta["original_file_sha256"],"e5e2acecac47217278b1b195bf4fd5d6abe3721708b597c1556df6797068b07e")
        self.assertEqual(delta["original_pixels_sha256"],"4f87816791c601eeee24173b20e6b446c242f5cdd827f6a9482a93c0fd05de40")
        self.assertEqual(sha(TOOL/"input/land.png"),delta["updated_file_sha256"])
        restored = self.land.copy()
        for y,x in delta["removed_pixels_y_x"]:
            self.assertEqual(self.land[y,x],0)
            restored[y,x] = 4
        self.assertEqual(hashlib.sha256(restored.tobytes()).hexdigest(),delta["original_pixels_sha256"])
        allowed = island_component(restored,[-4912,-14768]) | island_component(restored,[-5088,-15072])
        self.assertTrue(all(allowed[y,x] for y,x in delta["removed_pixels_y_x"]))
        np.testing.assert_array_equal(self.layers["coast_river"] & 8 != 0,self.land==0)
        np.testing.assert_array_equal(self.layers["region"] != 0,self.land>0)
        self.assertEqual(len(self.config["mountain_islands"]),5)
        occupied = np.zeros(self.land.shape,bool)
        for specification,peak in zip(self.config["mountain_islands"],self.index["mountain_islands"]):
            component = island_component(self.land,specification["anchor_xz"])
            self.assertFalse(np.any(occupied & component))
            occupied |= component
            self.assertTrue(np.all(self.layers["geology"][component]==3))
            heights = self.layers["height"][component].astype(int)-64
            self.assertEqual(heights.max(),specification["peak_y"])
            self.assertGreaterEqual(heights.max(),123)
            self.assertLessEqual(heights.max(),213)
            self.assertGreaterEqual(peak["summit_xz"][1]+16384,1000)
            reconstructed = np.zeros(self.land.shape,bool)
            for y,x0,x1 in peak["pixel_spans_y_x0_x1ex"]: reconstructed[y,x0:x1] = True
            np.testing.assert_array_equal(component,reconstructed)

    def test_required_walkway_grades_and_endpoints(self):
        facilities = {f["id"]: f for f in self.config["facilities"]}
        for walkway in self.index["walkways"]:
            points = walkway["points_x_z_y"]
            start,end = ("spawn","mining") if walkway["id"]=="mining_access" else ("mining","cold_north")
            for point,anchor in [(points[0],start),(points[-1],end)]:
                self.assertEqual(point[:2],facilities[anchor]["xz"])
                self.assertEqual(point[2],facilities[anchor]["surface_y"])
            for a,b in zip(points,points[1:]):
                self.assertLessEqual(abs(a[2]-b[2]),math.dist(a[:2],b[:2])/8+.00011)
                px,py = pixel(a[:2])
                self.assertEqual(self.land[py,px],1)

    def test_island_relief_is_limited_to_the_five_components(self):
        height = np.where(self.land>0,140.,40.).astype(np.float32)
        original = height.copy()
        selected = np.zeros(height.shape,bool)
        island_relief(self.config,self.land,height,selected)
        self.assertLessEqual(height[selected].max(),213)
        self.assertGreater(height[selected].min(),63)
        np.testing.assert_array_equal(height[~selected],original[~selected])

    def test_every_pixel_and_deferred_regions(self):
        for name in LAYERS[1:]:
            table = self.index["tables"][name] if name != "coast_river" else None
            if table:
                self.assertTrue(np.isin(self.layers[name], [int(v) for v in table]).all(),name)
        region = self.layers["region"]
        self.assertTrue(np.all(region[self.land==0]==0))
        self.assertTrue(np.all(region[self.land>0]!=0))
        self.assertTrue(np.all(region[self.land>1]==255))
        self.assertFalse(np.isin(region,[8,9]).any())
        self.assertEqual({d["resource"] for d in self.index["deposits"]},
                         {"iron","copper","coal","tin","limestone","clay","nitrate","silica_sand"})
        for deposit in self.index["deposits"]:
            x,y = pixel(deposit["xz"])
            self.assertEqual(self.land[y,x],1)
            self.assertEqual(self.layers["geology"][y,x],deposit["geology_profile"])

    def test_area_ratio_regression(self):
        counts = np.bincount(self.land.ravel(),minlength=5)[1:]
        actual = counts / counts.sum()
        np.testing.assert_allclose(actual,self.index["metrics"]["land_area_ratios"],rtol=0,atol=1e-12)
        error = np.abs(actual-self.config["area_ratio_reference"])
        self.assertTrue(np.all(error <= self.config["area_ratio_tolerance"]),
                        f"承認済みマスクの面積比から変化: 実測={actual.tolist()}, 許容差=±1ポイント")
        self.assertTrue(self.index["metrics"]["area_ratio_within_reference"])

    def test_northwest_mainland_has_no_unassigned_pixels(self):
        region = self.layers["region"]
        self.assertTrue(np.isin(region[self.land==1],range(1,8)).all())
        y,x = np.mgrid[:SIZE,:SIZE]
        south = (self.land==1) & (y*16-16384 > -4096)
        self.assertTrue(south.any())
        west = south & (x*16-16384 < -9000)
        east = south & (x*16-16384 > -6500)
        self.assertTrue(west.any() and east.any())
        self.assertTrue(np.all(region[west]==4),"南西の縁は内陸乾燥地域")
        self.assertTrue(np.isin(region[east],[5,6]).all(),"南東の縁は工業・沿岸工業地帯")

    def test_regional_adjacency(self):
        region = self.layers["region"]
        for a,b in self.config["adjacent_regions"]:
            self.assertTrue(np.any((region==a) & neighbors(region==b)),f"隣接なし: {a},{b}")

    def test_reference_coasts_and_channels(self):
        metadata = json.loads((TOOL/"input/extraction.json").read_text(encoding="utf-8"))
        controls = json.loads((TOOL/"input/corrections.json").read_text(encoding="utf-8"))
        left,top,right,bottom = metadata["crop_xyxy"]
        for key,land in [("land_control_points",True),("sea_control_points",False)]:
            for x,y in controls[key]:
                px,py = int((x-left)*SIZE/(right-left)),int((y-top)*SIZE/(bottom-top))
                self.assertEqual(self.land[py,px]>0,land,f"原図の照合点: {x},{y}")
        self.assertFalse(np.isin(self.land[:,0],[1,2,3]).any(),"大陸が西端の海を埋めています")
        for value in [1,2,3]:
            mask = self.land==value
            seen = np.zeros(mask.shape,bool)
            y,x = next(zip(*np.nonzero(mask)))
            queue = deque([(y,x)])
            seen[y,x] = True
            while queue:
                y,x = queue.popleft()
                for a,b in [(y-1,x),(y+1,x),(y,x-1),(y,x+1)]:
                    if 0 <= a < SIZE and 0 <= b < SIZE and mask[a,b] and not seen[a,b]:
                        seen[a,b] = True
                        queue.append((a,b))
            self.assertTrue(np.all(seen[mask]),f"大陸が分断されています: {value}")

    def test_meanders_valleys_and_region_shapes(self):
        center = self.index["water_bodies"][1]["centerline_pixel_x_y_surface"]
        straight = raster_line(self.config["river"]["points"],1)
        bends = sum(not straight[y,x] for x,y,_ in center)
        self.assertGreater(bends,len(center)*.3)
        river = self.layers["coast_river"] & 2 != 0
        banks = neighbors(river) & ~river & (self.land==1)
        self.assertGreater(np.median(self.layers["height"][banks]),np.median(self.layers["height"][river]))
        region = self.layers["region"]
        for a,b in [(3,7),(4,5)]:
            edge = (region==a) & neighbors(region==b)
            ys,xs = np.nonzero(edge)
            self.assertGreater(len(np.unique(ys)),10)
            self.assertGreater(len(np.unique(xs)),10)

    def test_anchors_distances_and_routes(self):
        spawn = self.index["facilities"][0]
        self.assertEqual(-80*spawn["xz"][1]/16384,40)
        for facility in self.index["facilities"]:
            x,y = pixel(facility["xz"])
            self.assertEqual(int(self.layers["region"][y,x]),facility["region"],facility["id"])
            distance = math.dist(spawn["xz"],facility["xz"])
            low, high = facility["distance_range"]
            self.assertTrue(low <= distance <= high,facility["id"])
            self.assertEqual(facility["surface_y"],facility["actual_surface_y"],facility["id"])
        for route in self.index["routes"]:
            self.assertTrue(np.all(self.land[raster_line(route["points"],5)]==1),route["id"])
        rail = self.index["routes"][0]["points"]
        for name in ["spawn","mining","industry","coastal_industry"]:
            anchor = next(f["xz"] for f in self.index["facilities"] if f["id"]==name)
            self.assertIn(anchor,rail)

    def test_river_reaches_ocean_and_water_surfaces(self):
        flags = self.layers["coast_river"]
        river = flags & 2 != 0
        self.assertTrue(np.any(river & neighbors(self.land==0)))
        # 河川の全セルを河口から遡って検査し、内陸で切れた支流を見逃さない。
        visited = river & neighbors(self.land==0)
        queue = deque(zip(*np.nonzero(visited)))
        while queue:
            y,x = queue.popleft()
            for a,b in [(y-1,x),(y+1,x),(y,x-1),(y,x+1)]:
                if 0 <= a < SIZE and 0 <= b < SIZE and river[a,b] and not visited[a,b]:
                    visited[a,b]=True
                    queue.append((a,b))
        self.assertTrue(np.all(visited[river]))
        surface = np.full((SIZE,SIZE),-999,np.int16)
        for y,x0,x1,level in self.index["water_bodies"][1]["surface_spans_y_x0_x1ex_y"]:
            surface[y,x0:x1] = level
        # 山中の渓谷は格子幅16より細いため、河床はJavaの実列で検査する。
        z=np.arange(SIZE)[:,None]*16-16384
        raster_bed=river & (z>=self.config["river"]["points"][0][1]+1300)
        self.assertTrue(np.all(surface[raster_bed] > self.layers["height"][raster_bed].astype(np.int16)-64))
        self.assertTrue(np.all(surface[river]!=-999))
        center = self.index["water_bodies"][1]["centerline_pixel_x_y_surface"]
        self.assertTrue(all(a[2]>=b[2] for a,b in zip(center,center[1:])))
        self.assertEqual(center[-1][2],63)

    def test_priority_flood_accumulation_and_outlets(self):
        heights = np.array([[0,0,0,0,0],[0,5,5,5,0],[0,5,1,5,0],[0,5,5,5,0],[0,0,0,0,0]],np.int16)
        land = heights>0
        parent, area = drainage(heights,land)
        self.assertEqual(heights[2,2],5)
        self.assertEqual(area[~land].sum(),9)
        for pos in np.flatnonzero(land):
            visited = set()
            while land.ravel()[pos]:
                self.assertNotIn(pos,visited)
                visited.add(pos)
                pos = parent.ravel()[pos]
                self.assertGreaterEqual(pos,0)

    def test_regeneration_is_identical(self):
        with tempfile.TemporaryDirectory() as directory:
            regenerated = Path(directory)
            with chdir(ROOT):
                generate(Path("tools/fixed-map/input"),regenerated)
            for name in [*RESOURCE_FILES,"manifest.json"]:
                self.assertEqual(sha(OUTPUT/name),sha(regenerated/name),name)

    def test_new_version_cli_and_preset(self):
        with tempfile.TemporaryDirectory(dir=TOOL) as directory:
            work = Path(directory)
            source = work/"input"
            shutil.copytree(TOOL/"input",source)
            config = json.loads((source/"layout.json").read_text(encoding="utf-8"))
            config["map_version"] = "earth_002"
            write_json(source/"layout.json",config)
            project = work/"project"
            preset = project/"modules/world/src/main/resources/data/ashen_frontier/worldgen/world_preset/fixed_map.json"
            preset.parent.mkdir(parents=True)
            shutil.copyfile(ROOT/preset.relative_to(project),preset)
            args = ["--input",source.relative_to(ROOT).as_posix()]
            with chdir(ROOT), patch("generate.ROOT",project):
                main([*args,"--update-preset"])
                main([*args,"--validate"])
            output = project/MAP_RESOURCES/"earth_002"
            version = json.loads(preset.read_text(encoding="utf-8"))["dimensions"]["minecraft:overworld"]["generator"]["map"]
            self.assertEqual(version["map_version"],"earth_002")
            self.assertEqual(version["manifest_hash"],sha(output/"manifest.json"))
            generator = json.loads(preset.read_text(encoding="utf-8"))["dimensions"]["minecraft:overworld"]["generator"]
            self.assertEqual(generator["biome_source"]["map"],version)
            for name in ["index.json","geology_profiles.json"]:
                self.assertEqual(json.loads((output/name).read_text(encoding="utf-8"))["map_version"],"earth_002")

    def test_manifest_write_failure_preserves_previous_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/"map"
            shutil.copytree(OUTPUT,output)
            before = {p.name:sha(p) for p in output.iterdir()}
            def fail_manifest(path,value):
                if path.name=="manifest.json":
                    raise OSError("manifest書込失敗の試験")
                write_json(path,value)
            with patch("generate.write_json",side_effect=fail_manifest):
                with self.assertRaisesRegex(OSError,"manifest書込"):
                    generate(TOOL/"input",output)
            self.assertEqual(before,{p.name:sha(p) for p in output.iterdir()})
            self.assertEqual([p.name for p in output.parent.iterdir()],["map"])

    def test_failed_promotion_restores_previous_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/"map"
            shutil.copytree(OUTPUT,output)
            before = {p.name:sha(p) for p in output.iterdir()}
            rename = Path.rename
            def copy_assets(source,target):
                shutil.copytree(OUTPUT,target)
                return {},{"map_version":self.config["map_version"]}
            def fail_promotion(path,target):
                if path.name=="map" and Path(target)==output:
                    raise OSError("出力置換失敗の試験")
                return rename(path,target)
            with patch("generate.generate_assets",side_effect=copy_assets),patch.object(Path,"rename",fail_promotion):
                with self.assertRaisesRegex(OSError,"出力置換"):
                    generate(TOOL/"input",output)
            self.assertEqual(before,{p.name:sha(p) for p in output.iterdir()})
            self.assertEqual([p.name for p in output.parent.iterdir()],["map"])

    def test_output_with_unrelated_files_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/"map"
            output.mkdir()
            (output/"notes.txt").write_text("保全する文書",encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"生成資産以外"):
                generate(TOOL/"input",output)
            self.assertEqual((output/"notes.txt").read_text(encoding="utf-8"),"保全する文書")

    def test_geology_profiles_cover_raster_and_layers(self):
        definition = json.loads((OUTPUT/"geology_profiles.json").read_text(encoding="utf-8"))
        self.assertEqual(definition["profile_version"],self.index["geology_profile_version"])
        self.assertEqual(definition["map_version"],self.config["map_version"])
        self.assertTrue(set(np.unique(self.layers["geology"])) <= {int(key) for key in definition["profiles"]})
        rocks = set(definition["catalog"]["rock_ids"]) | {"minecraft:stone","minecraft:granite","minecraft:sandstone"}
        for profile in definition["profiles"].values():
            self.assertIn(profile["basement"],rocks)
            self.assertTrue(-127 <= profile["reference_y"] <= 575)
            self.assertEqual(len(profile["dip"]["gradient_xz"]),2)
            self.assertEqual(len(profile["dip"]["anchor_xz"]),2)
            for fault in profile["faults"]:
                self.assertEqual(len(fault["point_xz"]),2)
                self.assertEqual(len(fault["normal_xz"]),2)
                self.assertNotEqual(fault["throw_blocks"],0)
            for layer in profile["layers_top_to_bottom"]:
                self.assertIn(layer["rock"],rocks)
                self.assertTrue(1 <= layer["thickness_blocks"][0] <= layer["thickness_blocks"][1])

    def test_geology_version_and_missing_profile_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)/"map"
            shutil.copytree(OUTPUT,target)
            definition = json.loads((target/"geology_profiles.json").read_text(encoding="utf-8"))
            manifest = json.loads((target/"manifest.json").read_text(encoding="utf-8"))
            definition["map_version"] = "other_001"
            write_json(target/"geology_profiles.json",definition)
            manifest["files"]["geology_profiles.json"] = sha(target/"geology_profiles.json")
            write_json(target/"manifest.json",manifest)
            with self.assertRaisesRegex(ValueError,"地質プロファイルの版"):
                validate(target,self.config["map_version"])
            definition["map_version"] = self.config["map_version"]
            del definition["profiles"]["3"]
            write_json(target/"geology_profiles.json",definition)
            manifest["files"]["geology_profiles.json"] = sha(target/"geology_profiles.json")
            write_json(target/"manifest.json",manifest)
            with self.assertRaisesRegex(ValueError,"未定義"):
                validate(target,self.config["map_version"])

    def test_wrong_version_missing_layers_and_corruption(self):
        with self.assertRaisesRegex(ValueError,"版"):
            validate(OUTPUT,"other_001")
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)/"map"
            shutil.copytree(OUTPUT,target)
            manifest = json.loads((target/"manifest.json").read_text(encoding="utf-8"))
            del manifest["files"]["soil.png"]
            write_json(target/"manifest.json",manifest)
            with self.assertRaisesRegex(ValueError,"レイヤー"):
                validate(target,self.config["map_version"])
            shutil.copyfile(OUTPUT/"manifest.json",target/"manifest.json")
            (target/"soil.png").unlink()
            with self.assertRaisesRegex(ValueError,"soil.png"):
                validate(target,self.config["map_version"])
            (target/"soil.png").write_bytes(b"broken")
            with self.assertRaisesRegex(ValueError,"soil.png"):
                validate(target,self.config["map_version"])

    def test_invalid_input_values_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            shutil.copyfile(TOOL/"input/layout.json",target/"layout.json")
            bad = self.land.copy()
            bad[0,0] = 5
            Image.fromarray(bad).save(target/"land.png")
            with self.assertRaisesRegex(ValueError,"陸海入力"):
                load_inputs(target)


if __name__ == "__main__":
    unittest.main()
