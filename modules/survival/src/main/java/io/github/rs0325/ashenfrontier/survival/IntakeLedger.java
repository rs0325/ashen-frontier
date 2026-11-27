package io.github.rs0325.ashenfrontier.survival;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/** 本人の摂取番号の発行末尾。取消・再ログイン後も同じ番号を再発行しない。 */
final class IntakeLedger {
    long issued;
    Tag failure;
    static final class Serializer implements IAttachmentSerializer<Tag,IntakeLedger> {
        @Override public IntakeLedger read(IAttachmentHolder holder,Tag tag,HolderLookup.Provider registries) {
            var ledger=new IntakeLedger();
            if(!(tag instanceof CompoundTag data)||!data.contains("format",Tag.TAG_INT)||data.getInt("format")!=1
                    ||!data.contains("issued",Tag.TAG_LONG)||data.getLong("issued")<0)ledger.failure=tag.copy();
            else ledger.issued=data.getLong("issued");
            return ledger;
        }
        @Override public Tag write(IntakeLedger ledger,HolderLookup.Provider registries) {
            if(ledger.failure!=null)return ledger.failure.copy();
            var tag=new CompoundTag();tag.putInt("format",1);tag.putLong("issued",ledger.issued);return tag;
        }
    }
}
