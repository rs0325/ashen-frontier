package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.climate.AshenFrontierClimate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(AshenFrontierClimate.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RetentionSaveGameTests {
    @GameTest(template="empty",templateNamespace=AshenFrontierClimate.MOD_ID)
    public static void rejectsMissingOrInconsistentRetention(GameTestHelper h) {
        var book=new RoomRetention();var owner=new UUID(135,10);
        book.retain(owner,1,42,0);book.retain(owner,2,43,0);
        book.end(new RoomRetention.Key(owner,2),10,0);
        book.commit(new RoomRetention.Key(owner,2),10,0);
        book.release(new RoomRetention.Key(owner,2));
        var valid=RoomRetentionCodec.save(book);
        var loaded=RoomRetentionCodec.load(valid,c->1,c->220);
        h.assertTrue(loaded.leases.size()==1&&loaded.heads.get(owner).ended(2),"終了世代の穴を保存復元");
        int rejected=0;
        for(String field:new String[]{"heads","leases","physical","general"}) {
            var broken=valid.copy();broken.remove(field);
            if(rejects(broken))rejected++;
        }
        var missing=valid.copy();missing.getList("leases",Tag.TAG_COMPOUND).clear();
        if(rejects(missing))rejected++;
        var reversed=valid.copy();
        reversed.getList("leases",Tag.TAG_COMPOUND).getCompound(0).putLong("committed",-1);
        if(rejects(reversed))rejected++;
        var fieldMissing=valid.copy();
        fieldMissing.getList("leases",Tag.TAG_COMPOUND).getCompound(0).remove("position");
        if(rejects(fieldMissing))rejected++;
        h.assertTrue(rejected==7,"欠落や不整合を解放済み/空の保持として受け入れない");
        var old=new CompoundTag();old.putInt("format",9);
        boolean rejectedOld=false;
        try { RoomAir.verify(old); } catch(IllegalStateException expected) { rejectedOld=true; }
        h.assertTrue(rejectedOld,"format=9を移行せず拒否");
        h.succeed();
    }
    private static boolean rejects(CompoundTag tag) {
        try { RoomRetentionCodec.load(tag,c->1,c->220);return false; }
        catch(IllegalArgumentException expected) { return true; }
    }
}
