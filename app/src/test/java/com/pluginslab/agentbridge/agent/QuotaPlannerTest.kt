package com.pluginslab.agentbridge.agent

import org.junit.Assert.*
import org.junit.Test

class QuotaPlannerTest {
    private val now=100000000L
    @Test fun requestsAreSpacedWithSafetyMargin() {
        val ledger=QuotaPlanner.admit(QuotaLedger(),"one",100,now)
        assertEquals(now+4200,QuotaPlanner.readyAt(ledger,QuotaLimits(15),100,true,now))
    }
    @Test fun minuteWindowAlsoReservesRoomForDialogue() {
        val ledger=QuotaLedger(uses=(1..14).map { QuotaUse("$it",now-50000+it*100,1) })
        val autonomous=QuotaPlanner.readyAt(ledger,QuotaLimits(15),1,false,now)
        val owner=QuotaPlanner.readyAt(ledger,QuotaLimits(15),1,true,now)
        assertTrue(autonomous>owner)
    }
    @Test fun tokenBudgetWaitsForEnoughInputTokensToExpire() {
        val ledger=QuotaLedger(uses=listOf(QuotaUse("one",now-50000,180000),QuotaUse("two",now-10000,50000)))
        assertEquals(now+10100,QuotaPlanner.readyAt(ledger,QuotaLimits(tpm=250000),50000,true,now))
    }
    @Test fun dailyBudgetDoesNotPretendOneMinuteWillFixIt() {
        val ledger=QuotaLedger(uses=listOf(QuotaUse("one",now-1000,1),QuotaUse("two",now-500,1)))
        assertTrue(QuotaPlanner.readyAt(ledger,QuotaLimits(rpd=2),1,true,now)>=now+QuotaPlanner.DAY-1000)
    }
    @Test fun serverRetryAfterIsSharedAndNeverShortened() {
        val first=QuotaPlanner.block(QuotaLedger(),now+120000,"429")
        val second=QuotaPlanner.block(first,now+1000,"503")
        assertEquals(now+120000,QuotaPlanner.readyAt(second,QuotaLimits(),1,true,now))
    }
    @Test fun measuredUsageReplacesConservativeReservation() {
        val first=QuotaPlanner.admit(QuotaLedger(),"one",100000,now)
        val measured=QuotaPlanner.measured(first,"one",10000)
        assertEquals(now,QuotaPlanner.readyAt(measured,QuotaLimits(tpm=25000),12000,true,now))
    }
    @Test fun oversizedRequestIsRejectedInsteadOfWaitingForever() {
        assertThrows(IllegalArgumentException::class.java) { QuotaPlanner.readyAt(QuotaLedger(),QuotaLimits(tpm=100),101,true,now) }
    }
    @Test fun expiredUsageIsPrunedAndZeroMeansUnknownLimit() {
        val ledger=QuotaLedger(uses=listOf(QuotaUse("old",now-QuotaPlanner.DAY-1,100)))
        assertTrue(QuotaPlanner.prune(ledger,now).uses.isEmpty())
        assertEquals(now,QuotaPlanner.readyAt(ledger,QuotaLimits(),999999,true,now))
    }
}
