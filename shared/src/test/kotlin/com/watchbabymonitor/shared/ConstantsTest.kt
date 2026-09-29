package com.watchbabymonitor.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class ConstantsTest {
    @Test
    fun logTag_hasPrefix() {
        assertEquals("WBM/MonitorService", Constants.logTag("MonitorService"))
    }
}
