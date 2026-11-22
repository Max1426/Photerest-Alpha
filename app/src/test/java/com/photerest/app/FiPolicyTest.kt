package com.photerest.app

import org.junit.Assert.*
import org.junit.Test

class FiPolicyTest {
    @Test fun onlineRequiresAllThreeIndependentConditions() {
        for (enabled in listOf(false,true)) for (google in listOf(false,true)) for (authorized in listOf(false,true)) {
            assertEquals(enabled && google && authorized, fiOnlineReason(enabled,google,authorized) == null)
        }
    }
    @Test fun reasonsExplainTheFirstActionableCondition() {
        assertEquals("φ 智能尚未开启",fiOnlineReason(false,true,true))
        assertEquals("Google 扩展尚未开启",fiOnlineReason(true,false,true))
        assertTrue(fiOnlineReason(true,true,false)!!.contains("已授权设备"))
    }
}
