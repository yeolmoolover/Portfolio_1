package com.portfolio.screentranslator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun parsesBlocksAndSfxFromNoisyReply() {
        val raw = """Sure, here it is:
            {"blocks":[{"id":1,"ko":"안녕"},{"id":2,"ko":""},{"id":3,"ko":"잘 가"}],
             "sfx":[{"ko":"쾅","box":[0.1,0.2,0.3,0.1]},{"ko":"","box":[0,0,1,1]},{"ko":"x","box":[0.5,0.5,0,0.2]}]}
            trailing text""".trimIndent()
        val items = PromptBuilder.parse(raw)
        val blocks = items.filter { !it.isSfx }
        val sfx = items.filter { it.isSfx }
        // id 2 came back empty: kept as an empty item so the caller can count it as "skipped"
        assertEquals(listOf(1, 2, 3), blocks.map { it.sourceId })
        assertEquals("", blocks[1].text)
        assertEquals("잘 가", blocks[2].text)
        assertEquals(1, sfx.size)
        assertNull(sfx[0].sourceId)
        assertEquals(0.3f, sfx[0].boxNorm!![2], 1e-6f)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsReplyWithoutJson() {
        PromptBuilder.parse("I cannot translate this.")
    }

    @Test
    fun userTextListsBlocksAndHistory() {
        val req = TranslationRequest(
            blocks = listOf(SourceBlock(1, "こんにちは", android.graphics.Rect(), vertical = true, lines = emptyList(), lineHeight = 0, hintX = 0)),
            imageJpeg = null, imageWidth = 0, imageHeight = 0, screenWidth = 1, screenHeight = 1,
            sourceLangHint = "ja", history = listOf("前" to "이전"),
        )
        val t = PromptBuilder.userText(req)
        assertTrue(t.contains("Source language: Japanese"))
        assertTrue(t.contains("[id=1] (vertical) こんにちは"))
        assertTrue(t.contains("前 => 이전"))
    }

}

class HangulSkipTest {
    @Test
    fun koreanBlocksAreDetected() {
        assertTrue(com.portfolio.screentranslator.ocr.OcrEngine.isMostlyHangul("사용 편의성에 대해 말이야"))
        assertTrue(com.portfolio.screentranslator.ocr.OcrEngine.isMostlyHangul("Portfolio_1 · 화면에서 번역하십시오"))
        assertEquals(false, com.portfolio.screentranslator.ocr.OcrEngine.isMostlyHangul("今日はいい天気ですね"))
        assertEquals(false, com.portfolio.screentranslator.ocr.OcrEngine.isMostlyHangul("Hello world 123"))
    }
}

class IncrementalParserTest {
    @Test
    fun emitsEachBlockOnceAsChunksArrive() {
        val got = ArrayList<TranslatedText>()
        val p = PromptBuilder.IncrementalParser { got += it }
        val full = """{"blocks":[{"id":1,"ko":"고마워\"요\""},{"id": 2 , "ko" : "잘 먹을게"},{"id":3,"ko":""}],"sfx":[{"ko":"쾅","box":[0.1,0.1,0.2,0.1]}]}"""
        // feed in awkward pieces, including one that splits a JSON escape
        val cuts = listOf(10, 25, 31, 44, 70, 90, full.length)
        var prev = 0
        for (c in cuts) { p.feed(full.substring(prev, c)); prev = c }
        assertEquals(listOf(1, 2), got.map { it.sourceId })
        assertEquals("고마워\"요\"", got[0].text)
        assertEquals("잘 먹을게", got[1].text)
        // feeding more text never re-emits
        p.feed(" trailing")
        assertEquals(2, got.size)
    }
}
