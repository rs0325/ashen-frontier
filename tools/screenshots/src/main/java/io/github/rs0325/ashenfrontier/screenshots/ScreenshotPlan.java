package io.github.rs0325.ashenfrontier.screenshots;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 撮影専用ワールドとカメラの設定。座標はプレイヤーの足元、角度はMinecraftの度単位。 */
public record ScreenshotPlan(World world, String gameMode, Environment environment, View view,
                             double timeoutSeconds, double startupTimeoutSeconds, List<String> setupCommands,
                             List<Point> points) {
    public ScreenshotPlan {
        require(world != null && environment != null && view != null, "world・environment・viewは必須です");
        require(Set.of("spectator", "creative", "survival", "adventure").contains(gameMode == null ? "" : gameMode),
                "gameModeが不正です");
        range(timeoutSeconds, 1, 3600, "timeoutSeconds");
        range(startupTimeoutSeconds, 1, 3600, "startupTimeoutSeconds");
        require(points != null && !points.isEmpty(), "pointsを1件以上指定してください");
        setupCommands = setupCommands == null ? List.of() : List.copyOf(setupCommands);
        require(setupCommands.stream().allMatch(command -> command.startsWith("fill ") || command.startsWith("setblock ")),
                "setupCommandsはfillまたはsetblockコマンドで指定してください");
        var names = new HashSet<String>();
        for (var point : points) {
            require(point != null, "地点にnullは指定できません");
            require(names.add(point.name().toLowerCase(Locale.ROOT)), "地点名が重複しています: " + point.name());
            require(point.waitSeconds() < timeoutSeconds, "waitSecondsはtimeoutSeconds未満にしてください");
        }
        points = List.copyOf(points);
    }

    /** UTF-8のJSONを読み込む。不正な計画はワールド作成前に例外として拒否する。 */
    public static ScreenshotPlan load(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            var json = JsonParser.parseReader(reader);
            require(json.isJsonObject(), "撮影計画はJSONオブジェクトで指定してください");
            var root = json.getAsJsonObject();
            fields(root, "world", "gameMode", "environment", "view", "timeoutSeconds", "startupTimeoutSeconds", "points");
            fields(root.getAsJsonObject("world"), "name", "preset", "seed");
            fields(root.getAsJsonObject("environment"), "time", "weather");
            fields(root.getAsJsonObject("view"), "width", "height", "fov", "hud", "debug", "renderDistance");
            for (var point : root.getAsJsonArray("points")) {
                require(point.isJsonObject(), "地点はJSONオブジェクトで指定してください");
                fields(point.getAsJsonObject(), "name", "x", "y", "z", "yaw", "pitch", "waitSeconds");
            }
            if (root.has("setupCommands")) {
                fields(root, "setupCommands");
                require(root.getAsJsonArray("setupCommands").asList().stream().allMatch(value -> value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()), "setupCommandsは文字列の配列で指定してください");
            }
            return new Gson().fromJson(root, ScreenshotPlan.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("撮影計画を読めません: " + path, e);
        }
    }

    private static void fields(JsonObject object, String... names) {
        for (var name : names) {
            require(object.has(name) && !object.get(name).isJsonNull(), name + "は必須です");
            var value = object.get(name);
            boolean valid = switch (name) {
                case "world", "environment", "view" -> value.isJsonObject();
                case "points", "setupCommands" -> value.isJsonArray();
                case "hud", "debug" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                case "name", "preset", "gameMode", "weather" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                default -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            };
            require(valid, name + "の型が不正です");
            switch (name) {
                case "seed" -> value.getAsBigDecimal().longValueExact();
                case "time", "width", "height", "fov", "renderDistance" -> value.getAsBigDecimal().intValueExact();
            }
        }
    }

    public record World(String name, String preset, long seed) {
        public World {
            require(name != null && !name.isBlank(), "world.nameは必須です");
            require("ashen_frontier:fixed_map".equals(preset), "presetはashen_frontier:fixed_mapを指定してください");
        }
    }

    public record Environment(int time, String weather) {
        public Environment {
            require(time >= 0 && time < 24000, "timeは0〜23999です");
            require(Set.of("clear", "rain", "thunder").contains(weather == null ? "" : weather), "weatherが不正です");
        }
    }

    public record View(int width, int height, int fov, boolean hud, boolean debug, int renderDistance) {
        public View {
            require(width >= 320 && width <= 7680 && height >= 240 && height <= 4320, "画面サイズが範囲外です");
            require(fov >= 30 && fov <= 110, "fovは30〜110です");
            require(renderDistance >= 2 && renderDistance <= 32, "renderDistanceは2〜32です");
            require(hud || !debug, "F3を表示する場合はhudもtrueにしてください");
        }
    }

    public record Point(String name, double x, double y, double z, float yaw, float pitch, double waitSeconds) {
        public Point {
            require(name != null && name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")
                    && !name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"), "地点名が不正です");
            range(x, -16384, Math.nextDown(16384.0), "x");
            range(z, -16384, Math.nextDown(16384.0), "z");
            range(y, -64, 1024, "y");
            range(yaw, -180, 180, "yaw");
            range(pitch, -90, 90, "pitch");
            range(waitSeconds, 0, 3599, "waitSeconds");
        }
    }

    private static void range(double value, double min, double max, String name) {
        require(Double.isFinite(value) && value >= min && value <= max, name + "が範囲外です");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
