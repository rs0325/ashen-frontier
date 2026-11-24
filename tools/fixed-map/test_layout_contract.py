"""Javaと同じ承認済みfixtureに対する正常系・破損系の制作検査。"""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from layout_contract import validate, validate_landform, validate_hydrology

FIXTURE = Path(__file__).parent/"fixtures/earth_002"


class LayoutContractTest(unittest.TestCase):
    def setUp(self):
        self.water = json.loads((FIXTURE/"hydrology.json").read_text(encoding="utf-8"))
        self.land = json.loads((FIXTURE/"landform.json").read_text(encoding="utf-8"))

    def test_approved_controls_and_network(self):
        validate(FIXTURE)
        self.assertEqual(30, len(self.land["units"]))
        self.assertEqual(56, sum(len(u["pins"]) for u in self.land["units"]))
        self.assertEqual(12, len(self.water["nodes"]))
        self.assertEqual(8, len(self.water["reaches"]))
        self.assertEqual(3, len(self.water["outlets"]))
        self.assertEqual([-6510, -10300], self.water["nodes"][2]["xz"])
        self.assertEqual([125, 110, 85], [p[2] for p in self.water["reaches"][6]["profile_x_z_surface_full_width_depth"][1:4]])

    def test_shared_level_must_not_be_silently_clamped(self):
        self.water["reaches"][4]["profile_x_z_surface_full_width_depth"][-1][2] = 137
        with self.assertRaisesRegex(ValueError, "座標水位"):
            validate_hydrology(self.water)

    def test_duplicate_node_and_inventory(self):
        for field in ["nodes", "inventories"]:
            doc = copy.deepcopy(self.water)
            doc[field].append(copy.deepcopy(doc[field][0]))
            with self.assertRaises(ValueError):
                validate_hydrology(doc)

    def test_cycle_is_detected_even_with_nonincreasing_levels(self):
        self.water["nodes"].append({"id": "loop", "kind": "confluence", "xz": [0, 0], "water_y": 100})
        self.water["reaches"].append({"id": "cycle", "from_node": "loop", "to_node": "loop", "inventory_id": 2,
            "profile_x_z_surface_full_width_depth": [[0, 0, 100, 2, 1], [4, 0, 100, 2, 1], [0, 0, 100, 2, 1]]})
        with self.assertRaisesRegex(ValueError, "閉路"):
            validate_hydrology(self.water)

    def test_point_contact_lake_and_other_inventory_fail(self):
        doc = copy.deepcopy(self.water)
        doc["nodes"][2]["xz"][0] = -6490
        doc["reaches"][1]["profile_x_z_surface_full_width_depth"][0][0] = -6490
        with self.assertRaisesRegex(ValueError, "正幅"):
            validate_hydrology(doc)
        self.water["reaches"][4]["inventory_id"] = 3
        with self.assertRaisesRegex(ValueError, "水量ID"):
            validate_hydrology(self.water)

    def test_unknown_schema_fraction_and_wrong_map_are_rejected(self):
        for value in [2, 1.5, 1.0, "1", True]:
            self.water["hydrology_schema_version"] = value
            with self.assertRaises(ValueError):
                validate_hydrology(self.water)
        self.land["map_version"] = "earth_001"
        with self.assertRaises(ValueError):
            validate_landform(self.land)

    def test_control_roles_and_widths(self):
        self.land["units"][0]["pins"][0]["section_half_width"] = -1
        with self.assertRaises(ValueError):
            validate_landform(self.land)
        self.land["units"][0]["pins"][0]["section_half_width"] = 100
        self.land["units"][0]["pins"][0]["xz"] = [0, 0]
        with self.assertRaisesRegex(ValueError, "包絡外"):
            validate_landform(self.land)

    def test_multiple_inlets_and_orphan_lake_node(self):
        doc = copy.deepcopy(self.water)
        doc["nodes"].extend([
            {"id": "extra_source", "kind": "source", "xz": [-7400, -11000], "water_y": 160},
            {"id": "extra_inlet", "kind": "lake_inlet", "xz": [-7100, -10400], "water_y": 145}])
        doc["reaches"].append({"id": "extra_reach", "from_node": "extra_source", "to_node": "extra_inlet",
            "inventory_id": 2, "profile_x_z_surface_full_width_depth": [[-7400, -11000, 160, 2, 1], [-7100, -10400, 145, 8, 2]]})
        doc["lakes"][0]["inlet_nodes"].append("extra_inlet")
        validate_hydrology(doc)
        self.water["nodes"][3]["kind"] = "lake_outlet"
        with self.assertRaisesRegex(ValueError, "接続本数"):
            validate_hydrology(self.water)

    def test_inlet_must_overlap_by_its_terminal_half_width(self):
        for x in [-7450, -7449]:
            with self.subTest(x=x):
                doc = copy.deepcopy(self.water)
                doc["nodes"][1]["xz"] = [x, -10400]
                rows = doc["reaches"][0]["profile_x_z_surface_full_width_depth"]
                rows[-2] = [-7900, -10400, 155, 8, 2]
                rows[-1] = [x, -10400, 145, 12, 2]
                with self.assertRaisesRegex(ValueError, "湖入口の正幅"):
                    validate_hydrology(doc)

    def test_boolean_width_and_decimal_lake_level_are_rejected(self):
        self.water["reaches"][0]["profile_x_z_surface_full_width_depth"][0][3] = True
        with self.assertRaises(ValueError):
            validate_hydrology(self.water)
        self.water["reaches"][0]["profile_x_z_surface_full_width_depth"][0][3] = 2
        self.water["lakes"][0]["surface_y"] = 145.0
        with self.assertRaises(ValueError):
            validate_hydrology(self.water)

    def test_additional_landform_definitions_are_required_and_bounded(self):
        for key in ["volcanoes", "snowline_latitude_y"]:
            with self.subTest(key=key):
                doc = copy.deepcopy(self.land)
                del doc[key]
                with self.assertRaisesRegex(ValueError, key):
                    validate_landform(doc)
        self.land["volcanoes"][0]["rim_y"] = 9999
        with self.assertRaisesRegex(ValueError, "火山の標高"):
            validate_landform(self.land)

    def test_preview_requires_production_input_and_keeps_normal_preset(self):
        from generate import generate, main, ROOT
        preset = ROOT/"modules/world/src/main/resources/data/ashen_frontier/worldgen/world_preset/fixed_map.json"
        before = hashlib.sha256(preset.read_bytes()).hexdigest()
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            (p/"layout.json").write_text('{"map_version":"earth_002","format_version":1}', encoding="utf-8")
            for option in [[], ["--update-preset"]]:
                with self.assertRaises(SystemExit):
                    main(["--input", str(p), "--output", str(p/"output"), *option])
                self.assertFalse((p/"output").exists())
            with self.assertRaises(FileNotFoundError):
                generate(p, p/"output")
            self.assertFalse((p/"output").exists())
        self.assertEqual(before, hashlib.sha256(preset.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
