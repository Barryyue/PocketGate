package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.release.NoteLanguage
import com.networkcontrol.android.kotlin.core.release.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val texts = ReleaseNotes.all.flatMap { it.features + it.improvements + it.notes }

    @Test
    fun everyEntryHasAllThreeLanguages() {
        texts.forEach { text ->
            assertTrue("missing English: $text", text.en.isNotBlank())
            assertTrue("missing Simplified Chinese: $text", text.zhHans.isNotBlank())
            assertTrue("missing Traditional Chinese: $text", text.zhHant.isNotBlank())
            assertTrue("English text contains CJK: ${text.en}", text.en.none { it in '一'..'鿿' })
        }
    }

    @Test
    fun simplifiedAndTraditionalAreNotMixed() {
        // 常見的簡繁對應字：簡體文字不該出現右側的繁體字，反之亦然。
        val pairs = "这這 说說 应應 设設 网網 选選 开開 关關 点點 线線 项項 显顯 动動 时時 间間 岛島 规規 则則 会會 录錄 询詢 转轉 发發 连連 拦攔 页頁 级級"
            .split(' ')
        val simplified = pairs.map { it[0] }.toSet()
        val traditional = pairs.map { it[1] }.toSet()
        texts.forEach { text ->
            val wrongInHans = text.zhHans.filter { it in traditional }
            val wrongInHant = text.zhHant.filter { it in simplified }
            assertTrue("Traditional characters '$wrongInHans' in: ${text.zhHans}", wrongInHans.isEmpty())
            assertTrue("Simplified characters '$wrongInHant' in: ${text.zhHant}", wrongInHant.isEmpty())
        }
    }

    @Test
    fun languageFollowsUiLanguageTag() {
        assertEquals(NoteLanguage.TraditionalChinese, NoteLanguage.fromTag("zh-TW"))
        assertEquals(NoteLanguage.TraditionalChinese, NoteLanguage.fromTag("zh-Hant-HK"))
        assertEquals(NoteLanguage.SimplifiedChinese, NoteLanguage.fromTag("zh-CN"))
        assertEquals(NoteLanguage.SimplifiedChinese, NoteLanguage.fromTag("zh-Hans"))
        assertEquals(NoteLanguage.English, NoteLanguage.fromTag("en-US"))
        assertEquals(NoteLanguage.English, NoteLanguage.fromTag("ja-JP"))
    }

    @Test
    fun currentVersionMatchesBuildSuffix() {
        assertEquals("0.9.10", ReleaseNotes.current("0.9.10-m4p11-debug")?.version)
        assertEquals("0.9.9", ReleaseNotes.current("0.9.9-m4p10-debug")?.version)
        assertEquals("0.9.8", ReleaseNotes.current("0.9.8-m4p9-debug")?.version)
        assertEquals("0.9.7", ReleaseNotes.current("0.9.7-m4p8-debug")?.version)
        assertEquals("0.9.6", ReleaseNotes.current("0.9.6-m4p7-debug")?.version)
        assertEquals("0.9.5", ReleaseNotes.current("0.9.5-m4p6-debug")?.version)
        assertEquals("0.9.4", ReleaseNotes.current("0.9.4-m4p5-debug")?.version)
        assertEquals("0.9.2", ReleaseNotes.current("0.9.2-m4p3-debug")?.version)
        assertEquals("0.9.1", ReleaseNotes.current("0.9.1-m4p2")?.version)
        val text = ReleaseNotes.current("0.9.1-m4p2")!!.features.first()
        assertTrue(text[NoteLanguage.English].startsWith("In forwarding mode"))
    }
}
