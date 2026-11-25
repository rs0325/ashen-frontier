package io.github.rs0325.ashenfrontier.climate;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import io.github.rs0325.ashenfrontier.world.MapCoordinates;
import java.util.Optional;
import java.util.ArrayList;
import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;

/** 気象専用API。EnvironmentQuery・水収支・室内への接続は後続Issueが所有する。 */
public final class WeatherQuery {
    private WeatherQuery() {}
    /** 未対応ワールド/範囲外は空。過去要求は保存カーソルを有限仕事量で進め、ページを消費する。 */
    public static Optional<WeatherReplay> begin(ServerLevel level,BlockPos position,long from,long to) {
        if(ClimateQuery.mapVersion(level).isEmpty()||!MapCoordinates.contains(position.getX(),position.getZ()))return Optional.empty();
        if(from<0||to<from||to>level.getGameTime())throw new IllegalArgumentException("気象要求時刻が不正です");
        return Optional.of(WeatherStore.get(level).replay(position.getX(),position.getZ(),from,to));
    }
    /** 消費前のページも保存する。未完了stepは再開時に最大4096セルだけ再計算する。 */
    public static CompoundTag saveCursor(WeatherReplay cursor) {
        var tag=new CompoundTag();tag.putInt("format",1);tag.putInt("weather_model",WeatherModel.VERSION);tag.putString("source",cursor.model.identity);tag.putLong("seed",cursor.model.seed);
        tag.put("frame",WeatherStore.writeFrame(cursor.frame));tag.putInt("x",cursor.x);tag.putInt("z",cursor.z);
        tag.putLong("from",cursor.from);tag.putLong("to",cursor.to);tag.putLong("through",cursor.through);
        tag.putLong("acknowledged",cursor.acknowledged);tag.putBoolean("finished",cursor.finished);
        var rows=new ListTag();
        for(var row:cursor.rows) {
            var value=new CompoundTag();value.putLong("from",row.startTick());value.putLong("to",row.endTick());
            value.putDouble("rain",row.rainKgM2());value.putDouble("snow",row.snowKgM2());value.putDouble("solar",row.solarJM2());
            value.putLongArray("state",encode(row.state()));value.put("setting",WeatherStore.settingTag(row.settings()));rows.add(value);
        }
        tag.put("page",rows);
        var clock=new ListTag();
        for(var change:cursor.time.changes()) {
            var value=new CompoundTag();value.putLong("tick",change.tick());value.putLong("day",change.ticksPerDay());clock.add(value);
        }
        tag.put("clock",clock);
        var settings=new ListTag();
        for(var change:cursor.timeline.changes()) {
            var value=new CompoundTag();value.putLong("tick",change.tick());value.putInt("interval",change.intervalTicks());
            value.putInt("events",change.enabledEvents());value.putBoolean("dynamic",change.dynamic());settings.add(value);
        }
        tag.put("settings",settings);return tag;
    }
    /** ワールド版/seed/設定履歴はWeatherStoreの正本を使用する。異なるワールドへカーソルを移さない。 */
    public static WeatherReplay restoreCursor(ServerLevel level,CompoundTag tag) {
        var data=WeatherStore.get(level);
        if(tag.getInt("format")!=1||tag.getInt("weather_model")!=WeatherModel.VERSION||!tag.getString("source").equals(data.model.identity)||tag.getLong("seed")!=data.seed||tag.getLong("to")>level.getGameTime())throw new IllegalArgumentException("気象カーソル形式が不正です");
        var savedSettings=new ArrayList<WeatherSettings>();
        var settingRows=tag.getList("settings",Tag.TAG_COMPOUND);
        for(int i=0;i<settingRows.size();i++) {
            var v=settingRows.getCompound(i);
            savedSettings.add(new WeatherSettings(v.getLong("tick"),v.getInt("interval"),v.getInt("events"),v.getBoolean("dynamic")));
        }
        var savedClock=new ArrayList<DayLengthHistory.Change>();
        var clockRows=tag.getList("clock",Tag.TAG_COMPOUND);
        for(int i=0;i<clockRows.size();i++) { var v=clockRows.getCompound(i);savedClock.add(new DayLengthHistory.Change(v.getLong("tick"),v.getLong("day"))); }
        var cursor=new WeatherReplay(data.model,new WeatherTimeline(savedSettings),new WeatherTime(new DayLengthHistory(savedClock)),WeatherStore.GRACE_END,
                WeatherStore.readFrame(tag.getCompound("frame")),tag.getInt("x"),tag.getInt("z"),tag.getLong("from"),tag.getLong("to"));
        cursor.through=tag.getLong("through");cursor.acknowledged=tag.getLong("acknowledged");cursor.finished=tag.getBoolean("finished");
        if(cursor.through<cursor.from||cursor.through>cursor.to||cursor.acknowledged<cursor.from||cursor.acknowledged>cursor.through)
            throw new IllegalArgumentException("気象カーソルの評価時刻が不正です");
        if(!(tag.get("page") instanceof ListTag rows)||(!rows.isEmpty()&&rows.getElementType()!=Tag.TAG_COMPOUND)||rows.size()>128)
            throw new IllegalArgumentException("気象ページの保存形式が不正です");
        if((cursor.from!=cursor.to&&cursor.finished!=(cursor.through==cursor.to))
                ||(cursor.from==cursor.to&&!rows.isEmpty()&&(!cursor.finished||rows.size()!=1)))
            throw new IllegalArgumentException("気象カーソルの完了状態が不正です");
        long end=cursor.acknowledged;
        for(int i=0;i<rows.size();i++) {
            var row=rows.getCompound(i);
            long start=row.getLong("from"),stop=row.getLong("to");
            if(start!=end||stop<start||stop>cursor.through||(stop==start&&cursor.from!=cursor.to))
                throw new IllegalArgumentException("未消費の気象ページが連続していません");
            cursor.rows.add(new WeatherReplay.Interval(start,stop,decode(row.getLongArray("state")),WeatherStore.setting(row.getCompound("setting")),
                    row.getDouble("rain"),row.getDouble("snow"),row.getDouble("solar")));
            end=stop;
        }
        if(end!=cursor.through)throw new IllegalArgumentException("未消費の気象ページが欠落しています");
        return cursor;
    }
    static long[] encode(WeatherState s) {
        double[] values={s.pressureAnomalyPa(),s.temperatureAnomalyK(),s.vaporKgM2(),s.windXMps(),s.windZMps(),
                s.cloudFraction(),s.rainKgM2GameSecond(),s.snowKgM2GameSecond(),s.dustKgM3(),s.pollutionKgM3(),s.events(),s.severity()};
        long[] bits=new long[values.length];for(int i=0;i<bits.length;i++)bits[i]=Double.doubleToLongBits(values[i]);return bits;
    }
    static WeatherState decode(long[] bits) {
        if(bits.length!=12)throw new IllegalArgumentException("気象セルのフィールド数が不正です");
        double[] v=new double[12];for(int i=0;i<v.length;i++)v[i]=Double.longBitsToDouble(bits[i]);
        return new WeatherState(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],v[9],(int)v[10],v[11]);
    }
}
