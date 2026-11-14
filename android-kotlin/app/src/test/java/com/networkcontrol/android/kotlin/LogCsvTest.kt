package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.Verdict
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.model.VerdictReason
import com.networkcontrol.android.kotlin.data.transfer.LogCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCsvTest {
    @Test
    fun encodesBomAndRfc4180Quoting() {
        val entry = LogEntry(
            id = 1,
            firstTs = 1_700_000_000_000,
            lastTs = 1_700_000_000_500,
            appId = 10,
            appPath = "android-app://com.example.app",
            appName = "Example, Inc.",
            direction = Direction.Outbound,
            protocol = L4Protocol.Tcp,
            remoteIp = "93.184.216.34",
            remotePort = 443,
            hostname = "api\"example.com",
            verdict = Verdict(VerdictAction.Deny, VerdictReason.Rule, "rule-1"),
            count = 3,
            local = null,
        )
        val csv = LogCsv.encode(listOf(entry))
        assertTrue(csv.startsWith("\uFEFFfirst_seen,"))
        assertTrue(csv.contains("\r\n"))
        assertTrue(csv.contains("\"Example, Inc.\""))
        assertTrue(csv.contains("\"api\"\"example.com\""))
        assertTrue(csv.contains(",deny,Rule,rule-1,3\r\n"))
        assertEquals("\"a,b\"", LogCsv.escape("a,b"))
        assertEquals("plain", LogCsv.escape("plain"))
        assertEquals("\"line1\nline2\"", LogCsv.escape("line1\nline2"))
        assertEquals("'=cmd()", LogCsv.escape("=cmd()"))
        assertEquals("\"'+cmd,1\"", LogCsv.escape("+cmd,1"))
    }
}
