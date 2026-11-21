"""配置図の制作契約を、同梱資産と再生成の両方で検査する。"""

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
                      drainage, generate, load_inputs, main, neighbors, pixel,
                      raster_line, sha, validate, write_json)


class FixedMapTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config, cls.land = load_inputs(TOOL / "input")
        cls.layers = {name: np.array(Image.open(OUTPUT/f"{name}.png")) for name in LAYERS}
        cls.index = json.loads((OUTPUT/"index.json").read_text(encoding="utf-8"))

    def test_dimensions_types_and_hashes(self):
        validate(OUTPUT,self.config["map_version"])
        self.assertLessEqual(self.layers["height"].max(),383)
        self.assertEqual(self.layers["height"].dtype,np.uint16)
        for name in LAYERS[1:]:
            self.assertEqual(self.layers[name].dtype,np.uint8)

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
        self.assertTrue(np.all(surface[river] > self.layers["height"][river].astype(np.int16)-64))
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
