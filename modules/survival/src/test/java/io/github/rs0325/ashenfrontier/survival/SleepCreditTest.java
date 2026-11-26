package io.github.rs0325.ashenfrontier.survival;

import io.github.rs0325.ashenfrontier.core.time.DayLengthHistory;
import io.github.rs0325.ashenfrontier.core.time.GameTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SleepCreditTest {
    static final class Credit {
        final Body body=new Body();long observed,cursor,sleepGeneration,consumed,lastCredit=-1;
        boolean credit(long generation,long tick,double elapsed,boolean valid) {
            var result=Physiology.creditSleep(body,generation,consumed,tick,lastCredit,elapsed,valid);
            consumed=result.consumed();lastCredit=result.lastCredit();return result.applied();
        }
    }
    @Test void sevenGameDaysAndNoBonusToOtherBudgets() {
        var state=new Credit();state.observed=0;state.cursor=0;
        for(int day=0;day<7;day++) {
            state.body.sleepDebt+=12*3600;
            state.sleepGeneration++;state.observed+=24000;state.cursor=state.observed;
            double[] before=state.body.values();
            assertTrue(state.credit(state.sleepGeneration,state.observed,86400,true));
            assertEquals(0,state.body.sleepDebt);
            var after=state.body.values();
            for(int i=0;i<before.length;i++)if(i!=8)assertEquals(before[i],after[i],"返済は負債だけ");
            assertFalse(state.credit(state.sleepGeneration,state.observed,86400,true));
        }
    }
    @Test void cooldownUsesOriginalCompletionAndDayLengthHistory() {
        var data=new Credit();data.observed=24000;data.cursor=24000;data.sleepGeneration=1;
        data.body.sleepDebt=80000;assertTrue(data.credit(1,0,0,true));
        var history=new DayLengthHistory(0,24000);history.change(6000,12000);
        data.sleepGeneration=2;data.body.sleepDebt=50000;
        assertEquals(43200,GameTime.gameSeconds(0,9000,history));
        assertFalse(data.credit(2,8999,GameTime.gameSeconds(0,8999,history),true));
        data.sleepGeneration=3;
        assertTrue(data.credit(3,9000,GameTime.gameSeconds(0,9000,history),true));
        assertEquals(0,data.body.sleepDebt);
    }
    @Test void invalidAndReplayedCompletionsDoNotGrantCredit() {
        var data=new Credit();data.body.sleepDebt=70000;data.sleepGeneration=1;data.observed=200;data.cursor=200;
        assertFalse(data.credit(1,100,999999,false));assertEquals(70000,data.body.sleepDebt);
        assertFalse(data.credit(1,101,999999,true));assertEquals(70000,data.body.sleepDebt);
    }
}
