"""earth_002の共有契約だけを検査する。本番の標高や配置図は制作しない。"""

import argparse
import json
import math
from collections import deque
from pathlib import Path


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def integer(value):
    require(type(value) is int and -2147483648 <= value <= 2147483647, "整数値が必要です")
    return value


def number(value):
    require(type(value) in (int, float) and math.isfinite(value), "有限の数値が必要です")
    return value


def point(values):
    require(len(values) == 2 and all(type(v) is int and -16384 <= v < 16384 for v in values), "座標の範囲・列数")
    return tuple(values)


def inside(outline, x, z):
    result = False
    for a, b in zip(outline, outline[1:] + outline[:1]):
        if (a[1] > z) != (b[1] > z) and x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]:
            result = not result
    return result


def edge_distance(outline, x, z):
    result = math.inf
    for a, b in zip(outline, outline[1:] + outline[:1]):
        dx, dz = b[0] - a[0], b[1] - a[1]
        require(dx * dx + dz * dz > 0, "外形の重複頂点")
        t = max(0, min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / (dx * dx + dz * dz)))
        result = min(result, math.hypot(x - a[0] - t * dx, z - a[1] - t * dz))
    return result


def validate_landform(doc, version="earth_002"):
    require(doc["map_version"] == version and integer(doc["format_version"]) == 1
            and integer(doc["landform_schema_version"]) == 1, "地形の版・schema")
    validate_details(doc)
    ids, controls = set(), set()
    for unit in doc["units"]:
        uid = integer(unit["id"])
        require(uid > 0 and uid not in ids, "地形ID")
        ids.add(uid)
        base, typical, peak = (integer(unit[k]) for k in ["base_y", "typical_y", "peak_y"])
        require(64 <= base <= typical <= peak <= 520, "地形標高")
        require(unit["kind"] in {"range", "massif", "hills", "plateau", "basin", "lowland", "detail"}, "地形種別")
        outline = [point(p) for p in unit["boundary"]]
        require(len(outline) >= 3 or unit["kind"] == "lowland" and not outline, "外形不足")
        require(bool(unit["pins"]), "制御点不足")
        for pin in unit["pins"]:
            x, z = point(pin["xz"])
            require(pin["id"] not in controls, "制御点ID重複")
            controls.add(pin["id"])
            require(integer(pin["base_y"]) >= 64 and 64 <= integer(pin["control_y"]) <= peak
                    and number(pin["section_half_width"]) >= 0
                    and (pin["role"] != "ridge" or pin["section_half_width"] > 0), "制御高・片幅")
            require(pin["role"] in {"ridge", "surface", "valley_floor", "crater_floor", "ice_surface"}, "制御高の役割")
            require(not outline or inside(outline, x, z), "制御点が包絡外")
    require(bool(ids), "地形区分不足")


def check_path(rows, columns):
    require(bool(rows), "地形の点列不足")
    for row in rows:
        require(len(row) == columns, "地形の列数")
        point(row[:2])
        if columns >= 3:
            require(64 <= integer(row[2]) <= 520, "地形の標高")


def validate_details(doc):
    for key in ["volcanoes", "divides", "caldera_rim", "caldera_drain", "snowline_latitude_y"]:
        require(type(doc.get(key)) is list and bool(doc[key]), "地形の必須配列: " + key)
    volcano_ids = set()
    for v in doc["volcanoes"]:
        require(type(v["id"]) is str, "火山IDの型")
        require(v["id"] not in volcano_ids, "火山IDの重複")
        volcano_ids.add(v["id"])
        point(v["xz"])
        require(64 <= integer(v["base_y"]) <= integer(v["rim_y"]) <= 520 and number(v["width"]) > 0, "火山の標高・幅")
    for path in doc["divides"]:
        require(len(path) >= 2, "分水界の点数")
        check_path(path, 2)
    glacier = doc["glacier"]
    require(type(glacier["id"]) is str and bool(glacier["id"].strip()), "氷河ID")
    require(len(glacier["points"]) >= 2, "氷河の点数")
    check_path(glacier["points"], 5)
    for row in glacier["points"]:
        require(number(row[3]) > 0 and 0 < number(row[4]) <= 8 and row[2] - row[4] >= 64, "氷河の幅・厚さ")
    check_path(glacier["moraine"], 3)
    require(len(glacier["outwash"]) >= 2, "氷河排水の点数")
    check_path(glacier["outwash"], 3)
    require(len(doc["caldera_rim"]) >= 3 and len(doc["caldera_drain"]) >= 2, "カルデラの点数")
    check_path(doc["caldera_rim"], 3)
    check_path(doc["caldera_drain"], 3)
    for uid in range(31, 41):
        rule = doc["details_rules"].get(str(uid))
        require(type(rule) is str and bool(rule.strip()), "細地形の規則: " + str(uid))
    previous_latitude = 81
    require(len(doc["snowline_latitude_y"]) >= 2, "雪線の点数")
    for row in doc["snowline_latitude_y"]:
        require(len(row) == 2, "雪線の列数")
        latitude, height = number(row[0]), integer(row[1])
        require(0 <= latitude <= 80 and latitude < previous_latitude and 64 <= height <= 550, "雪線の緯度・標高")
        previous_latitude = latitude
    require(integer(doc["snowline_below_40_min_y"]) >= 550 and number(doc["snow_continuity_scale_blocks"]) > 0, "雪線下限・連続変動幅")
    offsets = doc["snowline_offsets_y"]
    require(-20 <= integer(offsets["windward"]) <= 0 and 0 <= integer(offsets["leeward"]) <= 35
            and -24 <= integer(offsets["north_facing"]) <= 0 and 0 <= integer(offsets["south_facing"]) <= 24, "雪線補正")


def validate_hydrology(doc, version="earth_002"):
    require(doc["map_version"] == version and integer(doc["hydrology_schema_version"]) == 1
            and doc["coordinate_units"] == "block", "水系の版・schema・単位")
    inventories = {}
    keys = set()
    for item in doc["inventories"]:
        iid = integer(item["id"])
        require(iid in {1, 2, 3, 4} and iid not in inventories and item["key"] not in keys, "水量ID重複・範囲")
        require(item["key"] == {1: "ocean", 2: "nw05", 3: "nw06", 4: "nw16"}[iid]
                and integer(item["quality_id"]) == (8 if iid == 1 else 4)
                and type(item["finite"]) is bool and item["finite"] == (iid != 1), "水質・有限性")
        inventories[iid] = item
        keys.add(item["key"])
    require(set(inventories) == {1, 2, 3, 4}, "水量ID不足")
    nodes = {}
    for node in doc["nodes"]:
        require(node["id"] not in nodes and node["kind"] in
                {"source", "confluence", "lake_inlet", "lake_outlet", "ocean_outlet"}, "ノードID・種別")
        point(node["xz"])
        require(63 <= integer(node["water_y"]) <= 520, "ノード水位")
        nodes[node["id"]] = node
    ids = set(nodes)
    edges = {n: [] for n in nodes}
    incoming, outgoing, inventory_at = dict.fromkeys(nodes, 0), dict.fromkeys(nodes, 0), {}

    def assign(node, iid):
        require(node not in inventory_at or inventory_at[node] == iid, "合流の水量ID不一致")
        inventory_at[node] = iid

    def edge(a, b, iid):
        require(a in nodes and b in nodes, "未定義ノード")
        edges[a].append(b)
        incoming[b] += 1
        outgoing[a] += 1
        assign(a, iid)
        assign(b, iid)

    reaches = {}
    for reach in doc["reaches"]:
        iid = integer(reach["inventory_id"])
        require(reach["id"] not in ids and iid in {2, 3, 4}, "reach ID・水量ID")
        ids.add(reach["id"])
        rows = reach["profile_x_z_surface_full_width_depth"]
        require(len(rows) >= 2, "断面不足")
        for i, row in enumerate(rows):
            require(len(row) == 5, "断面の列数")
            point(row[:2])
            require(63 <= integer(row[2]) <= 520 and number(row[3]) > 0
                    and number(row[4]) > 0 and row[2] - row[4] >= -127, "河川断面")
            if i:
                require(rows[i-1][2] >= row[2] and rows[i-1][:2] != row[:2], "逆勾配・重複断面")
        for key, row in [("from_node", rows[0]), ("to_node", rows[-1])]:
            require(reach[key] in nodes and row[:3] == nodes[reach[key]]["xz"] + [nodes[reach[key]]["water_y"]], "合流・端点の座標水位不一致")
        edge(reach["from_node"], reach["to_node"], iid)
        reaches[reach["id"]] = reach
    lake_inlets, lake_outlets = set(), set()
    for lake in doc["lakes"]:
        iid = integer(lake["inventory_id"])
        require(lake["id"] not in ids and iid in {2, 3, 4}, "湖ID・水量ID")
        ids.add(lake["id"])
        outline = [point(p) for p in lake["outline_x_z"]]
        require(len(outline) >= 3 and len(set(outline)) == len(outline), "湖外形")
        surface, bed = integer(lake["surface_y"]), integer(lake["outlet_bed_y"])
        require(0 < number(lake["depth_min_interior"]) <= number(lake["depth_max"])
                and surface - lake["depth_max"] >= -127 and bed < surface
                and number(lake["outlet_full_width"]) > 0, "湖の深さ・出口幅")
        require(lake["outlet_node"] not in lake_outlets, "湖出口の重複")
        lake_outlets.add(lake["outlet_node"])
        outlet = nodes[lake["outlet_node"]]
        require(outlet["kind"] == "lake_outlet" and outlet["water_y"] == lake["surface_y"]
                and inside(outline, *outlet["xz"])
                and edge_distance(outline, *outlet["xz"]) > lake["outlet_full_width"]/2, "湖出口の正幅接続")
        require(lake["inlet_nodes"] and len(set(lake["inlet_nodes"])) == len(lake["inlet_nodes"]), "湖入口")
        for inlet_id in lake["inlet_nodes"]:
            require(inlet_id not in lake_inlets, "湖入口の重複")
            lake_inlets.add(inlet_id)
            inlet = nodes[inlet_id]
            incoming_reaches = [r for r in reaches.values() if r["to_node"] == inlet_id]
            require(inlet["kind"] == "lake_inlet" and inlet["water_y"] == lake["surface_y"]
                    and len(incoming_reaches) == 1 and inside(outline, *inlet["xz"])
                    and edge_distance(outline, *inlet["xz"]) > incoming_reaches[0]["profile_x_z_surface_full_width_depth"][-1][3]/2,
                    "湖入口の正幅接続・座標水位")
            edge(inlet_id, lake["outlet_node"], iid)
        starts = [r for r in reaches.values() if r["from_node"] == lake["outlet_node"]]
        require(len(starts) == 1, "湖出口の流路")
        row = starts[0]["profile_x_z_surface_full_width_depth"][0]
        require(row[3] == lake["outlet_full_width"] and row[2] - row[4] == lake["outlet_bed_y"], "湖出口の幅・床不一致")
    pending = incoming.copy()
    queue = deque(n for n in nodes if not pending[n])
    visited = 0
    while queue:
        n = queue.popleft()
        visited += 1
        for target in edges[n]:
            pending[target] -= 1
            if not pending[target]:
                queue.append(target)
    require(visited == len(nodes), "水系の閉路")
    for n, node in nodes.items():
        a, b = incoming[n], outgoing[n]
        require((a == 0 and b == 1) if node["kind"] == "source" else
                (a >= 2 and b == 1) if node["kind"] == "confluence" else
                (a == 1 and b == 0) if node["kind"] == "ocean_outlet" else
                (n in lake_outlets and a >= 1 and b == 1) if node["kind"] == "lake_outlet" else
                (n in lake_inlets and a == b == 1), "ノードの接続本数")
    require(len(doc["outlets"]) == 3 and {o["inventory_id"] for o in doc["outlets"]} == {2, 3, 4}, "淡水流域ごとの河口")
    outlet_ids = set()
    for o in doc["outlets"]:
        node = nodes[o["node_id"]]
        require(node["kind"] == "ocean_outlet" and node["water_y"] == 63 and integer(o["ocean_inventory_id"]) == 1
                and integer(o["inventory_id"]) == inventory_at[node["id"]] and node["id"] not in outlet_ids, "河口の水位・水量ID")
        outlet_ids.add(node["id"])
    require(outlet_ids == {n for n in nodes if nodes[n]["kind"] == "ocean_outlet"}, "河口定義不足")
    require(set(inventory_at.values()) == {2, 3, 4}, "淡水流域不足")


def validate(directory, version="earth_002"):
    directory = Path(directory)
    validate_landform(json.loads((directory/"landform.json").read_text(encoding="utf-8")), version)
    validate_hydrology(json.loads((directory/"hydrology.json").read_text(encoding="utf-8")), version)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    args = parser.parse_args()
    validate(args.input)
    print("earth_002: 地形・水系契約の検査成功（地形制作・通常プリセット変更なし）")
