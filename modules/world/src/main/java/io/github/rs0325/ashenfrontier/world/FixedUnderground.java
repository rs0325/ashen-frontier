package io.github.rs0325.ashenfrontier.world;

import io.github.rs0325.ashenfrontier.core.catalog.NatureCatalog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 固定鉱床と歩行可能な階段状経路。交差するチャンクの候補だけを列へ渡す。 */
public final class FixedUnderground {
    record Body(UndergroundLayout.Deposit data, int y, BlockState host, BlockState ore) {
        boolean contains(int x, int y, int z, int margin) {
            if (Math.abs(y - this.y) > data.radii().get(1) + margin) return false;
            double dx = (x - data.x()) / (double) (data.radii().get(0) + margin);
            double dy = (y - this.y) / (double) (data.radii().get(1) + margin);
            double dz = (z - data.z()) / (double) (data.radii().get(2) + margin);
            return switch (data.shape()) {
                case "ellipsoid" -> dx * dx + dy * dy + dz * dz <= 1;
                case "layer" -> dx * dx + dz * dz <= 1 && Math.abs(dy) <= 1;
                case "vein" -> dx * dx + dy * dy <= 1 && Math.abs(dz) <= 1;
                default -> throw new IllegalStateException("未定義の鉱床形状: " + data.shape());
            };
        }
    }

    record Passage(String id, int ax, int az, int ay, int bx, int bz, int by, int width, int height, List<Integer> floors) {
        int floor(int x, int z) {
            double dx = bx - ax, dz = bz - az, length = dx * dx + dz * dz;
            double t = length == 0 ? 0 : ((x - ax) * dx + (z - az) * dz) / length;
            if (t < 0 || t > 1) return Integer.MIN_VALUE;
            if (Math.abs(x - ax - t * dx) > width || Math.abs(z - az - t * dz) > width) return Integer.MIN_VALUE;
            double at = t * (floors.size() - 1);
            int index = (int) at, next = Math.min(index + 1, floors.size() - 1);
            return (int) Math.floor(floors.get(index) + (at - index) * (floors.get(next) - floors.get(index)));
        }
    }

    record Cell(List<Body> bodies, List<Passage> passages, Voxels[] columns) {
        Cell(List<Body> bodies, List<Passage> passages) { this(bodies, passages, null); }
    }
    record Voxels(int[] runs, BlockState[][] palette) {
        BlockState state(int y, int component) {
            for (int i=0;i<runs.length;i+=3) {
                if (y<runs[i]) break;
                if (y<=runs[i+1]) return palette[runs[i+2]][component];
            }
            return null;
        }
        int surface(int original) {
            for (int i=0;i<runs.length;i+=3) {
                if (runs[i]>original) break;
                if (runs[i+1]>=original && palette[runs[i+2]][0].isAir()) return runs[i]-1;
            }
            return original;
        }
    }
    private static final Cell EMPTY = new Cell(List.of(), List.of());
    private static final int[] NO_PASSAGES = new int[0];
    private final Map<Long, Cell> cells;
    private final boolean detailed;
    private final List<Body> bodies;
    private final List<Passage> passages;

    FixedUnderground(MapVersion version, FixedTerrain terrain) {
        var layout = UndergroundLayout.read(version);
        if (layout.fixed_seed() != terrain.map().seed()) throw new IllegalStateException("地下の固定シードが一致しません");
        detailed=layout.d4()!=null;
        if (detailed) {
            if (layout.d4().schema_version()!=1) throw new IllegalStateException("D4地下のschemaが不正です");
            var palette=new BlockState[layout.d4().palette().size()][2];
            for(int i=0;i<palette.length;i++) {
                var pair=layout.d4().palette().get(i);
                palette[i][0]=block(pair.get(0));palette[i][1]=block(pair.get(1));
            }
            var compiled=new HashMap<Long,Cell>();
            for(var column:layout.d4().columns()) {
                var cell=compiled.computeIfAbsent(key(column.x()>>4,column.z()>>4),
                        ignored->new Cell(List.of(),List.of(),new Voxels[256]));
                int[] runs=column.runs().stream().mapToInt(Integer::intValue).toArray();
                if(runs.length%3!=0)throw new IllegalStateException("D4列の区間が不正です");
                int previous=-128;
                for(int i=0;i<runs.length;i+=3) {
                    if(runs[i]<=previous || runs[i]>runs[i+1] || runs[i+1]>575 || runs[i+2]<0 || runs[i+2]>=palette.length)
                        throw new IllegalStateException("D4列の順序・範囲が不正です");
                    previous=runs[i+1];
                }
                int at=(column.z()&15)*16+(column.x()&15);
                if(cell.columns[at]!=null)throw new IllegalStateException("D4列の重複");
                cell.columns[at]=new Voxels(runs,palette);
            }
            cells=Map.copyOf(compiled);this.bodies=List.of();this.passages=List.of();
            return;
        }
        var bodies = new ArrayList<Body>();
        var passages = new ArrayList<Passage>();
        for (var data : layout.deposits()) {
            if (!Boolean.TRUE.equals(data.vanilla())) {
                var entry = NatureCatalog.require(data.block());
                if (entry.host() != null && !id(entry.host()).equals(id(data.host()))) {
                    throw new IllegalStateException("鉱床の母岩がカタログと一致しません: " + data.id());
                }
            }
            int y = terrain.surfaceY(data.x(), data.z()) - data.depth();
            var host = block(id(data.host()));
            var column = terrain.column(data.x(), data.z());
            while (y > -63 && !column.state(y).equals(column.rockState(y))) y--;
            if (!data.lens()) {
                int nearest = Integer.MIN_VALUE, distance = Integer.MAX_VALUE;
                for (int candidate = -63; candidate <= column.surfaceY() - 3; candidate++) {
                    if (column.state(candidate).equals(host) && Math.abs(candidate - y) < distance) {
                        nearest = candidate;
                        distance = Math.abs(candidate - y);
                    }
                }
                if (nearest == Integer.MIN_VALUE) throw new IllegalStateException("鉱床の母岩が地層にありません: " + data.id());
                y = nearest;
            }
            var body = new Body(data, y, host, block(id(data.block())));
            bodies.add(body);
            if (!data.id().startsWith("oil_") && !Boolean.TRUE.equals(data.vanilla())) {
                int distance = Math.max(96, data.depth() + 24);
                int x = data.x() - distance;
                passages.add(passage(terrain, data.id(), x, data.z(), terrain.surfaceY(x, data.z()),
                        data.x(), data.z(), y, 1, 3));
            }
        }
        for (var space : layout.spaces()) {
            int floor = terrain.surfaceY(space.x(), space.z()) - space.depth();
            int entryX = space.x() - Integer.signum(space.end_x() - space.x()) * (space.depth() + 16);
            passages.add(passage(terrain, space.id() + "_entry", entryX, space.z(),
                    terrain.surfaceY(entryX, space.z()),
                    space.x(), space.z(), floor, space.width(), space.height()));
            passages.add(passage(terrain, space.id(), space.x(), space.z(), floor,
                    space.end_x(), space.end_z(), floor, space.width(), space.height()));
        }
        this.bodies = List.copyOf(bodies);
        this.passages = List.copyOf(passages);
        Map<Long, List<Body>> bodyCells = new HashMap<>();
        Map<Long, List<Passage>> passageCells = new HashMap<>();
        for (var body : bodies) {
            int rx = body.data.radii().get(0) + 3, rz = body.data.radii().get(2) + 3;
            index(bodyCells, body, body.data.x() - rx, body.data.z() - rz, body.data.x() + rx, body.data.z() + rz);
        }
        for (var passage : passages) {
            index(passageCells, passage, Math.min(passage.ax, passage.bx) - passage.width,
                    Math.min(passage.az, passage.bz) - passage.width, Math.max(passage.ax, passage.bx) + passage.width,
                    Math.max(passage.az, passage.bz) + passage.width);
        }
        var cells = new HashMap<Long, Cell>();
        for (var key : bodyCells.keySet()) cells.put(key, new Cell(List.copyOf(bodyCells.get(key)), List.of()));
        for (var key : passageCells.keySet()) cells.put(key,
                new Cell(cells.getOrDefault(key, EMPTY).bodies, List.copyOf(passageCells.get(key))));
        this.cells = Map.copyOf(cells);
    }

    private static Passage passage(FixedTerrain terrain, String id, int ax, int az, int ay,
                                   int bx, int bz, int by, int width, int height) {
        int length = Math.max(Math.abs(bx - ax), Math.abs(bz - az));
        double distance = Math.hypot(bx - ax, bz - az);
        var floors = new ArrayList<Integer>();
        for (int i = 0; i <= length; i++) {
            double t = i / (double) Math.max(1, length);
            int x = (int) Math.round(ax + t * (bx - ax)), z = (int) Math.round(az + t * (bz - az));
            int floor = Math.min((int) Math.floor(ay + t * (by - ay)), terrain.surfaceY(x,z));
            if (i != 0 && i != length) {
                for (int offset = -width; offset <= width; offset++) {
                    int dx = (int) Math.round(-(bz - az) * offset / distance);
                    int dz = (int) Math.round((bx - ax) * offset / distance);
                    floor = Math.min(floor, terrain.surfaceY(x + dx, z + dz));
                }
            }
            floors.add(floor);
        }
        // 地表の窪みを横切る入口でも床を空中へ出さず、一段ずつ歩ける勾配にする。
        for (int i = 1; i < floors.size(); i++) floors.set(i, Math.min(floors.get(i), floors.get(i - 1) + 1));
        for (int i = floors.size() - 2; i >= 0; i--) floors.set(i, Math.min(floors.get(i), floors.get(i + 1) + 1));
        return new Passage(id, ax, az, floors.getFirst(), bx, bz, floors.getLast(), width, height, List.copyOf(floors));
    }

    private static <T> void index(Map<Long, List<T>> cells, T value, int x0, int z0, int x1, int z1) {
        for (int x = x0 >> 4; x <= x1 >> 4; x++) for (int z = z0 >> 4; z <= z1 >> 4; z++) {
            cells.computeIfAbsent(key(x, z), ignored -> new ArrayList<>()).add(value);
        }
    }

    static String id(String path) {
        if (path.contains(":")) return path;
        return switch (path) {
            case "granite", "andesite", "sandstone" -> "minecraft:" + path;
            default -> "ashen_frontier:" + path;
        };
    }

    private static BlockState block(String id) {
        var key = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalStateException("地下の未登録ブロック: " + id);
        return BuiltInRegistries.BLOCK.get(key).defaultBlockState();
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    Cell cell(int x, int z) { return cells.getOrDefault(key(x >> 4, z >> 4), EMPTY); }
    boolean detailed() { return detailed; }
    static Voxels voxels(Cell cell,int x,int z) {
        return cell==null || cell.columns==null?null:cell.columns[(z&15)*16+(x&15)];
    }
    int surface(int x,int z,int original) {
        var column=voxels(cell(x,z),x,z);
        return column==null?original:column.surface(original);
    }
    List<Body> bodies() { return bodies; }
    List<Passage> passages() { return passages; }

    static BlockState rock(Cell cell, int x, int y, int z, BlockState original) {
        for (int i = 0; i < cell.bodies.size(); i++) {
            var body = cell.bodies.get(i);
            if (body.data.lens() && body.contains(x, y, z, 3)) return body.host;
        }
        return original;
    }

    static BlockState state(Cell cell, int x, int y, int z, BlockState host) {
        for (int i = 0; i < cell.bodies.size(); i++) {
            var body = cell.bodies.get(i);
            if (host.equals(body.host) && body.contains(x, y, z, 0)) return body.ore;
        }
        return host;
    }

    static int[] passageFloors(Cell cell, int x, int z) {
        if (cell == null || cell.passages.isEmpty()) return NO_PASSAGES;
        int[] floors = new int[cell.passages.size() * 2];
        for (int i = 0; i < cell.passages.size(); i++) {
            var passage = cell.passages.get(i);
            int floor = passage.floor(x, z);
            floors[i * 2] = floor;
            floors[i * 2 + 1] = floor + passage.height;
        }
        return floors;
    }

    static boolean carved(int[] floors, int y) {
        for (int i = 0; i < floors.length; i += 2) {
            if (y > floors[i] && y <= floors[i + 1]) return true;
        }
        return false;
    }
}
