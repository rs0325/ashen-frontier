package io.github.rs0325.ashenfrontier.survival;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/** NeoForgeが読込例外を破棄して初期化する前に、破損原文を評価不能状態として保持する。 */
final class PhysiologyAttachment implements IAttachmentSerializer<Tag,PhysiologyData> {
    public PhysiologyData read(IAttachmentHolder holder,Tag tag,HolderLookup.Provider provider) {
        return read(tag);
    }
    static PhysiologyData read(Tag tag) {
        try {
            if(!(tag instanceof CompoundTag compound))throw new IllegalArgumentException("生理保存がcompoundではありません");
            return PhysiologyData.load(compound);
        } catch(RuntimeException error) {
            System.getLogger(PhysiologyAttachment.class.getName()).log(System.Logger.Level.ERROR,
                    "保存破損のため生理評価を停止します。原文を保全します",error);
            return PhysiologyData.failed(tag);
        }
    }
    public Tag write(PhysiologyData state,HolderLookup.Provider provider) {
        return state.blocked()?state.failure.copy():state.save();
    }
}
