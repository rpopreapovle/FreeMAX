package com.freemax.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * Цвета для markdown-рендера. Заполняются из MaterialTheme вызывающим кодом,
 * чтобы текст одинаково читался и в своих, и в чужих пузырях.
 */
data class MarkdownPalette(
    val link: Color,
    val codeBackground: Color,
    val codeText: Color,
    val quote: Color,
    val bullet: Color
)

private data class InlineFlags(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val inLink: Boolean = false
)

private const val MAX_DEPTH = 8

/**
 * Базовый markdown без внешних зависимостей.
 *
 * Блоки: заголовки #/##/###, маркированные (-, *, •) и нумерованные (1., 2.)
 * списки, цитаты (>). Инлайн: **жирный**, *курсив*, _курсив_, __подчёркнутый__,
 * `код`, ~~зачёркнутый~~, ссылки [текст](url), автоссылки (http/https, www.,
 * max.ru, mailto:, tel:, e-mail).
 *
 * Если в тексте нет markdown — возвращается исходная строка (плюс автоссылки),
 * поэтому plain-текст не портится.
 */
fun renderMarkdown(
    text: String,
    baseFontSize: TextUnit = TextUnit.Unspecified,
    palette: MarkdownPalette,
    block: Boolean = true
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        if (!block) {
            appendInline(this, text, InlineFlags(), palette, 0)
            return@buildAnnotatedString
        }
        val lines = text.split('\n')
        var index = 0
        while (index < lines.size) {
            if (index > 0) append('\n')
            appendBlockLine(this, lines[index], baseFontSize, palette)
            index++
        }
    }
}

private fun appendBlockLine(
    b: AnnotatedString.Builder,
    raw: String,
    baseFontSize: TextUnit,
    palette: MarkdownPalette
) {
    val line = raw.trimEnd()
    // Цитата: "> text", ">> text", ">> > text"
    val quoteMatch = Regex("^\\s*((?:>\\s?)+)(.*)$").find(line)
    if (quoteMatch != null) {
        val depth = quoteMatch.groupValues[1].count { it == '>' }
        val body = quoteMatch.groupValues[2]
        val bar = "▏".repeat(depth.coerceIn(1, 3))
        b.withStyle(SpanStyle(color = palette.quote, fontStyle = FontStyle.Italic)) {
            append(bar)
            append(' ')
            appendInline(b, body, InlineFlags(inLink = false), palette, 0)
        }
        return
    }
    // Заголовок: # / ## / ### + пробел
    val headingMatch = Regex("^(#{1,3})\\s+(.*)$").find(line)
    if (headingMatch != null) {
        val level = headingMatch.groupValues[1].length
        val factor = when (level) {
            1 -> 1.35f
            2 -> 1.2f
            else -> 1.1f
        }
        val style = SpanStyle(
            fontWeight = FontWeight.Bold,
            fontSize = if (baseFontSize.isSpecified) baseFontSize * factor else TextUnit.Unspecified
        )
        b.withStyle(style) {
            appendInline(b, headingMatch.groupValues[2], InlineFlags(bold = true), palette, 0)
        }
        return
    }
    // Маркированный список: -, *, •
    val bulletMatch = Regex("^\\s*([-*•])\\s+(.*)$").find(line)
    if (bulletMatch != null) {
        b.withStyle(SpanStyle(color = palette.bullet)) { append("•") }
        b.append("  ")
        appendInline(b, bulletMatch.groupValues[2], InlineFlags(), palette, 0)
        return
    }
    // Нумерованный список: 1. / 1)
    val numberMatch = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$").find(line)
    if (numberMatch != null) {
        b.withStyle(SpanStyle(color = palette.bullet, fontWeight = FontWeight.Medium)) {
            append(numberMatch.groupValues[1] + ".")
        }
        b.append("  ")
        appendInline(b, numberMatch.groupValues[2], InlineFlags(), palette, 0)
        return
    }
    appendInline(b, line, InlineFlags(), palette, 0)
}

private fun appendInline(
    b: AnnotatedString.Builder,
    src: String,
    flags: InlineFlags,
    palette: MarkdownPalette,
    depth: Int
) {
    if (depth > MAX_DEPTH) {
        appendStyled(b, src, flags, palette)
        return
    }
    var i = 0
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            appendAutolinks(b, plain.toString(), flags, palette)
            plain.clear()
        }
    }
    while (i < src.length) {
        val c = src[i]
        // Экранирование: \* и т.п.
        if (c == '\\' && i + 1 < src.length && src[i + 1] in "\\`*_~[]()#>-") {
            plain.append(src[i + 1]); i += 2; continue
        }
        // `код`
        if (c == '`') {
            val end = src.indexOf('`', i + 1)
            if (end > i) {
                flush()
                b.withStyle(codeStyle(palette)) { append(src.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        // [текст](url)
        if (c == '[') {
            val close = src.indexOf(']', i + 1)
            if (close > i && close + 1 < src.length && src[close + 1] == '(') {
                val end = src.indexOf(')', close + 2)
                if (end > close) {
                    val label = src.substring(i + 1, close)
                    val url = src.substring(close + 2, end).trim()
                    if (url.isNotEmpty() && !url.contains(' ')) {
                        flush()
                        b.withLink(LinkAnnotation.Url(url)) {
                            appendInline(
                                this,
                                if (label.isBlank()) url else label,
                                flags.copy(inLink = true),
                                palette,
                                depth + 1
                            )
                        }
                        i = end + 1
                        continue
                    }
                }
            }
        }
        val handled = tryMarker(src, i, flags) { start, end, newFlags ->
            flush()
            appendInline(b, src.substring(start, end), newFlags, palette, depth + 1)
        }
        if (handled > i) {
            i = handled
            continue
        }
        plain.append(c)
        i++
    }
    flush()
}

/**
 * Пробует распознать парный inline-маркер начиная с позиции [i].
 * Возвращает позицию после маркера или [i], если маркера нет.
 */
private inline fun tryMarker(
    src: String,
    i: Int,
    flags: InlineFlags,
    emit: (start: Int, end: Int, flags: InlineFlags) -> Unit
): Int {
    val c = src[i]
    // **жирный** / __подчёркнутый__
    if (c == '*' && src.startsWith("**", i)) {
        val end = src.indexOf("**", i + 2)
        if (end > i + 2) {
            emit(i + 2, end, flags.copy(bold = true))
            return end + 2
        }
    }
    if (c == '_' && src.startsWith("__", i)) {
        val end = src.indexOf("__", i + 2)
        if (end > i + 2) {
            emit(i + 2, end, flags.copy(underline = true))
            return end + 2
        }
    }
    // ~~зачёркнутый~~
    if (c == '~' && src.startsWith("~~", i)) {
        val end = src.indexOf("~~", i + 2)
        if (end > i + 2) {
            emit(i + 2, end, flags.copy(strike = true))
            return end + 2
        }
    }
    // *курсив* / _курсив_ (одиночный маркер, не часть **/__)
    if (c == '*' && !src.startsWith("**", i)) {
        val end = src.indexOf('*', i + 1)
        if (end > i + 1 && !src.startsWith("**", end)) {
            emit(i + 1, end, flags.copy(italic = true))
            return end + 1
        }
    }
    if (c == '_' && !src.startsWith("__", i)) {
        val end = src.indexOf('_', i + 1)
        if (end > i + 1 && !src.startsWith("__", end)) {
            emit(i + 1, end, flags.copy(italic = true))
            return end + 1
        }
    }
    return i
}

private fun spanStyle(flags: InlineFlags, palette: MarkdownPalette): SpanStyle {
    if (flags == InlineFlags()) return SpanStyle()
    return SpanStyle(
        fontWeight = if (flags.bold) FontWeight.Bold else null,
        fontStyle = if (flags.italic) FontStyle.Italic else null,
        textDecoration = when {
            flags.underline && flags.strike -> TextDecoration.combine(
                listOf(TextDecoration.Underline, TextDecoration.LineThrough)
            )
            flags.underline -> TextDecoration.Underline
            flags.strike -> TextDecoration.LineThrough
            flags.inLink -> TextDecoration.Underline
            else -> null
        },
        color = if (flags.inLink) palette.link else Color.Unspecified
    )
}

private fun codeStyle(palette: MarkdownPalette) = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = palette.codeBackground,
    color = palette.codeText
)

private fun linkStyle(palette: MarkdownPalette) =
    SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)

private fun appendStyled(
    b: AnnotatedString.Builder,
    text: String,
    flags: InlineFlags,
    palette: MarkdownPalette
) {
    val style = spanStyle(flags, palette)
    if (style == SpanStyle()) b.append(text) else b.withStyle(style) { append(text) }
}

private val AUTOLINK = Regex(
    "(?i)(?<![\\p{L}\\p{N}@._%+\\-])(?:" +
        "https?://[^\\s<>\"'«»]+|" +
        "www\\.[^\\s<>\"'«»]+|" +
        "mailto:[^\\s<>\"'«»]+|" +
        "tel:\\+?\\d[\\d\\s()\\-]*\\d|" +
        "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}|" +
        "(?:[A-Za-z0-9\\-]+\\.)+(?:ru|com|org|net|io|me|su|by|kz|ua|de|fr|app|dev|info|biz)(?:/[^\\s<>\"'«»]*)?" +
        ")"
)

private const val TRAILING = ".,;:!?»)\"'…"

private fun appendAutolinks(
    b: AnnotatedString.Builder,
    chunk: String,
    flags: InlineFlags,
    palette: MarkdownPalette
) {
    if (chunk.isEmpty()) return
    var last = 0
    for (m in AUTOLINK.findAll(chunk)) {
        val start = m.range.first
        if (start > last) appendStyled(b, chunk.substring(last, start), flags, palette)
        var display = m.value
        var trailing = ""
        while (display.isNotEmpty() && display.last() in TRAILING) {
            // Не отрезаем ')' если в url есть парная '('
            if (display.last() == ')' && display.count { it == '(' } >= display.count { it == ')' }) break
            trailing = display.last() + trailing
            display = display.dropLast(1)
        }
        if (display.isEmpty()) {
            appendStyled(b, m.value, flags, palette)
        } else {
            val uri = when {
                display.startsWith("http://", true) || display.startsWith("https://", true) -> display
                display.startsWith("www.", true) -> "https://$display"
                display.startsWith("mailto:", true) || display.startsWith("tel:", true) -> display
                display.contains('@') -> "mailto:$display"
                else -> "https://$display"
            }
            b.withLink(LinkAnnotation.Url(uri)) {
                b.withStyle(linkStyle(palette)) { append(display) }
            }
            if (trailing.isNotEmpty()) b.append(trailing)
        }
        last = m.range.last + 1
    }
    if (last < chunk.length) appendStyled(b, chunk.substring(last), flags, palette)
}
