package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.engine.blocklist.BlocklistCatalog
import com.networkcontrol.android.kotlin.engine.blocklist.BlocklistManager
import com.networkcontrol.android.kotlin.engine.blocklist.BlocklistMatcher
import com.networkcontrol.android.kotlin.engine.blocklist.TrackerCategory
import com.networkcontrol.android.kotlin.engine.blocklist.TrackerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TrackerTest {
    /** 與 Disconnect services.json 相同的結構（節錄）。 */
    private val sample = """
        {
          "license": "CC BY-NC-SA 4.0",
          "categories": {
            "Advertising": [
              { "Google": { "http://www.google.com/": ["doubleclick.net", "googlesyndication.com"] } },
              { "Example Ads": { "https://ads.example/": ["ads.example"], "performance": "true" } }
            ],
            "Analytics": [
              { "Google": { "http://www.google.com/": ["google-analytics.com"] } },
              { "Example Ads": { "https://ads.example/": ["ads.example"] } }
            ],
            "FingerprintingInvasive": [
              { "Fp Corp": { "https://fp.example/": ["fp.example"] } }
            ],
            "Content": [
              { "Google": { "http://www.google.com/": ["ytimg.com"] } }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun parsesDisconnectFormatAndLooksUpSubdomains() {
        val db = TrackerDatabase.parse(sample)
        assertEquals(6, db.size)
        assertEquals(3, db.companies)
        val dc = db.lookup("stats.g.doubleclick.net")!!
        assertEquals("Google", dc.company)
        assertEquals(TrackerCategory.ADVERTISING, dc.category)
        assertEquals(TrackerCategory.ANALYTICS, db.lookup("www.google-analytics.com")!!.category)
        assertEquals(TrackerCategory.FINGERPRINTING, db.lookup("fp.example")!!.category)
        assertNull(db.lookup("google.com"))
        assertNull(db.lookup(null))
    }

    @Test
    fun moreSevereCategoryWinsForDuplicates() {
        val db = TrackerDatabase.parse(sample)
        // ads.example 同時在 Advertising 與 Analytics，取 Advertising
        assertEquals(TrackerCategory.ADVERTISING, db.lookup("x.ads.example")!!.category)
    }

    @Test
    fun domainInSeveralCategoriesIsBlockedByAnyOfThem() {
        val json = sample.replace(
            "\"FingerprintingInvasive\": [",
            "\"FingerprintingInvasive\": [\n              { \"Google\": { \"http://www.google.com/\": [\"google-analytics.com\"] } },",
        )
        val db = TrackerDatabase.parse(json)
        val info = db.lookup("google-analytics.com")!!
        // 主要類別以用途為準（統計），但同時屬於指紋
        assertEquals(TrackerCategory.ANALYTICS, info.category)
        assertTrue(TrackerCategory.FINGERPRINTING in info.categories)
        assertEquals("tracker:Fingerprinting", BlocklistMatcher(emptyList(), db, setOf(TrackerCategory.FINGERPRINTING)).match("google-analytics.com"))
        assertEquals("tracker:Analytics", BlocklistMatcher(emptyList(), db, setOf(TrackerCategory.ANALYTICS)).match("google-analytics.com"))
    }

    @Test
    fun matcherBlocksOnlySelectedCategories() {
        val db = TrackerDatabase.parse(sample)
        val identifyOnly = BlocklistMatcher(emptyList(), db, emptySet())
        assertTrue(identifyOnly.isEmpty)
        assertNull(identifyOnly.match("doubleclick.net"))
        assertEquals("Google", identifyOnly.tracker("doubleclick.net")!!.company)

        val blocking = BlocklistMatcher(emptyList(), db, setOf(TrackerCategory.ANALYTICS))
        assertFalse(blocking.isEmpty)
        assertEquals("tracker:Analytics", blocking.match("google-analytics.com"))
        assertNull(blocking.match("doubleclick.net"))
        assertNull(blocking.match("ytimg.com"))
    }

    @Test
    fun managerDownloadsTrackerDatabaseIndependentlyOfBlocklists() = runBlocking {
        val http = MiniHttpServer { _, _ -> MiniHttpServer.Response(200, emptyMap(), sample.toByteArray()) }
        val dir = Files.createTempDirectory("trk").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = BlocklistManager(
                dir,
                scope,
                // 所有地址都導向本機測試伺服器
                open = { java.net.URL("http://127.0.0.1:${http.port}/services.json").openConnection() as java.net.HttpURLConnection },
                autoDownload = false,
            )
            manager.initialize()
            manager.setSourceEnabled(BlocklistCatalog.TRACKER_SOURCE_ID, true)
            val result = manager.updateNow()
            assertEquals(1, result.updated)
            assertEquals("Google", manager.matcher.value.tracker("doubleclick.net")!!.company)
            // 總開關關閉：只識別，不攔截
            assertNull(manager.matcher.value.match("doubleclick.net"))

            manager.setEnabled(true)
            manager.setTrackerCategoryBlocked(TrackerCategory.ADVERTISING, true)
            assertEquals("tracker:Advertising", manager.matcher.value.match("doubleclick.net"))

            // 重新載入後從檔案恢復
            val reloaded = BlocklistManager(dir, scope, open = { error("offline") }, autoDownload = false)
            reloaded.initialize()
            assertEquals("tracker:Advertising", reloaded.matcher.value.match("ad.doubleclick.net"))
            assertTrue(reloaded.state.value.lists.none { it.id == BlocklistCatalog.TRACKER_SOURCE_ID })
        } finally {
            scope.cancel()
            http.close()
            dir.deleteRecursively()
        }
    }
}
