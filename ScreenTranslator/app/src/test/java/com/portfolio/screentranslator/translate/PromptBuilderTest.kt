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
        assertEquals(listOf(1, 3), blocks.map { it.sourceId })
        assertEquals("잘 가", blocks[1].text)
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
            blocks = listOf(SourceBlock(1, "こんにちは", android.graphics.Rect(), vertical = true)),
            imageJpeg = null, imageWidth = 0, imageHeight = 0, screenWidth = 1, screenHeight = 1,
            sourceLangHint = "ja", history = listOf("前" to "이전"),
        )
        val t = PromptBuilder.userText(req)
        assertTrue(t.contains("Source language: Japanese"))
        assertTrue(t.contains("[id=1] (vertical) こんにちは"))
        assertTrue(t.contains("前 => 이전"))
    }

    @Test
    fun languageGuess() {
        assertEquals("ja", LangDetect.guess("今日はいい天気ですね"))
        assertEquals("zh", LangDetect.guess("今天天气很好"))
        assertEquals("en", LangDetect.guess("Hello there"))
    }
}
