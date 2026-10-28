package com.freemax.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val PALETTE = MarkdownPalette(
    link = Color(0xFF0061A4),
    codeBackground = Color(0x1F000000),
    codeText = Color.Black,
    quote = Color.Gray,
    bullet = Color.Gray
)

class MarkdownTextTest {

    @Test
    fun plainTextIsUnchanged() {
        val src = "Привет, как дела? Позвони позже."
        val out = renderMarkdown(src, 16.sp, PALETTE)
        assertEquals(src, out.text)
        assertTrue(out.spanStyles.isEmpty())
        assertTrue(out.getLinkAnnotations(0, out.length).isEmpty())
    }

    @Test
    fun httpAndBareUrlsBecomeLinks() {
        val src = "Смотри https://max.ru/dev и www.max.ru, почта test@max.ru, тел tel:+79990001122"
        val out = renderMarkdown(src, 16.sp, PALETTE)
        assertEquals(src, out.text)
        val links = out.getLinkAnnotations(0, out.length)
        assertEquals(4, links.size)
        val urls = links.map { (it.item as LinkAnnotation.Url).url }
        assertTrue(urls.contains("https://max.ru/dev"))
        assertTrue(urls.contains("https://www.max.ru")) // www. получает схему
        assertTrue(urls.contains("mailto:test@max.ru"))
        assertTrue(urls.any { it.startsWith("tel:") })
    }

    @Test
    fun trailingPunctuationStaysOutsideLink() {
        val src = "Ссылка: https://max.ru/dev."
        val out = renderMarkdown(src, 16.sp, PALETTE)
        assertEquals(src, out.text)
        val link = out.getLinkAnnotations(0, out.length).single().item as LinkAnnotation.Url
        assertEquals("https://max.ru/dev", link.url)
    }

    @Test
    fun boldItalicUnderlineStrikeAndCode() {
        val src = "**жирный** *курсив* __подчёркнутый__ ~~зачёркнутый~~ `код`"
        val out = renderMarkdown(src, 16.sp, PALETTE)
        assertEquals("жирный курсив подчёркнутый зачёркнутый код", out.text)
        val boldRange = 0 until "жирный".length
        assertTrue(out.spanStyles.any { it.start == 0 && it.item.fontWeight == FontWeight.Bold })
        assertTrue(out.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
        assertTrue(out.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
        assertTrue(out.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(out.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(boldRange.first == 0)
    }

    @Test
    fun nestedBoldAndItalic() {
        val out = renderMarkdown("**жирный *и курсив* внутри**", 16.sp, PALETTE)
        assertEquals("жирный и курсив внутри", out.text)
        // Внутренний курсив сохраняет и жирность, и курсив (вложенность)
        assertTrue(
            out.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle != null }
        )
    }

    @Test
    fun markdownLink() {
        val out = renderMarkdown("[MAX](https://max.ru)", 16.sp, PALETTE)
        assertEquals("MAX", out.text)
        val link = out.getLinkAnnotations(0, out.length).single().item as LinkAnnotation.Url
        assertEquals("https://max.ru", link.url)
    }

    @Test
    fun headingsListsAndQuotes() {
        val src = "# Заголовок\n- пункт\n1. первый\n> цитата"
        val out = renderMarkdown(src, 16.sp, PALETTE)
        assertEquals("Заголовок\n•  пункт\n1.  первый\n▏ цитата", out.text)
        assertTrue(out.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun unbalancedMarkersStayPlain() {
        val src = "2 * 2 = 4 и _курсив_"
        val out = renderMarkdown(src, 16.sp, PALETTE)
        // непарный "*" остаётся как есть; валидный _курсив_ размечается
        assertEquals("2 * 2 = 4 и курсив", out.text)
    }

    @Test
    fun linkInsideBoldIsClickable() {
        val out = renderMarkdown("**важно** [читать](https://max.ru/dev)", 16.sp, PALETTE)
        assertEquals("важно читать", out.text)
        assertEquals(1, out.getLinkAnnotations(0, out.length).size)
    }
}
