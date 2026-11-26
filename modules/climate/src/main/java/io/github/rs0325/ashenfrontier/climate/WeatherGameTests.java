package io.github.rs0325.ashenfrontier.climate;

import io.github.rs0325.ashenfrontier.core.testing.ThreadCpuClock;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Lifecycle;
import io.github.rs0325.ashenfrontier.core.AshenFrontierCore;
import io.github.rs0325.ashenfrontier.world.FixedMapChunkGenerator;
import io.netty.buffer.Unpooled;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WeatherGameTests {
    private static FixedMapChunkGenerator generator(GameTestHelper helper) {
        var key=ResourceKey.create(Registries.WORLD_PRESET,AshenFrontierCore.id("fixed_map"));
        return (FixedMapChunkGenerator)helper.getLevel().registryAccess().registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(key).value().createWorldDimensions().overworld();
    }
    private static List<WeatherReplay.Interval> collect(WeatherReplay cursor) {
        var rows=new ArrayList<WeatherReplay.Interval>();
        while(!cursor.complete()) {
            cursor.advance(1024);
            cursor.page().ifPresent(page->{ rows.addAll(page.intervals());cursor.acknowledge(page.intervals().getLast().endTick()); });
        }
        return rows;
    }
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void diskRestartCursorSettingsAndNearbySync(GameTestHelper helper) throws Exception {
        var server=helper.getLevel().getServer();var generator=generator(helper);
        var type=helper.getLevel().registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(ResourceKey.create(Registries.DIMENSION_TYPE,AshenFrontierCore.id("fixed_map")));
        var stem=new LevelStem(type,generator);
        var levelData=new PrimaryLevelData(server.getWorldData().getLevelSettings(),new WorldOptions(47,false,false),
                PrimaryLevelData.SpecialWorldProperty.NONE,Lifecycle.stable());
        levelData.setGameTime(30000);
        var source=LevelStorageSource.createDefault(Files.createTempDirectory(Path.of("."),"weather-contract-"));
        var pos=new BlockPos(-8500,100,-8192);
        CompoundTag cursorTag,saved;List<WeatherReplay.Interval> expected;
        try(var access=source.createAccess("fixture");
            var level=new ServerLevel(server,Util.backgroundExecutor(),access,levelData,Level.OVERWORLD,stem,
                    LoggerChunkProgressListener.createCompleted(),false,47,List.of(),false,null)) {
            var data=WeatherStore.get(level);
            var pending=WeatherQuery.begin(level,pos,0,600).orElseThrow();
            pending.advance(1);
            var valid=WeatherQuery.saveCursor(pending);
            helper.assertTrue(!WeatherQuery.restoreCursor(level,valid).complete(),"未消費の最終ページを復元");
            var broken=new ArrayList<CompoundTag>();
            var missing=valid.copy();missing.remove("page");broken.add(missing);
            var empty=valid.copy();empty.put("page",new net.minecraft.nbt.ListTag());broken.add(empty);
            var wrongType=valid.copy();wrongType.putLong("page",600);broken.add(wrongType);
            var gap=valid.copy();gap.getList("page",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putLong("from",1);broken.add(gap);
            var shortPage=valid.copy();shortPage.getList("page",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putLong("to",599);broken.add(shortPage);
            var reversed=valid.copy();reversed.getList("page",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putLong("to",-1);broken.add(reversed);
            var unfinished=valid.copy();unfinished.putBoolean("finished",false);broken.add(unfinished);
            var acknowledged=valid.copy();acknowledged.putLong("acknowledged",600);broken.add(acknowledged);
            for(var corrupt:broken) {
                boolean rejected=false;
                try { WeatherQuery.restoreCursor(level,corrupt); } catch(IllegalArgumentException e) { rejected=true; }
                helper.assertTrue(rejected,"不整合の未消費ページを拒否: "+corrupt.getAllKeys());
            }
            helper.assertTrue(collect(WeatherQuery.restoreCursor(level,valid)).equals(collect(pending)),"拒否後も正常な最終ページを再提供");
            var consumed=WeatherQuery.saveCursor(pending);
            helper.assertTrue(WeatherQuery.restoreCursor(level,consumed).complete(),"確定済み最終ページは完了を復元");
            var zero=WeatherQuery.begin(level,pos,600,600).orElseThrow();
            helper.assertTrue(!WeatherQuery.restoreCursor(level,WeatherQuery.saveCursor(zero)).complete(),"未評価のゼロ長要求を復元");
            zero.advance(4096);
            helper.assertTrue(!WeatherQuery.restoreCursor(level,WeatherQuery.saveCursor(zero)).complete(),"未消費のゼロ長ページを復元");
            zero.acknowledge(600);
            helper.assertTrue(WeatherQuery.restoreCursor(level,WeatherQuery.saveCursor(zero)).complete(),"確定済みゼロ長要求を復元");
            expected=collect(WeatherQuery.begin(level,pos,0,30000).orElseThrow());
            var cursor=WeatherQuery.begin(level,pos,0,30000).orElseThrow();
            cursor.advance(5000);
            helper.assertTrue(cursor.frame.tick()>0,"途中まで進んだ数値状態のカーソルを保存");
            cursorTag=WeatherQuery.saveCursor(cursor);
            helper.assertTrue(data.settings(new WeatherSettings(1400,200,WeatherEvent.FOG.bit(),true)),"設定変更を保存");
            helper.assertTrue(data.settings(new WeatherSettings(1800,200,0,false)),"固定気候モードへの変更");
            helper.assertTrue(data.settings(new WeatherSettings(2200,200,WeatherEvent.FOG.bit(),true)),"動的気象への復帰");
            var configured=collect(WeatherQuery.begin(level,pos,0,30000).orElseThrow());
            for(var row:configured)if(row.startTick()>=1800&&row.startTick()<2200)
                helper.assertTrue(row.state().rainKgM2GameSecond()==0&&row.state().snowKgM2GameSecond()==0&&row.state().events()==0&&row.state().windXMps()==6,"固定気候モードを過去へ遡及しない");
            for(var row:configured)if(row.startTick()>=1400)
                helper.assertTrue((row.state().events()&~WeatherEvent.FOG.bit())==0,"無効イベントが復活しない");
            helper.assertTrue(collect(WeatherQuery.restoreCursor(level,cursorTag)).equals(expected),"カーソルは開始時の設定履歴を保持");
            while(data.current.tick()<30000)data.service(30000,8192,Long.MAX_VALUE);
            helper.assertTrue(data.current.tick()==30000,"進んだ気象状態をディスク保存");
            data.setDirty(false);
            var clock=ClimateClock.get(level);
            clock.changeDayLength(30000,12000);
            data.clock(clock.history(),clock.revision(),30000);
            helper.assertTrue(data.isDirty(),"日長変更による巻戻しも保存対象");
            while(data.current.tick()<30000)data.service(30000,8192,Long.MAX_VALUE);
            saved=data.save(new CompoundTag(),level.registryAccess());
            helper.assertTrue(saved.getDouble("grace_end_game_seconds")==86400,"初期猶予の絶対境界を保存");
            level.getDataStorage().save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(level.getChunkSource().getLoadedChunksCount()==0,"全域の計算でチャンクをロードしない");
        }
        try(var access=source.createAccess("fixture");
            var level=new ServerLevel(server,Util.backgroundExecutor(),access,levelData,Level.OVERWORLD,stem,
                    LoggerChunkProgressListener.createCompleted(),false,47,List.of(),false,null)) {
            var data=WeatherStore.get(level);
            helper.assertTrue(data.current.tick()==30000,"再起動で進んだ気象時刻を復元");
            helper.assertTrue(data.timeline.at(1400).enabledEvents()==WeatherEvent.FOG.bit(),"再起動でイベント無効境界を復元");
            helper.assertTrue(collect(WeatherQuery.restoreCursor(level,cursorTag)).equals(expected),"ディスク再読込み後も保存カーソルが一致");
            var before=data.save(new CompoundTag(),level.registryAccess());
            var packet=WeatherCellsPayload.around(level,pos);
            helper.assertTrue(packet.cells().size()==9,"後入りには周辺9セルだけを選ぶ");
            var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),level.registryAccess());
            try {
                WeatherCellsPayload.CODEC.encode(buf,packet);
                int bytes=buf.readableBytes();
                var decoded=WeatherCellsPayload.CODEC.decode(buf);
                helper.assertTrue(decoded.equals(packet),"気象の周辺同期を損失なく復号");
                WeatherClientState.accept(decoded);
                helper.assertTrue(WeatherClientState.cell(Level.NETHER.location(),decoded.cells().getFirst().index()).isEmpty(),"別次元に気象を流用しない");
                WeatherClientState.clear();
                LogUtils.getLogger().info("M3-134 nearby payload cells={} bytes={} players=4 estimated_bytes_per_second={}",
                        packet.cells().size(),bytes,bytes*4);
            } finally { buf.release(); }
            helper.assertTrue(before.equals(data.save(new CompoundTag(),level.registryAccess())),"後入り同期で世界の時刻・猶予・天候をリセットしない");
            helper.assertTrue(WeatherQuery.begin(helper.getLevel(),pos,0,0).isEmpty(),"別生成器にフォールバックしない");
        }
        helper.succeed();
    }

    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID,timeoutTicks=400)
    public static void globalBudgetCheckpointsAndLongHistory(GameTestHelper helper) throws Exception {
        var version=generator(helper).mapVersion();
        var history=new io.github.rs0325.ashenfrontier.core.time.DayLengthHistory(0,24000);
        long seed=47;
        var model=new WeatherModel(FixedClimate.require(version).cells(),seed,"benchmark");
        var timeline=new WeatherTimeline(List.of(new WeatherSettings(0,600,511,true)));
        var initial=model.initial(timeline.at(0),history,86400);
        // 実Storeと同じ形式で初期レコードを組み立てる。
        var tag=new CompoundTag();tag.putInt("format",1);tag.putInt("weather_model",1);tag.putLong("seed",seed);
        tag.putDouble("grace_end_game_seconds",86400);tag.put("map",io.github.rs0325.ashenfrontier.world.MapVersion.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE,version).getOrThrow());
        tag.put("current",WeatherStore.writeFrame(initial));tag.put("previous",WeatherStore.writeFrame(initial));var frames=new net.minecraft.nbt.ListTag();frames.add(WeatherStore.writeFrame(initial));tag.put("checkpoints",frames);
        var settingRows=new net.minecraft.nbt.ListTag();var setting=new CompoundTag();setting.putLong("tick",0);setting.putInt("interval",600);setting.putInt("events",511);setting.putBoolean("dynamic",true);settingRows.add(setting);tag.put("settings",settingRows);
        var store=WeatherStore.load(tag,version,seed,history);
        var cpu=new ThreadCpuClock();boolean useCpu=cpu.useCpu("weather_update",1_000_000);long[] times=new long[1200],cpuTimes=new long[1200];int maximum=0;
        for(int tick=0;tick<1400;tick++) {
            long cpuStart=cpu.now(),start=System.nanoTime();int cells=store.service(tick,2048,start+600000);long elapsed=System.nanoTime()-start,usedCpu=cpu.now()-cpuStart;
            maximum=Math.max(maximum,cells);if(tick>=200){times[tick-200]=elapsed;cpuTimes[tick-200]=usedCpu;}
        }
        Arrays.sort(times);Arrays.sort(cpuTimes);
        helper.assertTrue(maximum<=9,"通常tickの全4096走査なし");
        LogUtils.getLogger().info("M3-134 normal update cells_max={} p95_ms={} p99_ms={} samples={} wall_max_ms={} cpu_p95_ms={} cpu_p99_ms={} cpu_max_ms={}",
                maximum,times[1139]/1e6,times[1187]/1e6,times.length,times[1199]/1e6,cpuTimes[1139]/1e6,cpuTimes[1187]/1e6,cpuTimes[1199]/1e6);
        helper.assertTrue((useCpu?cpuTimes:times)[1139]<1_000_000,"気象追加処理"+(useCpu?"CPU":"壁時計")+" p95が1ms未満");
        for(int years:new int[]{1,10}) {
            long end=years*24L*24000,start=System.nanoTime(),calls=0;
            while(store.current.tick()<end) { store.service(end,8192,Long.MAX_VALUE);calls++; }
            var saved=store.save(new CompoundTag(),helper.getLevel().registryAccess());
            var bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(saved,bytes);
            helper.assertTrue(store.checkpoints.size()<=16,"10年でもcheckpoint保存数は有界");
            var restored=WeatherStore.load(saved,version,seed,history);
            helper.assertTrue(Arrays.equals(store.current.values,restored.current.values),"現在気象の数値を正確に保存");
            helper.assertTrue(store.current.tick()==end,"未ロード地域も全域更新");
            var cursor=store.replay(-8500,-8192,end-500,end);
            var original=collect(cursor);
            var again=collect(restored.replay(-8500,-8192,end-500,end));
            helper.assertTrue(original.equals(again),"checkpointからの積算が再起動後も一致");
            LogUtils.getLogger().info("M3-134 archive years={} ms={} calls={} checkpoint_count={} numeric_bytes={} gzip_bytes={}",
                    years,(System.nanoTime()-start)/1e6,calls,store.checkpoints.size(),
                    (store.checkpoints.size()+3L)*4096*12*8,bytes.size());
        }
        helper.succeed();
    }
}
