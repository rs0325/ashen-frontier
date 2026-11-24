package io.github.rs0325.ashenfrontier.world;

import static io.github.rs0325.ashenfrontier.world.LayoutJson.integer;
import static io.github.rs0325.ashenfrontier.world.LayoutJson.number;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 生成時の固定水系。消費後の残水量やkg換算は保持せず、#55の保存状態と分離する。 */
public final class HydrologyLayout {
    public record Inventory(int id, String key, int qualityId, boolean finite) {}
    public record Node(String id, String kind, int x, int z, int waterY) {}
    /** 制作値は全幅。RiverCourse内部へ渡すときだけhalfWidth()を使う。 */
    public record Profile(int x, int z, int waterY, double fullWidth, double depth) {
        public double halfWidth() { return fullWidth / 2; }
    }
    public record Reach(String id, String from, String to, int inventoryId, List<Profile> profile) {
        public Reach { profile = List.copyOf(profile); }
    }
    public record Point(int x, int z) {}
    public record Lake(String id, int inventoryId, int surfaceY, List<Point> outline, List<String> inlets,
                       String outlet, double outletFullWidth, double depthMin, double depthMax, int outletBedY) {
        public Lake { outline = List.copyOf(outline); inlets = List.copyOf(inlets); }
    }
    public record Outlet(String node, int inventoryId, int oceanInventoryId) {}

    private final Map<Integer, Inventory> inventories;
    private final Map<String, Node> nodes;
    private final Map<String, Reach> reaches;
    private final Map<String, Lake> lakes;
    private final List<Outlet> outlets;

    private HydrologyLayout(JsonObject document) {
        var inventories = new HashMap<Integer, Inventory>();
        var keys = new HashSet<String>();
        for (var value : document.getAsJsonArray("inventories")) {
            var v = value.getAsJsonObject();
            check(v.get("finite").isJsonPrimitive() && v.get("finite").getAsJsonPrimitive().isBoolean(), "有限性の型");
            var inventory = new Inventory(integer(v, "id"), v.get("key").getAsString(),
                    integer(v, "quality_id"), v.get("finite").getAsBoolean());
            check(inventory.id >= 1 && inventory.id <= 4 && keys.add(inventory.key)
                    && inventories.putIfAbsent(inventory.id, inventory) == null, "水量IDの重複・範囲");
            check(inventory.key.equals(Map.of(1, "ocean", 2, "nw05", 3, "nw06", 4, "nw16").get(inventory.id))
                    && inventory.qualityId == (inventory.id == 1 ? 8 : 4)
                    && inventory.finite == (inventory.id != 1), "水質・有限性");
        }
        check(inventories.keySet().equals(Set.of(1, 2, 3, 4)), "水量IDの不足");
        this.inventories = Map.copyOf(inventories);
        var nodes = new HashMap<String, Node>();
        for (var value : document.getAsJsonArray("nodes")) {
            var v = value.getAsJsonObject();
            var p = point(v.getAsJsonArray("xz"));
            var node = new Node(v.get("id").getAsString(), v.get("kind").getAsString(), p.x, p.z, integer(v, "water_y"));
            check(Set.of("source", "confluence", "lake_inlet", "lake_outlet", "ocean_outlet").contains(node.kind), "未知のノード種別");
            check(node.waterY >= 63 && node.waterY <= 520 && nodes.putIfAbsent(node.id, node) == null, "ノードID・水位");
        }
        this.nodes = Map.copyOf(nodes);
        var reaches = new HashMap<String, Reach>();
        var ids = new HashSet<String>(nodes.keySet());
        for (var value : document.getAsJsonArray("reaches")) {
            var v = value.getAsJsonObject();
            var profile = new ArrayList<Profile>();
            for (var row : v.getAsJsonArray("profile_x_z_surface_full_width_depth")) {
                var a = row.getAsJsonArray();
                check(a.size() == 5, "河川断面の列数");
                int x = integer(a.get(0)), z = integer(a.get(1));
                int y = integer(a.get(2));
                double width = number(a.get(3)), depth = number(a.get(4));
                check(MapCoordinates.contains(x, z) && y >= 63 && y <= 520
                        && Double.isFinite(width) && width > 0 && Double.isFinite(depth) && depth > 0 && y - depth >= -127, "河川断面の範囲");
                if (!profile.isEmpty()) {
                    var previous = profile.getLast();
                    check(previous.waterY >= y && (previous.x != x || previous.z != z), "逆勾配・重複断面");
                }
                profile.add(new Profile(x, z, y, width, depth));
            }
            var reach = new Reach(v.get("id").getAsString(), v.get("from_node").getAsString(),
                    v.get("to_node").getAsString(), integer(v, "inventory_id"), profile);
            check(profile.size() >= 2 && ids.add(reach.id) && inventories.containsKey(reach.inventoryId) && reach.inventoryId != 1, "reach ID・水量ID");
            check(matches(profile.getFirst(), nodes.get(reach.from)) && matches(profile.getLast(), nodes.get(reach.to)), "合流・端点の座標水位不一致");
            reaches.put(reach.id, reach);
        }
        this.reaches = Map.copyOf(reaches);
        var lakes = new HashMap<String, Lake>();
        for (var value : document.getAsJsonArray("lakes")) {
            var v = value.getAsJsonObject();
            var outline = new ArrayList<Point>();
            for (var row : v.getAsJsonArray("outline_x_z")) outline.add(point(row.getAsJsonArray()));
            var inlets = new ArrayList<String>();
            for (var inlet : v.getAsJsonArray("inlet_nodes")) inlets.add(inlet.getAsString());
            var lake = new Lake(v.get("id").getAsString(), integer(v, "inventory_id"), integer(v, "surface_y"),
                    outline, inlets, v.get("outlet_node").getAsString(), number(v.get("outlet_full_width")),
                    number(v.get("depth_min_interior")), number(v.get("depth_max")), integer(v, "outlet_bed_y"));
            check(ids.add(lake.id) && inventories.containsKey(lake.inventoryId) && lake.inventoryId != 1, "湖ID・水量ID");
            check(outline.size() >= 3 && new HashSet<>(outline).size() == outline.size()
                    && !inlets.isEmpty() && new HashSet<>(inlets).size() == inlets.size(), "湖の外形・入口");
            check(Double.isFinite(lake.depthMin) && Double.isFinite(lake.depthMax) && lake.depthMin > 0
                    && lake.depthMax >= lake.depthMin && lake.surfaceY - lake.depthMax >= -127
                    && lake.outletBedY < lake.surfaceY && Double.isFinite(lake.outletFullWidth) && lake.outletFullWidth > 0, "湖の深さ・出口幅");
            var outlet = nodes.get(lake.outlet);
            check(outlet != null && outlet.kind.equals("lake_outlet") && outlet.waterY == lake.surfaceY
                    && inside(outline, outlet.x, outlet.z) && edgeDistance(outline, outlet.x, outlet.z) > lake.outletFullWidth / 2, "湖出口の正幅接続");
            for (var id : inlets) {
                var inlet = nodes.get(id);
                var incoming = reaches.values().stream().filter(r -> r.to.equals(id)).toList();
                check(inlet != null && inlet.kind.equals("lake_inlet") && inlet.waterY == lake.surfaceY
                        && incoming.size() == 1 && inside(outline, inlet.x, inlet.z)
                        && edgeDistance(outline, inlet.x, inlet.z) > incoming.getFirst().profile.getLast().halfWidth(),
                        "湖入口の正幅接続・座標水位");
            }
            lakes.put(lake.id, lake);
        }
        this.lakes = Map.copyOf(lakes);
        var outlets = new ArrayList<Outlet>();
        var outletIds = new HashSet<String>();
        for (var value : document.getAsJsonArray("outlets")) {
            var v = value.getAsJsonObject();
            var outlet = new Outlet(v.get("node_id").getAsString(), integer(v, "inventory_id"), integer(v, "ocean_inventory_id"));
            var node = nodes.get(outlet.node);
            check(node != null && node.kind.equals("ocean_outlet") && node.waterY == 63
                    && outlet.oceanInventoryId == 1 && inventories.containsKey(outlet.inventoryId)
                    && outlet.inventoryId != 1 && outletIds.add(outlet.node), "河口の水位・水量ID");
            outlets.add(outlet);
        }
        check(outlets.size() == 3 && outlets.stream().map(Outlet::inventoryId).collect(java.util.stream.Collectors.toSet()).equals(Set.of(2, 3, 4)), "淡水流域ごとの河口");
        this.outlets = List.copyOf(outlets);
        validateGraph();
    }

    /** map版・schema・接続・水位を検証する。不正入力は理由付きIllegalStateExceptionで停止する。 */
    public static HydrologyLayout parse(JsonObject document, String mapVersion) {
        try {
            check(document.get("map_version").getAsString().equals(mapVersion)
                    && integer(document, "hydrology_schema_version") == 1, "水系の版・schema");
            check(document.get("coordinate_units").getAsString().equals("block"), "座標単位");
            return new HydrologyLayout(document);
        } catch (RuntimeException e) {
            throw new IllegalStateException("固定マップ版 " + mapVersion + ": 水系契約が不正です: " + e.getMessage(), e);
        }
    }

    public Map<Integer, Inventory> inventories() { return inventories; }
    public Map<String, Node> nodes() { return nodes; }
    public Map<String, Reach> reaches() { return reaches; }
    public Map<String, Lake> lakes() { return lakes; }
    public List<Outlet> outlets() { return outlets; }

    private void validateGraph() {
        var edges = new HashMap<String, List<String>>();
        var incoming = new HashMap<String, Integer>();
        var outgoing = new HashMap<String, Integer>();
        var inventoryAt = new HashMap<String, Integer>();
        for (var id : nodes.keySet()) { edges.put(id, new ArrayList<>()); incoming.put(id, 0); outgoing.put(id, 0); }
        for (var r : reaches.values()) {
            edge(edges, incoming, outgoing, r.from, r.to);
            assign(inventoryAt, r.from, r.inventoryId); assign(inventoryAt, r.to, r.inventoryId);
        }
        var lakeInlets = new HashSet<String>();
        var lakeOutlets = new HashSet<String>();
        for (var l : lakes.values()) {
            check(lakeOutlets.add(l.outlet), "湖出口の重複");
            assign(inventoryAt, l.outlet, l.inventoryId);
            for (var inlet : l.inlets) {
                check(lakeInlets.add(inlet), "湖入口の重複");
                edge(edges, incoming, outgoing, inlet, l.outlet);
                assign(inventoryAt, inlet, l.inventoryId);
            }
            var starts = reaches.values().stream().filter(r -> r.from.equals(l.outlet)).toList();
            check(starts.size() == 1 && starts.getFirst().profile.getFirst().fullWidth == l.outletFullWidth
                    && starts.getFirst().profile.getFirst().waterY - starts.getFirst().profile.getFirst().depth == l.outletBedY, "湖出口の幅・床不一致");
        }
        var pending = new HashMap<>(incoming);
        var queue = new ArrayDeque<String>();
        pending.forEach((id, count) -> { if (count == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            var id = queue.remove(); visited++;
            for (var to : edges.get(id)) if (pending.merge(to, -1, Integer::sum) == 0) queue.add(to);
        }
        check(visited == nodes.size(), "水系の閉路");
        for (var n : nodes.values()) {
            int in = incoming.get(n.id), out = outgoing.get(n.id);
            check(switch (n.kind) {
                case "source" -> in == 0 && out == 1;
                case "confluence" -> in >= 2 && out == 1;
                case "ocean_outlet" -> in == 1 && out == 0;
                case "lake_outlet" -> lakeOutlets.contains(n.id) && in >= 1 && out == 1;
                case "lake_inlet" -> lakeInlets.contains(n.id) && in == 1 && out == 1;
                default -> false;
            }, "ノードの接続本数: " + n.id);
            if (n.kind.equals("ocean_outlet"))
                check(outlets.stream().anyMatch(o -> o.node.equals(n.id) && o.inventoryId == inventoryAt.get(n.id)), "河口定義の不足・不一致");
        }
        check(new HashSet<>(inventoryAt.values()).equals(Set.of(2, 3, 4)), "淡水流域の不足");
    }

    private static void edge(Map<String, List<String>> edges, Map<String, Integer> incoming,
                             Map<String, Integer> outgoing, String from, String to) {
        check(edges.containsKey(from) && edges.containsKey(to), "未定義ノード");
        edges.get(from).add(to); incoming.merge(to, 1, Integer::sum); outgoing.merge(from, 1, Integer::sum);
    }
    private static void assign(Map<String, Integer> at, String id, int inventory) {
        var previous = at.putIfAbsent(id, inventory);
        check(previous == null || previous == inventory, "合流の水量ID不一致");
    }
    private static boolean matches(Profile p, Node n) {
        return n != null && p.x == n.x && p.z == n.z && p.waterY == n.waterY;
    }
    private static Point point(JsonArray a) {
        check(a.size() == 2, "座標の列数");
        var p = new Point(integer(a.get(0)), integer(a.get(1)));
        check(MapCoordinates.contains(p.x, p.z), "座標の範囲");
        return p;
    }
    static boolean inside(List<Point> points, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            var a = points.get(i); var b = points.get(j);
            if ((a.z > z) != (b.z > z) && x < (double)(b.x - a.x) * (z - a.z) / (b.z - a.z) + a.x) inside = !inside;
        }
        return inside;
    }
    private static double edgeDistance(List<Point> points, double x, double z) {
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            var a = points.get(i); var b = points.get((i + 1) % points.size());
            double dx = b.x - a.x, dz = b.z - a.z;
            double t = Math.clamp(((x - a.x) * dx + (z - a.z) * dz) / (dx * dx + dz * dz), 0, 1);
            distance = Math.min(distance, Math.hypot(x - a.x - t * dx, z - a.z - t * dz));
        }
        return distance;
    }
    private static void check(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
