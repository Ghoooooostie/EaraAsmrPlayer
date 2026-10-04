package com.asmr.player.ui.common

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.asmr.player.util.DisplaySegment
import com.asmr.player.util.FURIGANA_READING_SCALE
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.annotateLine
import com.asmr.player.util.orPlainFallback

/**
 * 把带语言标记的字幕分段拼成展示文本，并按需给日文段的汉字插入小字读音。
 *
 * 中文段永不进词典；关闭注音或没有日文段时输出与纯文本完全一致，
 * 这样新功能不可能意外改变任何现有显示。
 * [plainFallback] 是调用点本行原本的展示文本，分段为空或全是空白段时由它兜底（修订 6）。
 * [separator] 只在跑马灯那种「多行折叠成一行」的渲染点传 " "，默认 \n 与 `displayText` 一致。
 */
fun buildFuriganaAnnotatedString(
    segments: List<DisplaySegment>,
    plainFallback: String,
    furigana: FuriganaSpec,
    rubyFontSize: TextUnit,
    separator: String = "\n"
): AnnotatedString {
    val resolved = resolveSegments(segments, plainFallback)
    if (!furigana.enabled || resolved.none { it.japanese }) {
        return AnnotatedString(resolved.joinToString(separator) { it.text })
    }
    val builder = AnnotatedString.Builder()
    for ((index, segment) in resolved.withIndex()) {
        if (index > 0) builder.append(separator)
        if (!segment.japanese) {
            builder.append(segment.text)
            continue
        }
        for (token in annotateLine(segment.text, furigana.source)) {
            builder.append(token.base)
            val reading = token.reading ?: continue
            val start = builder.length
            builder.append(reading)
            builder.addStyle(
                style = SpanStyle(fontSize = rubyFontSize),
                start = start,
                end = builder.length
            )
        }
    }
    return builder.toAnnotatedString()
}

/** TextView（悬浮歌词覆盖层）用的富文本；关闭注音时返回原始 String，不产生 Spanned。 */
fun buildFuriganaSpanned(
    segments: List<DisplaySegment>,
    plainFallback: String,
    furigana: FuriganaSpec
): CharSequence {
    val resolved = resolveSegments(segments, plainFallback)
    if (!furigana.enabled || resolved.none { it.japanese }) {
        return furiganaPlainText(resolved, plainFallback)
    }
    val builder = SpannableStringBuilder()
    for ((index, segment) in resolved.withIndex()) {
        if (index > 0) builder.append('\n')
        if (!segment.japanese) {
            builder.append(segment.text)
            continue
        }
        for (token in annotateLine(segment.text, furigana.source)) {
            builder.append(token.base)
            val reading = token.reading ?: continue
            val start = builder.length
            builder.append(reading)
            builder.setSpan(
                RelativeSizeSpan(FURIGANA_READING_SCALE),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    return builder
}

/**
 * 分段的纯文本拼接。开关关闭时必须与调用点原本的展示文本完全一致，
 * 因此段与段的连接符固定为 \n，且不做 trim。
 */
fun furiganaPlainText(segments: List<DisplaySegment>, plainFallback: String): String =
    resolveSegments(segments, plainFallback).joinToString("\n") { it.text }

/**
 * 丢掉空白段（挂起的中文行、纯空格的一行），再在什么都不剩时用 [plainFallback] 兜底。
 * 两个构建器共用这一条规则，避免出现「某处漏了兜底、整行画成空白」。
 */
private fun resolveSegments(
    segments: List<DisplaySegment>,
    plainFallback: String
): List<DisplaySegment> =
    segments.filter { it.text.isNotBlank() }.orPlainFallback(plainFallback)

/** 供调用点算注音字号：跟随该行实际使用的文字样式。 */
fun furiganaRubyFontSize(style: TextStyle): TextUnit =
    style.fontSize * FURIGANA_READING_SCALE
