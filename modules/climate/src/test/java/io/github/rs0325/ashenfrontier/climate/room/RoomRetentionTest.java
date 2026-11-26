package io.github.rs0325.ashenfrontier.climate.room;

import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomRetentionTest {
    @Test void leasesSurviveTimeAndReverseReleaseAndResends() {
        var book=new RoomRetention();var owner=new UUID(135,1);
        var first=book.retain(owner,1,20,0);
        assertSame(first,book.retain(owner,1,20,0));
        book.retain(owner,2,20,10);
        assertTrue(book.protectedPosition(20));
        book.end(new RoomRetention.Key(owner,2),30,2);
        assertFalse(book.release(new RoomRetention.Key(owner,2)));
        book.commit(new RoomRetention.Key(owner,2),30,2);
        assertTrue(book.release(new RoomRetention.Key(owner,2)));
        assertTrue(book.protectedPosition(20));
        book.end(first.key,20,3);book.commit(first.key,20,3);
        assertTrue(book.release(first.key));assertTrue(book.release(first.key));
        book.commit(first.key,20,3);
        assertEquals(2,book.heads.get(owner).released);
        assertFalse(book.protectedPosition(20));
        assertThrows(IllegalArgumentException.class,()->book.retain(owner,1,20,0));
    }
    @Test void generalBudgetAndPhysicalReferencesNeverEvictProtection() {
        var book=new RoomRetention();
        book.retain(new UUID(135,2),1,-1,0);book.physical(-2);
        int full=0,releaseDelay=0;
        for(int i=0;i<100000;i++) {
            while(!book.observe(i,i,1,220)) {
                Long candidate=book.candidate(i);assertNotNull(candidate);
                assertTrue(candidate>=0);book.forget(candidate);full++;
            }
            assertTrue(book.general.size()<=4096&&book.references<=16384&&book.bytes<=8*1024*1024);
            assertTrue(book.protectedPosition(-1)&&book.protectedPosition(-2));
            if(i%3000==0) {
                book.physical(i);
                assertFalse(book.general.containsKey((long)i));releaseDelay++;
            }
        }
        assertTrue(full>=32);assertTrue(releaseDelay>=32);
        assertTrue(book.protectedPosition(-1));
        System.out.printf("M3-135 retention observations=100000 full=%d delayed_physical=%d%n",full,releaseDelay);
    }
    @Test void byteReferenceExpiryAndMoreThanWarningCapacity() {
        var book=new RoomRetention();book.positions=3;book.referenceLimit=4;book.byteLimit=500;
        assertTrue(book.observe(1,0,2,200));assertTrue(book.observe(2,1,2,200));
        assertFalse(book.observe(3,2,1,10));book.forget(book.candidate(2));
        assertFalse(book.observe(4,3,1,400));book.forget(book.candidate(3));
        assertTrue(book.observe(4,4,1,400));
        assertEquals(4,book.candidate(4+86400));
        for(int i=0;i<16385;i++)book.retain(new UUID(135,i+100),1,i,0);
        assertEquals(16385,book.leases.size());
        assertTrue(book.protectedPosition(16384));
    }
    @Test void releasedGenerationsAreRangesAcrossOneLongLivedHole() {
        var book=new RoomRetention();var owner=new UUID(135,500);
        book.retain(owner,1,1,0);
        for(int i=2;i<=20000;i++) {
            var key=new RoomRetention.Key(owner,i);book.retain(owner,i,2,0);
            book.end(key,1,0);book.commit(key,1,0);assertTrue(book.release(key));
        }
        assertEquals(1,book.heads.get(owner).ended.size());
        assertEquals(1,book.leases.size());
        var key=new RoomRetention.Key(owner,1);book.end(key,1,0);book.commit(key,1,0);book.release(key);
        assertEquals(20000,book.heads.get(owner).released);assertTrue(book.heads.get(owner).ended.isEmpty());
    }
    @Test void expiryUsesElapsedGameSecondsAcrossDayLengthChanges() {
        var history=new DayLengthHistory(0,24000);
        var book=new RoomRetention();
        assertTrue(book.observe(1,0,1,220));
        history.change(12000,12000);
        double before=GameTime.gameSeconds(0,17999,history);
        double boundary=GameTime.gameSeconds(0,18000,history);
        assertNull(book.candidate(before));
        assertEquals(1L,book.candidate(boundary));
        book.forget(1);
        assertFalse(book.general.containsKey(1L));
    }

    @Test void temporaryReadersAreSkippedWithoutBecomingPhysicalReferences() {
        var book=new RoomRetention();book.positions=2;
        assertTrue(book.observe(1,0,1,220));assertTrue(book.observe(2,1,1,220));
        assertFalse(book.observe(3,2,1,220));
        assertEquals(2,book.candidate(2,java.util.Set.of(1L)));
        assertFalse(book.protectedPosition(1));book.forget(2);
        assertTrue(book.observe(3,2,1,220));
        assertEquals(1,book.candidate(86400));
    }

}
