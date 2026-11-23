package com.networkcontrol.android.automation

import com.networkcontrol.android.automation.engine.RuleEngine
import com.networkcontrol.android.automation.model.GroupOverride
import com.networkcontrol.android.automation.parser.GkdSubscriptionParser
import com.networkcontrol.android.automation.parser.Json5Preprocessor
import li.gkd.selector.NodeAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCoreTest {

    @Test
    fun json5PreprocessorHandlesCommentsSingleQuotesUnquotedKeysAndTrailingCommas() {
        val json5 = """
            // 頂部註釋
            {
              id: 1001,
              name: '社群訂閱 "測試"', /* 區塊註釋 */
              version: +42,
              apps: [
                {
                  id: 'com.demo.app',
                  groups: [
                    { key: 0, name: '開屏廣告', rules: '@[text="跳過"] < LinearLayout', },
                  ],
                },
              ],
            }
        """.trimIndent()

        val parsed = GkdSubscriptionParser.parse(json5)
        assertEquals(1001L, parsed.subscription.id)
        assertEquals("社群訂閱 \"測試\"", parsed.subscription.name)
        assertEquals(42, parsed.subscription.version)
        assertEquals(0, parsed.skippedRuleCount)
        assertEquals(1, parsed.subscription.apps.size)
        assertEquals("com.demo.app", parsed.subscription.apps[0].id)
    }

    @Test
    fun parserSkipsInvalidSelectorWithoutFailingWholeSubscription() {
        val json5 = """
            {
              id: 2,
              name: "容錯測試",
              version: 3,
              apps: [
                {
                  id: "com.example.demo",
                  groups: [
                    {
                      key: 1,
                      name: "混合規則",
                      rules: [
                        { key: 0, matches: '[text="跳過"]' },
                        { key: 1, matches: '[[[無效語法===' }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = GkdSubscriptionParser.parse(json5, validateSelectors = true)
        assertEquals(1, parsed.skippedRuleCount)
        assertEquals(1, parsed.subscription.apps[0].groups[0].rules.size)
        assertEquals(0, parsed.subscription.apps[0].groups[0].rules[0].key)
    }

    @Test
    fun ruleEngineMatchesRespectsCdMaximumPreKeysAndOverrides() {
        val json5 = """
            {
              id: 10,
              name: "測試訂閱",
              version: 1,
              apps: [
                {
                  id: "com.demo.video",
                  groups: [
                    {
                      key: 1,
                      name: "開屏跳過",
                      activityIds: [".SplashActivity"],
                      actionCd: 1000,
                      actionMaximum: 2,
                      rules: [
                        {
                          key: 0,
                          matches: 'TextView[text^="跳過"]',
                          excludeMatches: 'TextView[text="不可跳過"]',
                          action: "click"
                        },
                        {
                          key: 1,
                          preKeys: [0],
                          matches: 'Button[text="確認關閉"]'
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val sub = GkdSubscriptionParser.parse(json5).subscription
        val engine = RuleEngine(listOf(sub), emptyList())

        assertTrue(engine.hasRulesForApp("com.demo.video"))
        assertFalse(engine.hasRulesForApp("com.other.app"))

        val root = TestNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                TestNode(className = "android.widget.TextView", text = "跳過 3s"),
                TestNode(className = "android.widget.Button", text = "確認關閉"),
            ),
        )
        val adapter = TestNodeAdapter()

        // Activity 不匹配時不觸發
        assertNull(engine.evaluate("com.demo.video", "com.demo.video.MainActivity", root, adapter, 1000L))

        // Activity 匹配（支援短名稱 .SplashActivity），首次命中 key=0
        val hit1 = engine.evaluate("com.demo.video", "com.demo.video.SplashActivity", root, adapter, 2000L)
        assertNotNull(hit1)
        assertEquals(0, hit1!!.rule.ruleKey)
        assertEquals("跳過 3s", hit1.targetNode.text)

        // 同一毫秒內 key=0 在 actionCd 中，但 preKeys=[0] 的 key=1 應該能命中！
        val hit2 = engine.evaluate("com.demo.video", "com.demo.video.SplashActivity", root, adapter, 2100L)
        assertNotNull(hit2)
        assertEquals(1, hit2!!.rule.ruleKey)
        assertEquals("確認關閉", hit2.targetNode.text)

        // actionMaximum=2 已達上限，後續不再觸發
        assertNull(engine.evaluate("com.demo.video", "com.demo.video.SplashActivity", root, adapter, 5000L))

        // 覆寫關閉該規則組後，不再有任何規則
        val disabledEngine = RuleEngine(
            listOf(sub),
            listOf(GroupOverride(subscriptionId = 10, appId = "com.demo.video", groupKey = 1, enabled = false)),
        )
        assertFalse(disabledEngine.hasRulesForApp("com.demo.video"))
    }

    @Test
    fun parseVersionInfoReadsCheckUpdatePayload() {
        val payload = "{ id: 10, version: 205, }"
        assertEquals(205, GkdSubscriptionParser.parseVersionInfo(payload))
        assertEquals("{\"a\":1}", Json5Preprocessor.toStandardJson("{a:1,}").replace(" ", ""))
    }

    @Test
    fun parsesBundledDefaultCommunitySubscriptionAsset() {
        val assetFile = java.io.File("src/main/assets/default_gkd.json5.gz")
        assertTrue("Bundled default_gkd.json5.gz should exist", assetFile.exists())
        val raw = java.util.zip.GZIPInputStream(assetFile.inputStream()).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        val parsed = GkdSubscriptionParser.parse(raw, validateSelectors = true)
        assertEquals(667L, parsed.subscription.id)
        assertTrue("Should parse hundreds of apps", parsed.subscription.apps.size > 500)
        assertTrue("Should parse global groups", parsed.subscription.globalGroups.isNotEmpty())
    }

    private class TestNode(
        val className: String,
        val text: String? = null,
        val id: String? = null,
        val children: List<TestNode> = emptyList(),
    ) {
        var parent: TestNode? = null

        init {
            children.forEach { it.parent = this }
        }
    }

    private class TestNodeAdapter : NodeAdapter<TestNode>() {
        override fun getAttr(target: Any, name: String): Any? {
            val node = target as? TestNode ?: return null
            return when (name) {
                "name" -> node.className
                "text" -> node.text
                "id" -> node.id
                "vid" -> node.id?.substringAfter(":id/", node.id)
                else -> null
            }
        }

        override fun getName(node: TestNode): String = node.className
        override fun getChildCount(node: TestNode): Int = node.children.size
        override fun getChild(node: TestNode, index: Int): TestNode? = node.children.getOrNull(index)
        override fun getParent(node: TestNode): TestNode? = node.parent
        override fun getNodeKey(node: TestNode): Any = node
    }
}
