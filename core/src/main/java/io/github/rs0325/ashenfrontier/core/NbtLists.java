package io.github.rs0325.ashenfrontier.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 保存リストを、異なる要素型を空列へ読み替えずに取得する。 */
public final class NbtLists {
    private NbtLists() {}
    /**
     * 存在しない任意項目は空列、存在するリストは元の列を返す。必須項目の存在検査は呼出し元が行う。
     * 空のEND型リストを許可する。リスト以外、または指定と異なる要素型はIllegalArgumentException。
     */
    public static ListTag read(CompoundTag tag,String key,int elementType) {
        var value=tag.get(key);
        if(value==null)return new ListTag();
        if(!(value instanceof ListTag list)
                ||list.getElementType()!=elementType&&!(list.isEmpty()&&list.getElementType()==Tag.TAG_END))
            throw new IllegalArgumentException("保存リストの要素型が不正です: "+key);
        return list;
    }
}
