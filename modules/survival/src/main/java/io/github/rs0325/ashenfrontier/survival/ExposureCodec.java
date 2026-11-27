package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.food.IntakeExposure;
import io.github.rs0325.ashenfrontier.core.NbtLists;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.LinkedHashMap;

/** 必須の曝露保存を厳密に読む。破損はAttachment側が原文を保全して評価を停止する。 */
final class ExposureCodec {
    private ExposureCodec() {}
    static CompoundTag write(Exposure.State state) {
        var s=state.snapshot();var tag=new CompoundTag();tag.putInt("model",1);
        tag.put("loads",numbers(s.loads()));tag.put("integrals",numbers(s.integrals()));tag.putIntArray("stages",s.stages());
        tag.put("totals",numbers(s.totals()));tag.putBoolean("notified",s.notified());tag.putBoolean("enabled",s.enabled());
        tag.putLong("notification_tick",s.notificationTick());tag.putInt("unassessed_reasons",s.unassessedReasons());
        tag.putBoolean("approximate",s.approximate());
        var nuclear=new ListTag();s.nuclearIntakes().forEach((id,value)->{
            var row=new CompoundTag();row.putString("id",id);row.putDouble("oral_lower",value.oralLower());row.putDouble("oral_upper",value.oralUpper());
            row.putDouble("inhaled_lower",value.inhaledLower());row.putDouble("inhaled_upper",value.inhaledUpper());nuclear.add(row);
        });tag.put("nuclear_intakes",nuclear);
        var unknown=new ListTag();s.unidentifiedOralKg().forEach((id,kg)->{var row=new CompoundTag();row.putString("id",id);row.putDouble("kg",kg);unknown.add(row);});
        tag.put("unidentified_oral_kg",unknown);return tag;
    }
    static Exposure.State read(CompoundTag tag) {
        require(tag,"model",Tag.TAG_INT);if(tag.getInt("model")!=1)throw new IllegalArgumentException("未知の曝露モデルです");
        double[] loads=new double[7],integrals=new double[4],totals=new double[20];
        readNumbers(tag,"loads",loads);readNumbers(tag,"integrals",integrals);readNumbers(tag,"totals",totals);
        require(tag,"stages",Tag.TAG_INT_ARRAY);require(tag,"notified",Tag.TAG_BYTE);require(tag,"enabled",Tag.TAG_BYTE);
        require(tag,"notification_tick",Tag.TAG_LONG);require(tag,"unassessed_reasons",Tag.TAG_INT);require(tag,"approximate",Tag.TAG_BYTE);
        require(tag,"nuclear_intakes",Tag.TAG_LIST);require(tag,"unidentified_oral_kg",Tag.TAG_LIST);
        var nuclear=new LinkedHashMap<String,Exposure.NuclearIntake>();var unknown=new LinkedHashMap<String,Double>();
        for(var element:NbtLists.read(tag,"nuclear_intakes",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)element;require(row,"id",Tag.TAG_STRING);
            for(String key:new String[]{"oral_lower","oral_upper","inhaled_lower","inhaled_upper"})require(row,key,Tag.TAG_DOUBLE);
            String id=row.getString("id");
            if(net.minecraft.resources.ResourceLocation.tryParse(id)==null||nuclear.put(id,new Exposure.NuclearIntake(row.getDouble("oral_lower"),row.getDouble("oral_upper"),row.getDouble("inhaled_lower"),row.getDouble("inhaled_upper")))!=null)
                throw new IllegalArgumentException("核種の摂取記録が重複または不正です");
        }
        for(var element:NbtLists.read(tag,"unidentified_oral_kg",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)element;require(row,"id",Tag.TAG_STRING);require(row,"kg",Tag.TAG_DOUBLE);
            if(unknown.put(row.getString("id"),row.getDouble("kg"))!=null)throw new IllegalArgumentException("未評価の物質が重複しています");
        }
        return Exposure.State.restore(new Exposure.Snapshot(loads,integrals,tag.getIntArray("stages"),totals,
                tag.getBoolean("notified"),tag.getBoolean("enabled"),tag.getLong("notification_tick"),
                tag.getInt("unassessed_reasons"),tag.getBoolean("approximate"),nuclear,unknown));
    }
    static CompoundTag write(IntakeExposure value) {
        var t=new CompoundTag();t.putDouble("mass",value.massKg());t.putString("nuclide",value.nuclide());
        t.putDouble("lower",value.activityLowerBq());t.putDouble("upper",value.activityUpperBq());t.putLong("tick",value.tick());
        var species=new ListTag();value.substancesKg().forEach((id,kg)->{
            var row=new CompoundTag();row.putString("id",id);row.putDouble("kg",kg);species.add(row);
        });t.put("species",species);return t;
    }
    static IntakeExposure intake(CompoundTag t) {
        for(String key:new String[]{"mass","lower","upper"})require(t,key,Tag.TAG_DOUBLE);
        require(t,"nuclide",Tag.TAG_STRING);require(t,"tick",Tag.TAG_LONG);
        require(t,"species",Tag.TAG_LIST);var species=new LinkedHashMap<String,Double>();
        for(var entry:NbtLists.read(t,"species",Tag.TAG_COMPOUND)) {
            var row=(CompoundTag)entry;require(row,"id",Tag.TAG_STRING);require(row,"kg",Tag.TAG_DOUBLE);
            if(species.put(row.getString("id"),row.getDouble("kg"))!=null)throw new IllegalArgumentException("摂取物質が重複しています");
        }
        return new IntakeExposure(t.getDouble("mass"),species,t.getString("nuclide"),t.getDouble("lower"),t.getDouble("upper"),t.getLong("tick"));
    }
    private static ListTag numbers(double[] values) { var list=new ListTag();for(double v:values)list.add(DoubleTag.valueOf(v));return list; }
    private static void readNumbers(CompoundTag t,String name,double[] target) {
        require(t,name,Tag.TAG_LIST);
        var list=NbtLists.read(t,name,Tag.TAG_DOUBLE);
        if(list.size()!=target.length)throw new IllegalArgumentException("曝露列の長さが不正です");
        for(int i=0;i<target.length;i++) {
            double value=list.getDouble(i);if(!Double.isFinite(value)||value<0)throw new IllegalArgumentException("曝露量が不正です");target[i]=value;
        }
    }
    static void require(CompoundTag tag,String key,int type) {
        if(!tag.contains(key,type))throw new IllegalArgumentException("曝露保存の必須項目がありません: "+key);
    }
}
