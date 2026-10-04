# 日语汉字注音开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增一个默认关闭的设置开关，开启后在播放页、歌词页、悬浮歌词三处，于日文汉字的右侧以小字号平假名显示读音。

**Architecture:** 三层单向依赖——`util` 提供纯 JVM 的注音切分算法与行级缓存；`data/reading` 读 gzip 资产实现词典查询；`ui/common` 把切分结果映射成 Compose `AnnotatedString` 或 TextView `Spanned`。字幕模型增加语言分段，保证中文译文永不进词典；`entry.text` 的压平行为不变，因此导出文件保持纯文本。

**Tech Stack:** Kotlin、Jetpack Compose（BOM 2024.02.00 / compiler ext 1.5.8）、Jetpack DataStore Preferences、Room 无关、Hilt、JUnit4 + Robolectric 4.11.1、Python 3（资产生成脚本）。

**Spec:** `docs/superpowers/specs/2026-10-04-japanese-furigana-design.md`（commit `a71177f`）——执行时两份一起读。

## 对 spec 的 8 处修订

实现计划基于 spec，但写计划时逐行核对代码后发现下列偏差。这些是修正而非降级，执行者按本文档为准：

1. **§6.5 第 4 行改做法。** spec 要求把 `NowPlayingLyricsPreviewContent.current`/`upcoming` 的 `String` 换成 `AnnotatedString`。这些对象的 `equals` 参与 `remember(sourceContent, …)` 与 `visibleUpcomingCount` 的 key，而 `AnnotatedString` 不是 data class、`equals` 语义不可靠，一旦每帧判定为「变了的」就会在滚动动画中反复触发 `textMeasurer.measure`。**改为：这些状态对象继续只存 plain `String`（含已有的 `lyricIndex`），注音在绘制与测量点用 `annotateLine` 就地派生**，由行级 LRU 承担成本。结果是预览句也能注音，覆盖面反而更大。
2. **§6.1 字号实现方式。** spec 写 `SpanStyle(fontSize = 0.55.em)`。仓库锁的是 Compose BOM 2024.02.00，`em` 在 `SpanStyle.fontSize` 上的解析行为不值得赌。**改为显式绝对字号**：常量 `FURIGANA_READING_SCALE = 0.55f` + `furiganaRubyFontSize(style: TextStyle): TextUnit` 助手，调用点传入自己那行实际生效的 `TextStyle`。跟随用户字号的效果不变，且测试可精确断言。`RelativeSizeSpan(0.55f)` 侧保持原样。
3. **§3.1 `ReadingSource` 增加 `ready`。** §8 的「未就绪或加载失败时不写 LRU 缓存」这条自愈规则，在接口上没有任何就绪信号时无法实现（加载失败的词典照样能返回 null，缓存就把「不注音」钉死了）。加 `val ready: Boolean`。
4. **§4.2 单字回退表的派生规则收紧。** spec 说「同一汉字在所有词条中出现时取加权最高读音」——多汉字词的读音无法对齐到单个汉字，那样派生必然出错。**收紧为：只用「恰好含一个汉字」的词条派生**（`食べる/たべる` → 食=た，去掉送假名尾部），同字多读时取出现次数最多者。覆盖面小一些，但不会产出错读。
5. **§9.5 测试范式引用错了文件。** `data/local/datastore/SearchCacheStoreTest.kt` 是纯函数测试，不碰 DataStore。真正的往返测试范式在 `app/src/test/java/com/asmr/player/ui/library/LibraryPreferencesStoreTest.kt`（`@RunWith(RobolectricTestRunner::class)` + `PreferenceDataStoreFactory.create` + 临时文件）。
6. **§6.4 补一条空分段兜底规则（写计划时发现的真实缺陷）。** `CHINESE` 模式下 `displaySegments` 是空列表，如果渲染点直接把分段交给构建器，中文模式整行会渲染成空字符串——等于把现有歌词全屏蔽掉。因此所有渲染/测量点必须经过 `List<DisplaySegment>.orPlainFallback(plain)`（Task 3 定义），空分段时退回一个 `japanese = false` 的段。这条规则的测试在 Task 3 和 Task 8 各有一处。
7. **§6.5 补上跑马灯那个接缝。** `NowPlayingScreen.kt:2440` 走的是 `marqueeCurrentLine = true` 分支，文本由 `SlowMarqueeText` 内部的 `normalizeSingleLineText` 折叠成单行。分段渲染会绕开这个折叠，所以注音构建器需要一个 `separator` 参数（Task 6），跑马灯点用 `marqueeLyricText`（Task 9）逐段折叠 + 空格连接，并把 `SlowMarqueeText` 的首参从 `String` 换成已折叠好的 `AnnotatedString`。spec 原本按「5 行表」计数，跑马灯是表里新增的第 6 行；接缝本身在原表里被 `NowPlayingControls` 那一行含糊带过了。
8. **§6.4 的分段函数必须带双语顺序参数（前置依赖）。** 同一仓库里正在推进的「双语上下顺序」特性已经把 `displayText` / `withDisplayMode` 改成两参数形式（`app/src/test/java/com/asmr/player/util/SubtitleDisplayModeTest.kt` 已在断 `displayText(mode, SubtitleBilingualOrder.JAPANESE_FIRST)`），且 `BILINGUAL` 默认变成**日文在上**。spec 写的「`withDisplayMode` 签名不变」因此不成立。**本计划以两参数形状为准编写所有代码**：`displaySegmentsFor(mode, order)` 的分段顺序必须与 `displayText(mode, order)` 逐字等价（否则 §6.2 的逐字节一致与 `orPlainFallback` 兜底都会漂移）。**执行前置：Task 3 开工前先读 `util/SubtitleDisplayMode.kt` 的当前签名**——若该特性尚未合入（仍是单参数），把它报成阻塞项，不要在本计划里顺手实现双语顺序。

## Global Constraints

- `minSdk = 24`，`compileSdk = 36`，`targetSdk = 34`。禁止使用 Android 12+ 才有的 ruby/text 能力。
- 开关关闭、或该行没有日文段时，渲染输出必须与现状逐字节一致（Task 6 有专门断言）。
- 导出的字幕文件（`GeneratedSubtitleFileExporter`）内容不变，绝不写入假名。
- 不新增任何第三方依赖（词典靠自带资产 + 纯 Kotlin 实现，不引入 MeCab）。
- UI 文案继续硬编码简体中文；`res/values/strings.xml` 只有 `app_name`，不引入 i18n 系统。
- 注音字号只有 `FURIGANA_READING_SCALE` 这一个常量，不新增用户设置项。
- 词典数据 CC-BY-SA 4.0，必须随资产 ship `NOTICE.txt`。
- 单测命令（Git Bash，仓库根目录）：
  `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests '<FQCN>'"`
  需要时先 `cmd //c "gradlew-local.bat --stop"`；全量非增量约 3 分钟。
  增量缓存报出「找不到参数」类错误时，**先读被调方签名确认是真错**，只有排除后才加 `-Dkotlin.incremental=false`。
- 提交信息用仓库现有风格：`feat(player): …` / `fix(lyrics): …` / `docs(furigana): …`。

---

### Task 1: 注音切分算法（纯 JVM）

**Files:**
- Create: `app/src/main/java/com/asmr/player/util/JapaneseReadingSupport.kt`
- Test: `app/src/test/java/com/asmr/player/util/JapaneseReadingSupportTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class ReadingToken(val base: String, val reading: String? = null)`
  - `interface ReadingSource { val ready: Boolean; val maxSurfaceLength: Int; fun readingOf(surface: String): String?; fun kanjiReadingOf(kanji: Char): String? }`
  - `fun annotateJapanese(text: String, source: ReadingSource?): List<ReadingToken>`
  - `internal fun isKanji(ch: Char): Boolean` / `internal fun isKana(ch: Char): Boolean`（Task 4 的脚本校验与 Task 6 复用）

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/util/JapaneseReadingSupportTest.kt`：

```kotlin
package com.asmr.player.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseReadingSupportTest {

    private class FakeSource(
        private val words: Map<String, String>,
        private val singleKanji: Map<Char, String> = emptyMap(),
        override val ready: Boolean = true
    ) : ReadingSource {
        override val maxSurfaceLength: Int get() = words.keys.maxOfOrNull { it.length } ?: 0
        override fun readingOf(surface: String): String? = words[surface]
        override fun kanjiReadingOf(kanji: Char): String? = singleKanji[kanji]
    }

    /**
     * 断言 tokens 拼回的基字与 expectedBase 相同，且 (基字 -> 读音) 序列与 readings 一致。
     * readings 必须逐 token 列全：不注音的整段文本也是一个 token（读音为 null）。
     */
    private fun assertAnnotation(
        text: String,
        source: ReadingSource?,
        expectedBase: String,
        vararg readings: Pair<String, String?>
    ) {
        val tokens = annotateJapanese(text, source)
        assertEquals(expectedBase, tokens.joinToString("") { it.base })
        assertEquals(readings.toList(), tokens.map { it.base to it.reading })
    }

    @Test
    fun annotateJapanese_prefersWordOverSingleKanji() {
        val source = FakeSource(
            words = mapOf("昨日" to "きのう", "日" to "ひ"),
            singleKanji = mapOf('昨' to "さく", '日' to "にち")
        )
        assertAnnotation("昨日", source, "昨日", "昨日" to "きのう")
    }

    @Test
    fun annotateJapanese_extendsIntoOkurigana() {
        val source = FakeSource(
            words = mapOf("美味しい" to "おいしい", "美味" to "びみ"),
            singleKanji = mapOf('美' to "び", '味' to "み")
        )
        // 关键用例：没有送假名扩展就会显示成「びみしい」
        assertAnnotation(
            "美味しい",
            source,
            "美味しい",
            "美味" to "おい",
            "しい" to null
        )
    }

    @Test
    fun annotateJapanese_trimsOnlyMatchingOkuriganaTail() {
        val source = FakeSource(words = mapOf "難しい" to "むずかしい")
        assertAnnotation("難しい", source, "難しい", "難" to "むずか", "しい" to null)
    }

    @Test
    fun annotateJapanese_keepsFullReadingWhenReadingIsNotLongerThanTail() {
        // 词典里 行こう/こう：读音尾部并不包含送假名，dropLast 会把假名吃光，
        // 所以长度不够时必须整条保留——这是 trimReading 的长度守卫。
        val source = FakeSource(words = mapOf "行こう" to "こう")
        assertAnnotation("行こう", source, "行こう", "行" to "こう", "こう" to null)
    }

    @Test
    fun annotateJapanese_consumesKanjiPrefixPartially() {
        // 日本語 本身没收录，最长匹配退到前缀 日本，剩下的 語 交给下一轮。
        val source = FakeSource(words = mapOf("日本" to "にほん"))
        assertAnnotation(
            "日本語",
            source,
            "日本語",
            "日本" to "にほん",
            "語" to null
        )
    }

    @Test
    fun annotateJapanese_fallsBackToSingleKanji() {
        val source = FakeSource(words = emptyMap(), singleKanji = mapOf('猫' to "ねこ"))
        assertAnnotation("猫", source, "猫", "猫" to "ねこ")
    }

    @Test
    fun annotateJapanese_leavesUnknownKanjiUnannotated() {
        val source = FakeSource(words = emptyMap())
        assertAnnotation("囍", source, "囍", "囍" to null)
    }

    @Test
    fun annotateJapanese_neverAnnotatesKanaDigitsLatinAndPunctuation() {
        val source = FakeSource(words = mapOf("猫" to "ねこ"))
        assertAnnotation(
            "ねこ 12 abc 。",
            source,
            "ねこ 12 abc 。",
            "ねこ 12 abc 。" to null
        )
    }

    @Test
    fun annotateJapanese_absorbsParenthesisedReadingInAllFourBracketForms() {
        val source = FakeSource(words = emptyMap(), singleKanji = mapOf('漢' to "かん"))
        for (open in listOf('（', '(', '［', '[')) {
            val close = mapOf('（' to '）', '(' to ')', '［' to '］', '[' to ']').getValue(open)
            assertAnnotation(
                "漢${open}かん$close字",
                source,
                "漢字",
                "漢" to "かん",
                "字" to null
            )
        }
    }

    @Test
    fun annotateJapanese_keepsParenthesesWhenInsideIsNotPureKana() {
        val source = FakeSource(words = emptyMap())
        assertAnnotation("漢(A)字", source, "漢(A)字", "漢(A)字" to null)
    }

    @Test
    fun annotateJapanese_nullSourceReturnsPlainText() {
        assertEquals(listOf(ReadingToken("日本語")), annotateJapanese("日本語", null))
        assertEquals(emptyList<ReadingToken>(), annotateJapanese("", null))
    }

    @Test
    fun annotateJapanese_notReadySourceBehavesLikeNull() {
        val source = FakeSource(words = mapOf("日本" to "にほん"), ready = false)
        assertEquals(listOf(ReadingToken("日本")), annotateJapanese("日本", source))
    }

    @Test
    fun annotateJapanese_annotatesChineseBecauseCallerDecidesLanguageNotTheMatcher() {
        // 算法本身不判语言：中文段之所以不会被注音，靠的是 Task 3 的语言分段。
        val source = FakeSource(singleKanji = mapOf('中' to "ちゅう"))
        assertAnnotation("中文", source, "中文", "中" to "ちゅう", "文" to null)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.util.JapaneseReadingSupportTest'"`
Expected: 编译失败，`unresolved reference: ReadingToken` / `annotateJapanese`

- [ ] **Step 3: 写最小实现**

创建 `app/src/main/java/com/asmr/player/util/JapaneseReadingSupport.kt`：

```kotlin
package com.asmr.player.util

/**
 * 日文中汉字注音（行内小字假名）的切分算法。
 *
 * 本文件刻意不依赖 Android 与 Compose，只处理「给定文本 + 词典，怎么切」；
 * 是否该给这段文本注音由调用方通过语言分段决定，所以中文传进来一样会注音。
 */

/** 一个注音单元：[base] 为基字文本，[reading] 为需要小字显示的假名，null 表示不注音。 */
data class ReadingToken(
    val base: String,
    val reading: String? = null
)

/** 注音所需文本的统一字号比例，让注音跟随用户可调的歌词字号。 */
const val FURIGANA_READING_SCALE = 0.55f

/**
 * 读音查询能力。由 data 层的词典实现，算法层据此保持可在 JVM 上测试。
 * [ready] 为 false 时所有查询都不应该被信任，调用方需要按「不注音」处理。
 */
interface ReadingSource {
    val ready: Boolean

    /** 词典里最长的表层字数，用于限制匹配窗口。未就绪时为 0。 */
    val maxSurfaceLength: Int

    /** 词级读音查询；未收录返回 null。 */
    fun readingOf(surface: String): String?

    /** 单字回退读音；未收录返回 null。 */
    fun kanjiReadingOf(kanji: Char): String?
}

internal fun isKanji(ch: Char): Boolean =
    ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch in '\uF900'..'\uFAFF'

internal fun isKana(ch: Char): Boolean =
    ch in '\u3041'..'\u309F' || ch in '\u30A1'..'\u30FA' || ch == '\u30FC'

/** 括号形态与右括号的对应关系；用于吸收源文本里自带的注音。 */
private val rubyBrackets = mapOf('（' to '）', '(' to ')', '［' to '］', '[' to ']')

/** 送假名最多向后扩展几个假名，超过这个宽度就不再尝试词级匹配。 */
private const val maxOkuriganaLength = 4

/**
 * 把文本切成注音单元。[source] 为 null 或未就绪时整段原样返回（只有一个 token）。
 */
fun annotateJapanese(text: String, source: ReadingSource?): List<ReadingToken> {
    if (text.isEmpty()) return emptyList()
    if (source == null || !source.ready) return listOf(ReadingToken(text))

    val tokens = mutableListOf<ReadingToken>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            tokens += ReadingToken(plain.toString())
            plain.setLength(0)
        }
    }

    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (!isKanji(ch)) {
            plain.append(ch)
            i++
            continue
        }
        var runEnd = i
        while (runEnd < text.length && isKanji(text[runEnd])) runEnd++

        val kanjiPart = text.substring(i, runEnd)
        val absorbed = absorbedReadingAt(text, runEnd)
        if (absorbed != null) {
            flushPlain()
            tokens += ReadingToken(kanjiPart, absorbed.first)
            i = absorbed.second
            continue
        }

        val match = bestSurfaceMatch(text, i, runEnd, source)
        if (match != null) {
            flushPlain()
            val okurigana = text.substring(i + match.kanjiLength, i + match.surfaceLength)
            tokens += ReadingToken(kanjiPart.take(match.kanjiLength), trimReading(match.reading, okurigana))
            // 只跨过汉字部分，送假名留给下一轮原样输出，避免被二次匹配。
            i += match.kanjiLength
            continue
        }

        val singleReading = source.kanjiReadingOf(ch)
        if (singleReading != null) {
            flushPlain()
            tokens += ReadingToken(ch.toString(), singleReading)
        } else {
            plain.append(ch)
        }
        i++
    }
    flushPlain()
    return tokens
}

/** 返回「汉字段后面紧跟（假名）」的读音与其右括号之后的位置，否则 null。 */
private fun absorbedReadingAt(text: String, kanjiRunEnd: Int): Pair<String, Int>? {
    val open = text.getOrNull(kanjiRunEnd) ?: return null
    val close = rubyBrackets[open] ?: return null
    val contentStart = kanjiRunEnd + 1
    val closeIndex = text.indexOf(close, contentStart)
    if (closeIndex < 0) return null
    val content = text.substring(contentStart, closeIndex)
    if (content.isEmpty() || !content.all(::isKana)) return null
    return content to (closeIndex + 1)
}

private class SurfaceMatch(val kanjiLength: Int, val surfaceLength: Int, val reading: String)

/**
 * 最大正向匹配：先保证覆盖更多汉字，再在汉字段后扩展连续送假名，
 * 「单汉字且无送假名」的组合不在这里查（`continue` 跳过），交给
 * [ReadingSource.kanjiReadingOf] 兜底——单字表也从词库派生，两条路给出的是同一个读音，
 * 少一次二分查找。
 */
private fun bestSurfaceMatch(
    text: String,
    start: Int,
    runEnd: Int,
    source: ReadingSource
): SurfaceMatch? {
    val maxKanji = minOf(source.maxSurfaceLength, runEnd - start)
    // 送假名窗口只数「紧跟汉字段的连续假名」，否则窗口会伸进标点与空格，
    // 拼出一个永不在词典里的表层字（还白做几次查询）。
    var trailingKana = 0
    while (trailingKana < maxOkuriganaLength &&
        runEnd + trailingKana < text.length &&
        isKana(text[runEnd + trailingKana])
    ) trailingKana++
    for (kanjiLength in maxKanji downTo 1) {
        for (kanaLength in trailingKana downTo 0) {
            if (kanjiLength == 1 && kanaLength == 0) continue
            val surface = text.substring(start, start + kanjiLength + kanaLength)
            val reading = source.readingOf(surface) ?: continue
            return SurfaceMatch(kanjiLength, kanjiLength + kanaLength, reading)
        }
    }
    return null
}

/** 送假名已在基字里显示，就从读音尾部去掉，得到只属于汉字的注音。 */
private fun trimReading(reading: String, okurigana: String): String =
    if (okurigana.isNotEmpty() &&
        reading.length > okurigana.length &&
        reading.endsWith(okurigana)
    ) reading.dropLast(okurigana.length) else reading
```

注意：`kanjiPart.take(match.kanjiLength)` 是必须的——`bestSurfaceMatch` 可以只消耗汉字段的前缀（`日本語` → `日本`）。

`trailingKana` 必须按假名逐个计数而不是直接用 `text.length - runEnd`：后者会把紧跟汉字段的标点、空格、半角字符也圈进窗口，拼出「`美味。`」这类永不在词典里的表层字，既不命中又浪费查询次数。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.util.JapaneseReadingSupportTest'"`
Expected: 全部 PASS（13 个方法）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/util/JapaneseReadingSupport.kt \
        app/src/test/java/com/asmr/player/util/JapaneseReadingSupportTest.kt
git commit -m "feat(lyrics): add Japanese kanji reading splitter with okurigana matching"
```

---

### Task 2: 行级注音缓存与「未就绪不缓存」

**Files:**
- Modify: `app/src/main/java/com/asmr/player/util/JapaneseReadingSupport.kt`（文件末尾追加）
- Test: `app/src/test/java/com/asmr/player/util/JapaneseReadingSupportTest.kt`（追加方法）

**Interfaces:**
- Consumes: Task 1 的 `annotateJapanese`、`ReadingSource.ready`
- Produces: `fun annotateLine(text: String, source: ReadingSource?): List<ReadingToken>`（Task 6/8/9/10 唯一使用的入口）

- [ ] **Step 1: 写失败的测试**

在 `JapaneseReadingSupportTest` 里追加（沿用该文件的 `FakeSource`）：

```kotlin
    @Test
    fun annotateLine_reusesCachedResultForSameText() {
        var lookups = 0
        val counting = object : ReadingSource {
            private val delegate = FakeSource(mapOf("日本" to "にほん"))
            override val ready get() = true
            override val maxSurfaceLength get() = delegate.maxSurfaceLength
            override fun readingOf(surface: String): String? {
                lookups++
                return delegate.readingOf(surface)
            }
            override fun kanjiReadingOf(kanji: Char): String? = delegate.kanjiReadingOf(kanji)
        }

        val first = annotateLine("日本語", counting)
        val cachedCalls = lookups
        val second = annotateLine("日本語", counting)

        assertEquals(first, second)
        assertEquals(lookups, cachedCalls)
        org.junit.Assert.assertTrue("首调必须发生过词典查询", cachedCalls > 0)
    }

    @Test
    fun annotateLine_doesNotCacheWhileSourceIsNotReady() {
        val notReady = FakeSource(mapOf("日本" to "にほん"), ready = false)
        assertEquals(listOf(ReadingToken("日本語")), annotateLine("日本語", notReady))

        // 预热完成后，同一行必须立刻拿到注音——证明未就绪的结果没有进缓存。
        val ready = FakeSource(mapOf("日本" to "にほん"))
        val annotated = annotateLine("日本語", ready)
        org.junit.Assert.assertTrue(annotated.any { it.reading != null })
    }

    @Test
    fun annotateLine_nullSourceIsNotCached() {
        assertEquals(listOf(ReadingToken("漢字")), annotateLine("漢字", null))
        val ready = FakeSource(mapOf("漢" to "かん"), singleKanji = mapOf('漢' to "かん"))
        org.junit.Assert.assertTrue(annotateLine("漢字", ready).any { it.reading != null })
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.util.JapaneseReadingSupportTest'"`
Expected: 编译失败，`unresolved reference: annotateLine`

- [ ] **Step 3: 写最小实现**

在 `JapaneseReadingSupport.kt` 末尾追加：

```kotlin
private const val annotateCacheLimit = 2000

/**
 * 行级缓存。歌词滚动与悬浮歌词换句都会重复处理同一行，缓存让注音开销接近零。
 * 词典未就绪时的结果一律不写入，好让预热完成后下一次渲染自动带上注音，
 * 不需要「就绪」通知机制。
 */
private val annotateCache = object : LinkedHashMap<String, List<ReadingToken>>(64, 1f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReadingToken>>?): Boolean =
        size > annotateCacheLimit
}

fun annotateLine(text: String, source: ReadingSource?): List<ReadingToken> {
    val cacheable = source?.ready == true
    if (!cacheable) return annotateJapanese(text, source)
    val cached = synchronized(annotateCache) { annotateCache[text] }
    if (cached != null) return cached
    val result = annotateJapanese(text, source)
    synchronized(annotateCache) { annotateCache[text] = result }
    return result
}

/** 测试与词典热替换用；生产路径不调用。 */
internal fun clearAnnotateCache() = synchronized(annotateCache) { annotateCache.clear() }
```

测试之间共享这个进程级缓存，所以第二个和第三个测试刻意用不同文本或不依赖缓存语义的断言。若后续新增 `annotateLine` 测试导致串扰，在测试类里 `@Before fun setUp() { clearAnnotateCache() }`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.util.JapaneseReadingSupportTest'"`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/util/JapaneseReadingSupport.kt \
        app/src/test/java/com/asmr/player/util/JapaneseReadingSupportTest.kt
git commit -m "feat(lyrics): cache per-line furigana annotation and skip caching until dictionary is ready"
```

---

### Task 3: 字幕语言分段

**Files:**
- Modify: `app/src/main/java/com/asmr/player/util/SubtitleParser.kt:12-18`
- Modify: `app/src/main/java/com/asmr/player/util/SubtitleDisplayMode.kt:27-46`
- Test: `app/src/test/java/com/asmr/player/util/SubtitleDisplayModeTest.kt`（追加方法，**不改动现有断言**）

**Interfaces:**
- Consumes: `SubtitleEntry`；`SubtitleBilingualOrder`（修订 8，兄弟特性提供）
- Produces:
  - `data class DisplaySegment(val text: String, val japanese: Boolean)`
  - `val SubtitleEntry.displaySegments: List<DisplaySegment>`（模型字段）
  - `fun SubtitleEntry.displaySegmentsFor(mode: SubtitleDisplayMode, order: SubtitleBilingualOrder): List<DisplaySegment>`（Task 10 的悬浮歌词路径直接用，那里拿到的是未压平的原始列表）
  - `fun List<DisplaySegment>.orPlainFallback(plain: String): List<DisplaySegment>`（修订 6：每个渲染/测量点都必须经过它）
- 不变量：`displaySegmentsFor(mode, order).joinToString("\n") { it.text } == displayText(mode, order)`，**对所有 mode × order 组合成立**。`CHINESE` 时段列表为空，所以渲染点必须兜底。

- [ ] **Step 0: 核对前置**

打开 `app/src/main/java/com/asmr/player/util/SubtitleDisplayMode.kt`，确认 `displayText` 与 `withDisplayMode` 已经是 `(mode, order)` 两参数、且存在 `enum class SubtitleBilingualOrder { JAPANESE_FIRST, CHINESE_FIRST }`。若仍是单参数（双语顺序特性未合入），**停下报阻塞**，不要在本任务里替它实现顺序开关——本任务以下所有代码都假定两参数形状。

- [ ] **Step 1: 写失败的测试**

在 `SubtitleDisplayModeTest` 末尾追加：

```kotlin
    @Test
    fun displaySegmentsFor_chineseModeHasNoSegments() {
        assertEquals(
            emptyList<DisplaySegment>(),
            generated.displaySegmentsFor(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_japaneseModeMarksSingleJapaneseSegment() {
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true)),
            generated.displaySegmentsFor(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        // 没有日文原文时回退中文，且不能标记成日文，否则中文会被送进词典。
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            SubtitleEntry(0L, 1_000L, "中文")
                .displaySegmentsFor(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_bilingualFollowsSelectedOrderAndMarksOnlyTheJapaneseLine() {
        // 双语顺序由兄弟特性提供，分段顺序必须与 displayText 的压平顺序一致。
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true), DisplaySegment("中文", japanese = false)),
            generated.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false), DisplaySegment("日文", japanese = true)),
            generated.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_bilingualDegenerateCasesStaySingleSegmentAndMatchText() {
        val identical = SubtitleEntry(0L, 1_000L, "同じ", "同じ")
        assertEquals(
            listOf(DisplaySegment("同じ", japanese = false)),
            identical.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            SubtitleEntry(0L, 1_000L, "中文")
                .displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true)),
            SubtitleEntry(0L, 1_000L, "", "日文")
                .displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        )
    }

    @Test
    fun displaySegmentsFor_alwaysRoundTripsIntoDisplayText() {
        for (entry in listOf(
            generated,
            SubtitleEntry(0L, 1_000L, "中文"),
            SubtitleEntry(0L, 1_000L, "", "日文"),
            SubtitleEntry(0L, 1_000L, "同じ", "同じ")
        )) {
            for (mode in SubtitleDisplayMode.entries) {
                for (order in SubtitleBilingualOrder.entries) {
                    assertEquals(
                        entry.displayText(mode, order),
                        entry.displaySegmentsFor(mode, order).joinToString("\n") { it.text }
                    )
                }
            }
        }
    }

    @Test
    fun withDisplayMode_fillsSegmentsInSelectedOrder() {
        val japanese = listOf(generated)
            .withDisplayMode(SubtitleDisplayMode.JAPANESE, SubtitleBilingualOrder.JAPANESE_FIRST)
            .first()
        assertEquals(listOf(DisplaySegment("日文", japanese = true)), japanese.displaySegments)

        val bilingual = listOf(generated)
            .withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
            .first()
        assertEquals(
            listOf(DisplaySegment("日文", japanese = true), DisplaySegment("中文", japanese = false)),
            bilingual.displaySegments
        )
        assertEquals("日文\n中文", bilingual.text)
    }

    @Test
    fun orPlainFallback_keepsSegmentsWhenPresent() {
        val segments = listOf(DisplaySegment("日文", japanese = true))
        assertEquals(segments, segments.orPlainFallback("整行文本"))
    }

    @Test
    fun orPlainFallback_wrapsPlainTextAsNonJapaneseWhenSegmentsAreEmpty() {
        // 中文模式与外挂字幕都是空分段；不兜底的话渲染点会拿到空列表并画出空白行。
        assertEquals(
            listOf(DisplaySegment("中文", japanese = false)),
            emptyList<DisplaySegment>().orPlainFallback("中文")
        )
        assertEquals(
            listOf(DisplaySegment(" ", japanese = false)),
            emptyList<DisplaySegment>().orPlainFallback(" ")
        )
    }

    @Test
    fun withDisplayMode_chineseLeavesSegmentsEmpty() {
        val entry = listOf(generated.copy(displaySegments = listOf(DisplaySegment("旧", true))))
            .withDisplayMode(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST)
            .first()
        assertEquals(emptyList<DisplaySegment>(), entry.displaySegments)
        // 空分段必须能被渲染点安全兜底回原句文本——这是修订 6 的落点。
        assertEquals(
            listOf(DisplaySegment(entry.text, japanese = false)),
            entry.displaySegments.orPlainFallback(entry.text)
        )
    }
```

`withDisplayMode_chineseLeavesSegmentsEmpty` 会破坏 `withDisplayMode` 现在「CHINESE 直接返回同一个 list」的短路——见 Step 3 的处理方式，两个测试都必须成立。测试夹具 `generated` 沿用该文件现有的 `SubtitleEntry(0L, 1_000L, "中文", "日文")`；若兄弟特性改过夹具名，按现名写，不要重命名。

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.util.SubtitleDisplayModeTest'"`
Expected: 编译失败，`unresolved reference: DisplaySegment`

- [ ] **Step 3: 实现**

3a. `util/SubtitleParser.kt` 把 `SubtitleEntry` 换成：

```kotlin
data class SubtitleEntry(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** 自动生成的字幕同时保存日文原文；外挂字幕为空。 */
    val japaneseText: String = "",
    /**
     * 显示模式解析出的分段，供注音判断「这段文本是不是日文」。
     * 中文译文同样是汉字，所以必须由带语言标记的分段而不是渲染层来猜语言。
     * 空列表表示未解析（中文模式或原始未处理字幕），此时直接用 [text]。
     */
    val displaySegments: List<DisplaySegment> = emptyList()
)
```

3b. `util/SubtitleDisplayMode.kt` 在 `displayText` 之前插入 `DisplaySegment`，并把 `withDisplayMode` 改为共享同一个分段函数：

```kotlin
/** 一行字幕里的一段可独立处理的文本；[japanese] 为 true 才允许进注音词典。 */
data class DisplaySegment(val text: String, val japanese: Boolean)

/**
 * 空分段（中文模式、外挂字幕、未走 withDisplayMode 的原始列表）时退回一个不注音的纯文本段。
 * 所有渲染/测量点都必须经过它，否则这些模式下整行会画成空白。
 */
fun List<DisplaySegment>.orPlainFallback(plain: String): List<DisplaySegment> =
    if (isNotEmpty()) this else listOf(DisplaySegment(plain, japanese = false))
```

```kotlin
/**
 * 按显示模式与双语顺序把单条字幕拆成带语言标记的分段。
 * 与 [displayText] 必须逐字等价：分段按顺序拼接（\n 连接）就是该模式的展示文本。
 */
fun SubtitleEntry.displaySegmentsFor(
    mode: SubtitleDisplayMode,
    order: SubtitleBilingualOrder
): List<DisplaySegment> {
    val chinese = text.trim()
    val japanese = japaneseText.trim()
    val bilingual = listOf(
        DisplaySegment(chinese, japanese = false),
        DisplaySegment(japanese, japanese = true)
    )
    return when (mode) {
        SubtitleDisplayMode.CHINESE -> emptyList()
        SubtitleDisplayMode.JAPANESE -> when {
            japanese.isBlank() -> listOf(DisplaySegment(chinese, japanese = false))
            else -> listOf(DisplaySegment(japanese, japanese = true))
        }
        SubtitleDisplayMode.BILINGUAL -> when {
            japanese.isBlank() -> listOf(DisplaySegment(chinese, japanese = false))
            chinese.isBlank() -> listOf(DisplaySegment(japanese, japanese = true))
            japanese == chinese -> listOf(DisplaySegment(chinese, japanese = false))
            else -> if (order == SubtitleBilingualOrder.JAPANESE_FIRST) bilingual.reversed() else bilingual
        }
    }
}
```

`displayText` 的实现一个字都不改（它已经是 `(mode, order)` 两参数，且 `BILINGUAL` 分支已按顺序拼好）。`displaySegmentsFor` 只是把同一份判定按段暴露出来：`bilingual` 局部列表的构造顺序写成「中文在前」，`JAPANESE_FIRST` 时 `reversed()`，这样两条模式的顺序语义只在一个地方决定。若 `displaySegmentsFor_alwaysRoundTripsIntoDisplayText` 失败，说明 `displayText` 与这里的分支判定已经分叉——**以 `displayText` 为准修 `displaySegmentsFor`**，绝不反过来改 `displayText`（它同时被导出、通知与双语顺序特性使用）。

`withDisplayMode` 改为（保持两参数，只多填 `displaySegments`）：

```kotlin
/** 把整轨字幕的展示文本替换为指定模式与顺序下的文本，并填好语言分段供注音使用；时间轴与顺序保持不变。 */
fun List<SubtitleEntry>.withDisplayMode(
    mode: SubtitleDisplayMode,
    order: SubtitleBilingualOrder
): List<SubtitleEntry> {
    if (isEmpty()) return this
    if (mode == SubtitleDisplayMode.CHINESE) {
        // 中文模式不需要分段，但也不能残留上一次模式的分段。
        return map { if (it.displaySegments.isEmpty()) it else it.copy(displaySegments = emptyList()) }
    }
    return map { entry ->
        val segments = entry.displaySegmentsFor(mode, order)
        entry.copy(text = segments.joinToString("\n") { it.text }, displaySegments = segments)
    }
}
```

这会让 `withDisplayMode` 对 CHINESE 返回**新列表**，而现有测试 `withDisplayMode_chineseReturnsSameList`（`assertEquals(source, source.withDisplayMode(CHINESE, JAPANESE_FIRST))`）比较的是内容而非引用。源条目的 `displaySegments` 本来就是空，`map` 后逐字段相等，`assertEquals` 仍然通过——不需要改那个测试。

- [ ] **Step 4: 跑测试确认通过（含既有测试无回归）**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.util.*'"`
Expected: `SubtitleDisplayModeTest`、`SubtitleParserTest`、`SubtitleMatchSupportTest` 全 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/util/SubtitleParser.kt \
        app/src/main/java/com/asmr/player/util/SubtitleDisplayMode.kt \
        app/src/test/java/com/asmr/player/util/SubtitleDisplayModeTest.kt
git commit -m "feat(lyrics): tag subtitle display segments with source language"
```

---

### Task 4: 词典索引（纯解析 + 查询）

**Files:**
- Create: `app/src/main/java/com/asmr/player/data/reading/ReadingDictionaryIndex.kt`
- Test: `app/src/test/java/com/asmr/player/data/reading/ReadingDictionaryIndexTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ReadingSource`、`isKanji`、`isKana`（同模块 internal 不可见，本文件用公开 API，需要的字符判定自行实现）
- Produces:
  - `class ReadingDictionaryIndex private constructor(...) : ReadingSource`
  - `companion object { fun parse(wordLines: List<String>, kanjiLines: List<String>): ReadingDictionaryIndex }`，行格式 `表层\t读音`
  - `fun deriveKanjiLines(wordLines: List<String>): List<String>`（Task 11 的 Python 脚本做同样的事，这里给 Kotlin 侧一份可测试实现用于校验资产）

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/data/reading/ReadingDictionaryIndexTest.kt`：

```kotlin
package com.asmr.player.data.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingDictionaryIndexTest {

    private val words = listOf(
        "日本語\tにほんご",
        "日本\tにほん",
        "美味しい\tおいしい",
        "難しい\tむずかしい",
        "猫\tねこ",
        "食べる\tたべる"
    )

    private fun index(): ReadingDictionaryIndex =
        ReadingDictionaryIndex.parse(words, ReadingDictionaryIndex.deriveKanjiLines(words))

    @Test
    fun parse_isReadyAndExposesLongestSurface() {
        val index = index()
        assertTrue(index.ready)
        assertEquals(4, index.maxSurfaceLength) // 日本語 = 3 字，美味しい = 4 字
    }

    @Test
    fun readingOf_findsExactEntryRegardlessOfInputOrder() {
        assertEquals("にほんご", index().readingOf("日本語"))
        assertEquals("おいしい", index().readingOf("美味しい"))
    }

    @Test
    fun readingOf_returnsNullForUnknownSurface() {
        assertNull(index().readingOf("猫猫"))
        assertNull(index().readingOf(""))
    }

    @Test
    fun readingOf_ignoresLinesWithoutTabOrWithBlankReading() {
        val index = ReadingDictionaryIndex.parse(listOf("坏行", "空\t", "好\tかん"), emptyList())
        assertNull(index.readingOf("坏行"))
        assertNull(index.readingOf("空"))
        assertEquals("かん", index.readingOf("好"))
    }

    @Test
    fun parse_keepsFirstReadingForDuplicateSurface() {
        val index = ReadingDictionaryIndex.parse(
            listOf("日本\tにほん", "日本\tにほん", "日本\tやまと"),
            emptyList()
        )
        // 生成脚本已按词频排序，同表层多读音时保留先出现的一条；重复条目不得让查询崩溃或错乱。
        assertEquals("にほん", index.readingOf("日本"))
        assertEquals(3, index.surfaceCount)
    }

    @Test
    fun deriveKanjiLines_onlyUsesSingleKanjiWordsAndTrimsOkurigana() {
        val derived = ReadingDictionaryIndex.deriveKanjiLines(words).toMap()
        assertEquals("ねこ", derived.getValue("猫"))   // 无送假名，整条读音就是这个字
        assertEquals("た", derived.getValue("食"))     // 食べる/たべる 去掉送假名 べる
        assertEquals("むずか", derived.getValue("難")) // 難しい/むずかしい 去掉送假名 しい
        // 修订 4：含两个以上汉字的词条一律不参与派生——
        // 日本語/にほんご、美味しい/おいしい 的读音无法可靠切给单个汉字，宁缺勿错。
        assertFalse(derived.containsKey("日"))
        assertFalse(derived.containsKey("本"))
        assertFalse(derived.containsKey("美"))
        assertFalse(derived.containsKey("味"))
    }

    @Test
    fun deriveKanjiLines_prefersTheMostFrequentReadingPerKanji() {
        val lines = ReadingDictionaryIndex.deriveKanjiLines(
            listOf("行\tいく", "行\tこう", "行\tこう")
        ).toMap()
        assertEquals("こう", lines.getValue("行"))
    }

    @Test
    fun emptyDictionaryReportsNotReady() {
        val index = ReadingDictionaryIndex.parse(emptyList(), emptyList())
        assertFalse(index.ready)
        assertEquals(0, index.maxSurfaceLength)
        assertNull(index.kanjiReadingOf('猫'))
    }

    private fun List<String>.toMap(): Map<String, String> =
        associate { it.substringBefore('\t') to it.substringAfter('\t') }
}
```

`surfaceCount` 是给 Task 11 的资产体积校验用的（生成脚本按条数截断，Kotlin 侧要能核对）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.data.reading.ReadingDictionaryIndexTest'"`
Expected: 编译失败，`unresolved reference: ReadingDictionaryIndex`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/asmr/player/data/reading/ReadingDictionaryIndex.kt`：

```kotlin
package com.asmr.player.data.reading

import com.asmr.player.util.ReadingSource

/**
 * 只读读音索引：排序数组 + 二分查找。
 *
 * 用两个并行数组而不是 HashMap，是为了省掉哈希桶开销——手机上常驻内存预算 20 MB。
 * 算法层按窗口长度递减逐个精确查询，所以这里只需要精确匹配。
 */
class ReadingDictionaryIndex private constructor(
    private val surfaces: Array<String>,
    private val readings: Array<String>,
    private val kanjiChars: CharArray,
    private val kanjiReadings: Array<String>
) : ReadingSource {

    override val ready: Boolean get() = surfaces.isNotEmpty() || kanjiChars.isNotEmpty()

    override val maxSurfaceLength: Int = surfaces.maxOfOrNull { it.length } ?: 0

    /** 词条数，供资产体积校验使用。 */
    val surfaceCount: Int get() = surfaces.size

    override fun readingOf(surface: String): String? {
        if (surface.isEmpty()) return null
        val at = surfaces.binarySearchSurface(surface)
        return if (at >= 0) readings[at] else null
    }

    override fun kanjiReadingOf(kanji: Char): String? {
        val at = kanjiChars.binarySearch(kanji)
        return if (at >= 0) kanjiReadings[at] else null
    }

    private fun Array<String>.binarySearchSurface(key: String): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                this[mid] < key -> low = mid + 1
                this[mid] > key -> high = mid - 1
                // 重复表层（同表层多读音）时线性回退到第一条；
                // parse 用的是稳定排序，先出现的词条因此排在前面。
                else -> {
                    var first = mid
                    while (first > 0 && this[first - 1] == key) first--
                    return first
                }
            }
        }
        return -1
    }

    companion object {
        /**
         * 从 `表层\t读音` 行构建索引。非法行（无制表符、读音为空）直接丢弃。
         * [kanjiLines] 为同样的行格式，键是单个汉字。
         */
        fun parse(wordLines: List<String>, kanjiLines: List<String>): ReadingDictionaryIndex {
            val parsedWords = wordLines.mapNotNull(::parseEntry)
            val sortedWords = parsedWords.sortedBy { it.first }
            val parsedKanji = kanjiLines.mapNotNull(::parseEntry)
                .filter { it.first.length == 1 }
                .sortedBy { it.first[0] }
            return ReadingDictionaryIndex(
                surfaces = sortedWords.map { it.first }.toTypedArray(),
                readings = sortedWords.map { it.second }.toTypedArray(),
                kanjiChars = parsedKanji.map { it.first[0] }.toCharArray(),
                kanjiReadings = parsedKanji.map { it.second }.toTypedArray()
            )
        }

        private fun parseEntry(line: String): Pair<String, String>? {
            val tab = line.indexOf('\t')
            if (tab <= 0) return null
            val surface = line.substring(0, tab)
            val reading = line.substring(tab + 1).trim()
            if (surface.isBlank() || reading.isEmpty()) return null
            return surface to reading
        }

        /**
         * 由词级词条派生单字回退表。
         *
         * 只用「恰好含一个汉字」的词条：此时把送假名从读音尾部去掉，剩下的就是这个汉字的读音。
         * 多汉字词条无法把读音可靠对齐到单个汉字，所以一律不参与派生，宁缺勿错。
         */
        fun deriveKanjiLines(wordLines: List<String>): List<String> {
            val counter = HashMap<String, HashMap<String, Int>>()
            for ((surface, reading) in wordLines.mapNotNull(parseEntry)) {
                val kanjis = surface.filter(::isKanji)
                if (kanjis.length != 1) continue
                val okurigana = surface.substringAfter(kanjis[0])
                val kanjiReading = if (okurigana.isNotEmpty() &&
                    reading.length > okurigana.length &&
                    reading.endsWith(okurigana)
                ) reading.dropLast(okurigana.length) else reading
                if (kanjiReading.isEmpty()) continue
                counter.getOrPut(kanjis) { HashMap() }.merge(kanjiReading, 1) { a, b -> a + b }
            }
            return counter.entries
                .map { (kanji, readings) -> "$kanji\t${mostFrequentReading(readings)}" }
                .sortedBy { it[0] }
        }

        /** 次数多的优先；次数相同取字典序小的，保证同一份词库生成出的资产可复现。 */
        private fun mostFrequentReading(readings: Map<String, Int>): String {
            var bestKey = ""
            var bestCount = -1
            for ((key, count) in readings) {
                if (count > bestCount || (count == bestCount && key < bestKey)) {
                    bestKey = key
                    bestCount = count
                }
            }
            return bestKey
        }

        private fun isKanji(ch: Char): Boolean =
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch in '\uF900'..'\uFAFF'
    }
}
```

两处实现约定，执行者注意：
- `kanjiChars` 必须按字符升序排（`sortedBy { it.first[0] }`），因为 `CharArray.binarySearch` 依赖有序；`surfaces` 同理。
- `parse` 只负责精确匹配，不做任何前缀或模糊查询——算法层的窗口已经覆盖了这些情况。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.data.reading.ReadingDictionaryIndexTest'"`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/data/reading/ReadingDictionaryIndex.kt \
        app/src/test/java/com/asmr/player/data/reading/ReadingDictionaryIndexTest.kt
git commit -m "feat(readings): add memory-lean sorted-array reading index with kanji fallback derivation"
```

---

### Task 5: 词典资产加载与降级

**Files:**
- Create: `app/src/main/java/com/asmr/player/data/reading/ReadingDictionary.kt`
- Test: `app/src/test/java/com/asmr/player/data/reading/ReadingDictionaryTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `ReadingDictionaryIndex.parse`
- Produces:
  - `@Singleton class ReadingDictionary @Inject constructor(@ApplicationContext context: Context) : ReadingSource`
  - `suspend fun warmUp()`（幂等，整个进程只尝试一次；生产路径用真实 assets）
  - `internal suspend fun warmUpWith(open: (String) -> InputStream)`（同样的幂等守卫，注入假资产流给测试； Dagger 只认单参数的 `@Inject` 构造器，所以测试缝口必须是方法参数而不是构造参数）

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/data/reading/ReadingDictionaryTest.kt`：

```kotlin
package com.asmr.player.data.reading

import com.asmr.player.util.ReadingToken
import com.asmr.player.util.annotateLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class ReadingDictionaryTest {

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** 一个测试床：持有未就绪的词典 + 指向内存 gzip 内容的假资产加载器。 */
    private inner class TestBed(words: String, kanji: String = "", failOn: String? = null) {
        private val loader: (String) -> InputStream = { name ->
            if (name == failOn) throw FileNotFoundException(name)
            when (name) {
                ReadingDictionary.WORDS_ASSET -> ByteArrayInputStream(gzip(words))
                ReadingDictionary.KANJI_ASSET -> ByteArrayInputStream(gzip(kanji))
                else -> throw FileNotFoundException(name)
            }
        }
        val dictionary: ReadingDictionary = ReadingDictionary(RuntimeEnvironment.getApplication())
        suspend fun load() = dictionary.warmUpWith(loader)
        suspend fun loadWith(open: (String) -> InputStream) = dictionary.warmUpWith(open)
    }

    @Test
    fun notReadyBeforeWarmUp() = runBlocking {
        val bed = TestBed("日本\tにほん")
        assertFalse(bed.dictionary.ready)
        assertEquals(0, bed.dictionary.maxSurfaceLength)
        assertNull(bed.dictionary.readingOf("日本"))
    }

    @Test
    fun warmUp_loadsBothAssetsAndBecomesReady() = runBlocking {
        val bed = TestBed("日本\tにほん", "猫\tねこ")
        bed.load()
        assertTrue(bed.dictionary.ready)
        assertEquals("にほん", bed.dictionary.readingOf("日本"))
        assertEquals("ねこ", bed.dictionary.kanjiReadingOf('猫'))
        assertEquals(2, bed.dictionary.maxSurfaceLength)
    }

    @Test
    fun warmUp_isIdempotentAndOnlyTriesOnce() = runBlocking {
        var attempts = 0
        val bed = TestBed("日本\tにほん")
        val failing: (String) -> InputStream = {
            attempts++
            throw FileNotFoundException(it)
        }
        bed.loadWith(failing)
        bed.loadWith(failing)
        assertFalse(bed.dictionary.ready)
        assertEquals("只允许尝试一次资产加载", 1, attempts)
        // 加载失败后 annotateLine 必须仍然安全，且不缓存「不注音」
        assertEquals(listOf(ReadingToken("日本語")), annotateLine("日本語", bed.dictionary))
    }

    @Test
    fun corruptGzipContentMarksUnavailableWithoutThrowing() = runBlocking {
        val bed = TestBed("日本\tにほん")
        bed.loadWith { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) }
        assertFalse(bed.dictionary.ready)
    }

    @Test
    fun emptyWordAssetStillAcceptsKanjiFallback() = runBlocking {
        val bed = TestBed("", "猫\tねこ")
        bed.load()
        assertTrue(bed.dictionary.ready)
        assertNull(bed.dictionary.readingOf("猫"))
        assertEquals("ねこ", bed.dictionary.kanjiReadingOf('猫'))
    }
}
```

`annotateLine` 用进程级缓存，这些测试里只有 `warmUp_isIdempotentAndOnlyTriesOnce` 会走缓存路径，且它断言的正是「未就绪 → 不缓存」，与其它用例无冲突。若之后新增用例出现串扰，加 `@Before fun setUp() = clearAnnotateCache()`。

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.data.reading.ReadingDictionaryTest'"`
Expected: 编译失败，`unresolved reference: ReadingDictionary`

- [ ] **Step 3: 写实现**

创建 `app/src/main/java/com/asmr/player/data/reading/ReadingDictionary.kt`：

```kotlin
package com.asmr.player.data.reading

import android.content.Context
import android.util.Log
import com.asmr.player.util.ReadingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 读音词典的进程级单例：从自带 gzip 资产加载 [ReadingDictionaryIndex]。
 *
 * 只在注音开关打开后由 [warmUp] 加载一次。任何失败（资产缺失、解压失败、内容非法）
 * 都被吞掉并标记不可用，调用方按「不注音」处理——绝不让歌词界面因为词典而崩。
 */
@Singleton
class ReadingDictionary @Inject constructor(
    @ApplicationContext private val context: Context
) : ReadingSource {

    @Volatile private var index: ReadingDictionaryIndex? = null
    @Volatile private var attempted = false
    private val mutex = Mutex()

    override val ready: Boolean get() = index?.ready == true

    override val maxSurfaceLength: Int get() = index?.maxSurfaceLength ?: 0

    override fun readingOf(surface: String): String? = index?.readingOf(surface)

    override fun kanjiReadingOf(kanji: Char): String? = index?.kanjiReadingOf(kanji)

    /** 生产入口：读取 assets/reading/ 下的 gzip 词库。 */
    suspend fun warmUp() = warmUpWith { context.assets.open(it) }

    /**
     * 幂等的加载入口，[open] 是测试缝口（Dagger 只认单参数的 @Inject 构造器，
     * 所以假加载器只能走方法参数）。无论成功失败，一个实例只尝试一次，
     * 避免失败后每次渲染都重试。
     */
    internal suspend fun warmUpWith(open: (String) -> InputStream) {
        if (attempted) return
        mutex.withLock {
            if (attempted) return
            attempted = true
            index = runCatching { loadFrom(open) }
                .getOrElse {
                    Log.i(TAG, "furigana dictionary unavailable", it)
                    null
                }
        }
    }

    private suspend fun loadFrom(open: (String) -> InputStream): ReadingDictionaryIndex =
        withContext(Dispatchers.Default) {
            val words = readLines(WORDS_ASSET, open)
            val kanji = try {
                readLines(KANJI_ASSET, open)
            } catch (_: FileNotFoundException) {
                emptyList()
            }
            val parsed = ReadingDictionaryIndex.parse(words, kanji)
            check(parsed.ready) { "furigana dictionary is empty" }
            parsed
        }

    private fun readLines(asset: String, open: (String) -> InputStream): List<String> =
        open(asset).use { stream ->
            GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines()
            }
        }

    companion object {
        private const val TAG = "ReadingDictionary"
        const val WORDS_ASSET = "reading/words.gz"
        const val KANJI_ASSET = "reading/kanji.gz"
    }
}
```

`ReadingDictionary` 是 `@Singleton`，Task 8/10 的预热因此天然只跑一次；`loadFrom` 已经在 `withContext(Dispatchers.Default)` 里，调用方在 `Dispatchers.Main` 直接 await 即可。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.data.reading.ReadingDictionaryTest'"`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/data/reading/ReadingDictionary.kt \
        app/src/test/java/com/asmr/player/data/reading/ReadingDictionaryTest.kt
git commit -m "feat(readings): load furigana dictionary from gzipped assets with fail-safe warm-up"
```

---

### Task 6: FuriganaSpec 与两个渲染构建器

**Files:**
- Modify: `app/src/main/java/com/asmr/player/util/SubtitleDisplayMode.kt`（加 `FuriganaSpec`）
- Create: `app/src/main/java/com/asmr/player/ui/common/FuriganaText.kt`
- Test: `app/src/test/java/com/asmr/player/ui/common/FuriganaTextTest.kt`

**Interfaces:**
- Consumes: Task 1 `annotateLine`、`FURIGANA_READING_SCALE`；Task 3 `DisplaySegment`、`orPlainFallback`；Task 5 `ReadingSource`
- Produces:
  - `data class FuriganaSpec(val enabled: Boolean, val source: ReadingSource?)` + `FuriganaSpec.NONE`
  - `fun buildFuriganaAnnotatedString(segments: List<DisplaySegment>, plainFallback: String, furigana: FuriganaSpec, rubyFontSize: TextUnit, separator: String = "\n"): AnnotatedString`
  - `fun buildFuriganaSpanned(segments: List<DisplaySegment>, plainFallback: String, furigana: FuriganaSpec): CharSequence`
  - `fun furiganaPlainText(segments: List<DisplaySegment>, plainFallback: String): String`
  - `fun furiganaRubyFontSize(style: TextStyle): TextUnit`

两个 `build*` 函数都必须带 `plainFallback`：调用点传该行当前要显示的纯文本（`entry.text` / 覆盖层的 `current`），空分段时由 `orPlainFallback` 兜底（修订 6）。这样「中文模式整行变空白」这类回归在构建器这一层就不可能发生。

`separator` 只有 Compose 侧需要（跑马灯渲染点把分段折成一行）；覆盖层的 `TextView` 原生支持多行，固定用 `\n`。

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/ui/common/FuriganaTextTest.kt`：

```kotlin
package com.asmr.player.ui.common

import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.asmr.player.util.DisplaySegment
import com.asmr.player.util.FURIGANA_READING_SCALE
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.clearAnnotateCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FuriganaTextTest {

    private class FakeSource(
        private val words: Map<String, String>,
        private val singleKanji: Map<Char, String> = emptyMap()
    ) : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = words.keys.maxOfOrNull { it.length } ?: 0
        override fun readingOf(surface: String): String? = words[surface]
        override fun kanjiReadingOf(kanji: Char): String? = singleKanji[kanji]
    }

    private val spec = FuriganaSpec(enabled = true, source = FakeSource(mapOf("日本" to "にほん")))
    private val off = FuriganaSpec(enabled = false, source = spec.source)
    private val ruby = 21.sp * FURIGANA_READING_SCALE

    private val bilingual = listOf(
        DisplaySegment("中文行", japanese = false),
        DisplaySegment("日本語", japanese = true)
    )

    @Before
    fun setUp() = clearAnnotateCache()

    /** 删掉所有注音 span 覆盖的字符，剩下的必须就是原始展示文本。 */
    private fun stripReadings(built: androidx.compose.ui.text.AnnotatedString): String {
        val drop = built.spanStyles.flatMap { it.start until it.end }
            .toSet()
        return built.text.filterIndexed { index, _ -> index !in drop }
    }

    @Test
    fun annotated_whenOff_isByteIdenticalToPlainText() {
        val built = buildFuriganaAnnotatedString(bilingual, "", off, ruby)
        assertEquals("中文行\n日本語", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun annotated_onlyJapaneseSegmentGetsSpans() {
        val segments = listOf(
            DisplaySegment("猫", japanese = false),
            DisplaySegment("日本", japanese = true)
        )
        val built = buildFuriganaAnnotatedString(segments, "", spec, ruby)

        assertEquals("猫\n日本にほん", built.text)
        val span = built.spanStyles.single()
        assertEquals(ruby, span.style.fontSize)
        assertEquals(built.text.indexOf("にほん"), span.start)
        assertEquals(built.text.length, span.end)
    }

    @Test
    fun annotated_insertsReadingRightAfterTheKanjiItBelongsTo() {
        // 行内小字注音：读音紧跟自己的基字，送假名留在原位——
        // 美味しい 显示成「美味[おい]しい」，不是把整条读音堆到词尾。
        val okuriganaSpec = FuriganaSpec(
            enabled = true,
            source = FakeSource(mapOf("美味しい" to "おいしい"))
        )
        val built = buildFuriganaAnnotatedString(
            listOf(DisplaySegment("美味しい", japanese = true)),
            "",
            okuriganaSpec,
            ruby
        )
        assertEquals("美味おいしい", built.text)
        assertEquals(1, built.spanStyles.size)
        assertEquals("美味しい", stripReadings(built))
    }

    @Test
    fun annotated_separatorCollapsesSegmentBreaksForMarqueeSurfaces() {
        // 跑马灯那个渲染点把多行折叠成一行，分段连接符必须跟着变，
        // 否则开关关掉时的文本就和现状（normalizeSingleLineText 的结果）不一致。
        val built = buildFuriganaAnnotatedString(bilingual, "", spec, ruby, separator = " ")
        // 读音插在它自己的基字后面：日本[にほん]語
        assertEquals("中文行 日本にほん語", built.text)
        val offBuilt = buildFuriganaAnnotatedString(bilingual, "", off, ruby, separator = " ")
        assertEquals("中文行 日本語", offBuilt.text)
        assertTrue(offBuilt.spanStyles.isEmpty())
    }

    @Test
    fun annotated_emptySegmentsFallBackToPlainFallbackText() {
        // 修订 6：中文模式 / 外挂字幕的分段是空的，必须退回 plainFallback，
        // 否则整行渲染成空白，等于把现有歌词全屏蔽掉。
        val built = buildFuriganaAnnotatedString(emptyList(), "中文", spec, ruby)
        assertEquals("中文", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun annotated_blankSegmentsAlsoFallBackToPlainFallbackText() {
        // 悬浮歌词对空白行用 " " 占位；空白段必须同样退回，不能画成真正的空串。
        val built = buildFuriganaAnnotatedString(
            listOf(DisplaySegment("  ", japanese = false)),
            " ",
            spec,
            ruby
        )
        assertEquals(" ", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun annotated_plainFallbackTextIsNeverSentToDictionary() {
        val everyWord = object : ReadingSource {
            override val ready get() = true
            override val maxSurfaceLength get() = 3
            override fun readingOf(surface: String) = "かんじ"
            override fun kanjiReadingOf(kanji: Char) = "かんじ"
        }
        val built = buildFuriganaAnnotatedString(
            emptyList(),
            "日本語",
            FuriganaSpec(enabled = true, source = everyWord),
            ruby
        )
        assertEquals("日本語", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun spanned_whenOff_returnsPlainString() {
        val built = buildFuriganaSpanned(bilingual, "", off)
        assertEquals("中文行\n日本語", built.toString())
        assertTrue("关闭时不应产生 Spanned", built is String)
    }

    @Test
    fun spanned_appliesRelativeSizeOnlyToReadings() {
        val segments = listOf(DisplaySegment("日本", japanese = true))
        val built = buildFuriganaSpanned(segments, "", spec) as Spanned
        assertEquals("日本にほん", built.toString())
        val spans = built.getSpans(0, built.length, RelativeSizeSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(FURIGANA_READING_SCALE, spans.single().size)
        assertEquals(built.toString().indexOf("にほん"), built.getSpanStart(spans.single()))
        assertEquals(built.length, built.getSpanEnd(spans.single()))
    }

    @Test
    fun spanned_emptySegmentsFallBackToPlainFallbackText() {
        val built = buildFuriganaSpanned(emptyList(), "只有中文", spec)
        assertEquals("只有中文", built.toString())
        assertTrue(built is String)
    }

    @Test
    fun rubyFontSize_followsTheLineStyleSoUserFontScaleIsInherited() {
        val style = TextStyle(fontSize = 20.sp, fontFamily = FontFamily.Default)
        assertEquals(20.sp * FURIGANA_READING_SCALE, furiganaRubyFontSize(style))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.ui.common.FuriganaTextTest'"`
Expected: 编译失败，`unresolved reference: FuriganaSpec` / `buildFuriganaAnnotatedString`

- [ ] **Step 3: 写实现**

3a. `util/SubtitleDisplayMode.kt` 顶部 import `com.asmr.player.util.ReadingSource`（同包，无需 import），在文件末尾加：

```kotlin
/**
 * 注音渲染所需的全部输入。[source] 为 null 或词典未就绪时等价于不注音。
 * 打包成一个值对象是为了避免把「开关 + 词典」逐层透传到五个渲染点。
 */
data class FuriganaSpec(
    val enabled: Boolean,
    val source: ReadingSource?
) {
    companion object {
        val NONE = FuriganaSpec(enabled = false, source = null)
    }
}
```

3b. 创建 `ui/common/FuriganaText.kt`：

```kotlin
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
```

`furigana.source` 为 null（开关开了但词典没注入）时 `annotateLine` 原样返回单 token，等于不注音——这是修订 3 的降级路径，不需要额外的判空。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.ui.common.FuriganaTextTest'"`
Expected: 全部 PASS（11 个方法）。`annotated_insertsReadingRightAfterTheKanjiItBelongsTo` 期望 `美味おいしい`——若实现输出的是 `美味しいおいしい` 或 `おいしい美味しい`，说明 Task 1 的 `trimReading` / 指针推进规则没照做（读音只属于汉字部分，送假名必须留在原位由下一轮原样输出），回 Task 1 修，不要改这个断言。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/util/SubtitleDisplayMode.kt \
        app/src/main/java/com/asmr/player/ui/common/FuriganaText.kt \
        app/src/test/java/com/asmr/player/ui/common/FuriganaTextTest.kt
git commit -m "feat(lyrics): build furigana annotated text for Compose and TextView surfaces"
```

---

### Task 7: 设置项与设置页开关

**Files:**
- Modify: `app/src/main/java/com/asmr/player/data/local/datastore/SettingsDataStore.kt`（keys 区 L50 附近、flows 区 L111 附近、setters 区 L233 附近）
- Modify: `app/src/main/java/com/asmr/player/ui/settings/SettingsViewModel.kt`（StateFlow 区 L162 附近、setter 区 L285 附近）
- Modify: `app/src/main/java/com/asmr/player/ui/settings/SettingsScreen.kt`（collect 区 L202 附近、歌词分区 L900 附近）
- Test: `app/src/test/java/com/asmr/player/data/local/datastore/FuriganaSettingStoreTest.kt`

**Interfaces:**
- Consumes: Task 5 的 `ReadingDictionary`（ViewModel 不需要，UI 只读写布尔）
- Produces:
  - `SettingsDataStore.japaneseFuriganaEnabled: Flow<Boolean>`（默认 false）
  - `SettingsDataStore.setJapaneseFuriganaEnabled(enabled: Boolean)`
  - `SettingsViewModel.japaneseFuriganaEnabled: StateFlow<Boolean>`
  - `SettingsViewModel.setJapaneseFuriganaEnabled(enabled: Boolean)`
  - 偏好 key 字符串：`japanese_furigana_enabled`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/data/local/datastore/FuriganaSettingStoreTest.kt`（范式来自 `ui/library/LibraryPreferencesStoreTest.kt`）：

```kotlin
package com.asmr.player.data.local.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.asmr.player.data.settings.settingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FuriganaSettingStoreTest {

    private val context = RuntimeEnvironment.getApplication()
    private val store = SettingsDataStore(context)

    @Test
    fun japaneseFuriganaEnabled_defaultsToOff() = runBlocking {
        // 清掉上一次测试可能写入的值，Robolectric 进程内 DataStore 是单例。
        context.settingsDataStore.edit { it[booleanPreferencesKey("japanese_furigana_enabled")] = false }
        assertFalse(store.japaneseFuriganaEnabled.first())
    }

    @Test
    fun japaneseFuriganaEnabled_roundTrips() = runBlocking {
        store.setJapaneseFuriganaEnabled(true)
        assertTrue(store.japaneseFuriganaEnabled.first())

        store.setJapaneseFuriganaEnabled(false)
        assertFalse(store.japaneseFuriganaEnabled.first())
    }

    @Test
    fun keyNameIsStableBecauseExistingInstallsDependOnIt() = runBlocking {
        val key = booleanPreferencesKey("japanese_furigana_enabled")
        context.settingsDataStore.edit { it[key] = true }
        assertEquals(true, context.settingsDataStore.data.first()[key])
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.data.local.datastore.FuriganaSettingStoreTest'"`
Expected: 编译失败，`unresolved reference: japaneseFuriganaEnabled`

- [ ] **Step 3: 实现三处**

下面所有 `:数字` 只是辅助定位；双语顺序特性（修订 8）同样在 `SettingsDataStore` / `SettingsViewModel` / `SettingsScreen` 里插入了 `subtitle_bilingual_order` 相关的行，行号必然漂移。**按锚点名字定位**（`subtitleDisplayModeKey`、`setSubtitleDisplayMode`、`SubtitleDisplayModeSection(...)` 调用与其后的 `HorizontalDivider`），不要按行号跳。注音开关这一行放在双语顺序那行的**后面**，两者都还在「字幕显示」这节里。

3a. `data/local/datastore/SettingsDataStore.kt`

在 `:50` 的 `subtitleDisplayModeKey` 之后插入：

```kotlin
    private val japaneseFuriganaEnabledKey = booleanPreferencesKey("japanese_furigana_enabled")
```

在 `:113` 的 `subtitleDisplayMode` Flow 之后插入：

```kotlin
    val japaneseFuriganaEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[japaneseFuriganaEnabledKey] ?: false
    }
```

在 `:235` 的 `setSubtitleDisplayMode` 之后插入：

```kotlin
    suspend fun setJapaneseFuriganaEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[japaneseFuriganaEnabledKey] = enabled }
    }
```

3b. `ui/settings/SettingsViewModel.kt`

在 `:163` 的 `subtitleDisplayMode` StateFlow 之后插入：

```kotlin
    val japaneseFuriganaEnabled: StateFlow<Boolean> = settingsDataStore.japaneseFuriganaEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
```

在 `:287` 的 `setSubtitleDisplayMode` 之后插入：

```kotlin
    fun setJapaneseFuriganaEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setJapaneseFuriganaEnabled(enabled) }
    }
```

3c. `ui/settings/SettingsScreen.kt`

在 `:202` 的 `subtitleDisplayMode` collect 之后插入：

```kotlin
    val japaneseFuriganaEnabled by viewModel.japaneseFuriganaEnabled.collectAsStateWhileActive(lyricsDataActive)
```

在 `:900` 的 `SubtitleDisplayModeSection(...)` 调用与它后面那条 `HorizontalDivider` 之间插入注音开关（放在显示模式之后、悬浮歌词之前，因为它是显示模式的从属项）：

```kotlin
                            SettingsToggleRow(
                                text = "日文汉字注音",
                                checked = japaneseFuriganaEnabled,
                                onCheckedChange = { viewModel.setJapaneseFuriganaEnabled(it) }
                            )
                            Text(
                                text = if (subtitleDisplayMode == SubtitleDisplayMode.CHINESE) {
                                    "当前字幕显示模式不含日文，切到「日文原文」或「中日双语」才会生效。"
                                } else {
                                    "在日文汉字的右侧以小字显示平假名读音，字号自动跟随歌词字号；不影响导出的字幕文件。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = AsmrTheme.colorScheme.textSecondary
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
```

现有代码在 `SubtitleDisplayModeSection` 之后已经有一条 `HorizontalDivider(...0.18f)`，插入位置在那条 divider 之后、`SettingsToggleRow(text = "开启悬浮歌词")` 之前，并复用上面新增的 divider，确保分区不被挤乱。

- [ ] **Step 4: 跑测试确认通过 + 编译设置页**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.data.local.datastore.FuriganaSettingStoreTest'"`
Run: `cmd //c "gradlew-local.bat :app:compileDebugKotlin"`
Expected: 测试 PASS；编译成功（`SettingsToggleRow`、`AsmrTheme`、`collectAsStateWhileActive` 均已在该文件可见，不需要新 import；若报缺 import 按报错补）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/data/local/datastore/SettingsDataStore.kt \
        app/src/main/java/com/asmr/player/ui/settings/SettingsViewModel.kt \
        app/src/main/java/com/asmr/player/ui/settings/SettingsScreen.kt \
        app/src/test/java/com/asmr/player/data/local/datastore/FuriganaSettingStoreTest.kt
git commit -m "feat(settings): add Japanese kanji reading toggle to subtitle settings"
```

---

### Task 8: 歌词 ViewModel 下发注音输入 + AppleLyricsView 注音

覆盖歌词页（`LyricsPage`）和播放页歌词面（`NowPlayingLyricsSurface`），两者都走 `AppleLyricsView`。

**Files:**
- Modify: `app/src/main/java/com/asmr/player/ui/player/LyricsViewModel.kt:23-44`
- Modify: `app/src/main/java/com/asmr/player/ui/player/AppleLyricsView.kt:138-157, 605-625, 265-275, 873-893`
- Modify: `app/src/main/java/com/asmr/player/ui/player/LyricsPage.kt:142-157`
- Modify: `app/src/main/java/com/asmr/player/ui/player/nowplaying/NowPlayingSurfaceComponents.kt:263-279`
- Modify: `app/src/main/java/com/asmr/player/ui/player/NowPlayingScreen.kt:1827, 2005, 2391, 2426, 2540`（转发 `furigana`）
- Test: `app/src/test/java/com/asmr/player/ui/player/AppleLyricsViewFuriganaTest.kt`

**Interfaces:**
- Consumes: Task 6 `FuriganaSpec`、`buildFuriganaAnnotatedString`、`furiganaRubyFontSize`
- Produces:
  - `LyricsUiState.furigana: FuriganaSpec`
  - `AppleLyricsView(..., furigana: FuriganaSpec)`（无默认值，强制调用点显式传）
  - `NowPlayingLyricsSurface(..., furigana: FuriganaSpec)`、`LyricsPage(..., furigana: FuriganaSpec)`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/ui/player/AppleLyricsViewFuriganaTest.kt`。`AppleLyricsView` 是完整的 Compose 渲染，Robolectric 里跑成本高风险大。**只测两条真正的逻辑接缝**：ViewModel 组装出的 `FuriganaSpec` 走不走词典，以及测量与绘制共用的那个 `lyricLineAnnotated` 助手（Step 3a 提出，返回 `AnnotatedString`）。

```kotlin
package com.asmr.player.ui.player

import androidx.compose.ui.unit.sp
import com.asmr.player.util.DisplaySegment
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppleLyricsViewFuriganaTest {

    private class Source : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = 2
        override fun readingOf(surface: String) = if (surface == "日本") "にほん" else null
        override fun kanjiReadingOf(kanji: Char) = null
    }

    private val bilingualEntry = SubtitleEntry(0L, 1_000L, "中文", "日本").copy(
        text = "中文\n日本",
        displaySegments = listOf(
            DisplaySegment("中文", japanese = false),
            DisplaySegment("日本", japanese = true)
        )
    )

    @Before
    fun setUp() = com.asmr.player.util.clearAnnotateCache()

    @Test
    fun lyricLineAnnotated_offIsByteIdenticalToPlainText() {
        val built = lyricLineAnnotated(bilingualEntry, FuriganaSpec.NONE, 11.sp)
        assertEquals("中文\n日本", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun lyricLineAnnotated_withReadyDictionaryAnnotatesOnlyJapaneseSegment() {
        val built = lyricLineAnnotated(
            bilingualEntry,
            FuriganaSpec(enabled = true, source = Source()),
            11.sp
        )
        assertEquals("中文\n日本にほん", built.text)
        assertEquals(1, built.spanStyles.size)
    }

    @Test
    fun lyricLineAnnotated_nullSourceBehavesLikeOff() {
        // 开关开了但词典还没就绪：文本必须完全不变，且不能崩。
        val built = lyricLineAnnotated(bilingualEntry, FuriganaSpec(enabled = true, source = null), 11.sp)
        assertEquals("中文\n日本", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun lyricLineAnnotated_chineseModeEmptySegmentsStillRenderTheWholeLine() {
        // 修订 6 的渲染点落位：中文模式（及一切没有分段的行）不能画成空白。
        val chinese = SubtitleEntry(0L, 1_000L, "中文行")
        assertEquals(emptyList<DisplaySegment>(), chinese.displaySegments)
        val built = lyricLineAnnotated(chinese, FuriganaSpec(enabled = true, source = Source()), 11.sp)
        assertEquals("中文行", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun lyricLineAnnotated_externalJapaneseSubtitleIsNeverGuessed() {
        // 外挂字幕只有 text、没有分段信息，绝不能进词典（语言未标注 = 不注音）。
        val external = SubtitleEntry(0L, 1_000L, "日本語")
        val built = lyricLineAnnotated(external, FuriganaSpec(enabled = true, source = Source()), 11.sp)
        assertEquals("日本語", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.ui.player.AppleLyricsViewFuriganaTest'"`
Expected: 编译失败，`unresolved reference: lyricLineAnnotated`

- [ ] **Step 3: 实现**

3a. `ui/player/AppleLyricsView.kt`

在 `AppleLyricsView` 参数表（`:138-157`）里，`settings: LyricsPageSettings` 之后加一项**无默认值**的 `furigana: FuriganaSpec`：

```kotlin
    furigana: FuriganaSpec,
```

无默认值是刻意的：编译器会把所有调用点全部报出来，比人肉搜索可靠（Step 3c 逐个补）。

在文件里 `measuredLyricItemHeight`（`:873`）之前加一致性助手，测量与绘制都走它：

```kotlin
/** 歌词行的展示文本：绘制与测量共用，避免行高与实际文本不一致。 */
internal fun lyricLineAnnotated(
    entry: SubtitleEntry,
    furigana: FuriganaSpec,
    rubyFontSize: TextUnit
): AnnotatedString = buildFuriganaAnnotatedString(
    segments = entry.displaySegments,
    plainFallback = entry.text,
    furigana = furigana,
    rubyFontSize = rubyFontSize
)
```

把 `measuredLyricItemHeight`（`:873-893`）的参数表加一项 `furigana: FuriganaSpec`（放在 `measurementStyle: TextStyle` 之后），并把内部：

```kotlin
    val textLayout = textMeasurer.measure(
        text = AnnotatedString(entry.text),
```

改成（该函数上面已有 `if (entry == null) return nominalItemHeightPx`，这里 `entry` 已智能转换为非空）：

```kotlin
    val textLayout = textMeasurer.measure(
        text = lyricLineAnnotated(entry, furigana, furiganaRubyFontSize(measurementStyle)),
```

`LyricLineText`（`:904-914`）参数 `text: String` → `text: AnnotatedString`，函数体内三处 `Text(text = text, ...)` 不用改（`Text` 接受 `AnnotatedString`）。

绘制点（`:613-629`）现在把 `TextStyle` 内联写在参数上，先把它提出来，注音字号才能取自同一份样式：

```kotlin
                        val shadowColor = remember(color) { lyricShadowColor(color) }
                        val lineStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                            fontSize = fontSize,
                            lineHeight = wrappedLineHeight,
                            textAlign = textAlign,
                            shadow = shadow
                        )
                        LyricLineText(
                            text = lyricLineAnnotated(entry, furigana, furiganaRubyFontSize(lineStyle)),
                            color = color,
                            shadowColor = shadowColor,
                            strokeWidthPx = strokeWidthPx,
                            dispersionProgress = focusEffect.dispersionProgress,
                            dispersionOffsetX = focusEffect.dispersionOffsetXDp.dp,
                            dispersionOffsetY = focusEffect.dispersionOffsetYDp.dp,
                            style = lineStyle,
                            textAlign = textAlign
                        )
```

`:269` 的 `measuredLyricItemHeight(...)` 调用补 `furigana = furigana`。

需要的 import：`androidx.compose.ui.unit.TextUnit`、`com.asmr.player.ui.common.buildFuriganaAnnotatedString`、`com.asmr.player.ui.common.furiganaRubyFontSize`、`com.asmr.player.util.FuriganaSpec`。

3b. `ui/player/LyricsViewModel.kt`

`LyricsUiState`（`:23-28`）加字段：

```kotlin
    val furigana: FuriganaSpec = FuriganaSpec.NONE
```

构造注入 `ReadingDictionary`（import `com.asmr.player.data.reading.ReadingDictionary`），`uiState` 的 `combine` 换成四源版本并在开关打开时预热：

```kotlin
    val uiState: StateFlow<LyricsUiState> = combine(
        _loadedState,
        settingsDataStore.subtitleDisplayMode,
        settingsDataStore.subtitleBilingualOrder,
        settingsDataStore.japaneseFuriganaEnabled
    ) { state, mode, order, furiganaEnabled ->
        state.copy(
            lyrics = state.lyrics.withDisplayMode(mode, order),
            furigana = FuriganaSpec(
                enabled = furiganaEnabled,
                source = if (furiganaEnabled) readingDictionary else null
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState())

    init {
        viewModelScope.launch {
            settingsDataStore.japaneseFuriganaEnabled
                .distinctUntilChanged()
                .filter { it }
                .collect { readingDictionary.warmUp() }
        }
    }
```

需要的 import：`kotlinx.coroutines.flow.distinctUntilChanged`、`kotlinx.coroutines.flow.filter`、`kotlinx.coroutines.launch`（已有）。双语顺序流的名字按 `SettingsDataStore` 里的实际拼写取（本计划写作 `subtitleBilingualOrder`）；`withDisplayMode` 的 `order` 参数**没有默认值**，所以漏取会被编译器直接报出，不靠人肉搜索。

3c. 调用点转发（`furigana` 无默认值，编译会精确列出漏改的地方）

- `ui/player/LyricsPage.kt:142` 的 `AppleLyricsView(...)` 加 `furigana = uiState.furigana`
- `ui/player/nowplaying/NowPlayingSurfaceComponents.kt:263` 的 `AppleLyricsView(...)` 加 `furigana = furigana`，并在 `NowPlayingLyricsSurface`（`:193`）参数表加 `furigana: FuriganaSpec`
- `ui/player/NowPlayingScreen.kt` 的 5 个调用点（`:1827`、`:2005`、`:2391`、`:2426`、`:2540`）分别加 `furigana = lyricsState.furigana`
- 若 `LyricsPage` 有独立签名，同步加参数；`hiltViewModel()` 已提供 `uiState`

3d. 编译验证（这一步会把所有漏改点暴露出来，比人肉搜索可靠）

Run: `cmd //c "gradlew-local.bat :app:compileDebugKotlin"`
Expected: 先失败并报出每个缺少 `furigana` 参数的调用点，逐个补完后成功。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.ui.player.AppleLyricsViewFuriganaTest'"`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/ui/player/LyricsViewModel.kt \
        app/src/main/java/com/asmr/player/ui/player/AppleLyricsView.kt \
        app/src/main/java/com/asmr/player/ui/player/LyricsPage.kt \
        app/src/main/java/com/asmr/player/ui/player/NowPlayingScreen.kt \
        app/src/main/java/com/asmr/player/ui/player/nowplaying/NowPlayingSurfaceComponents.kt \
        app/src/test/java/com/asmr/player/ui/player/AppleLyricsViewFuriganaTest.kt
git commit -m "feat(player): render furigana on lyric lines with shared measurement and drawing text"
```

---

### Task 9: 播放页歌词轨道与多行字幕注音

按修订 1，`NowPlayingLyricsPreviewContent` 与 `NowPlayingUpcomingLyricLine` **保持 `String` 字段不变**，注音在测量点与绘制点就地派生，靠 Task 2 的行级 LRU 承担开销。

**Files:**
- Modify: `app/src/main/java/com/asmr/player/ui/player/nowplaying/NowPlayingControls.kt:643-663, 681-710, 730-740, 772-830, 863-880, 940-960`
- Modify: `app/src/main/java/com/asmr/player/ui/player/nowplaying/NowPlayingMultilineLyrics.kt:37-46, 82-88`
- Test: `app/src/test/java/com/asmr/player/ui/player/NowPlayingLyricsFuriganaTest.kt`

**Interfaces:**
- Consumes: Task 3 `displaySegmentsFor`、Task 6 构建器
- Produces:
- Produces:
  - `NowPlayingLyricsPreview(..., furigana: FuriganaSpec)`
  - `NowPlayingMultilineLyrics(..., furigana: FuriganaSpec)`
  - `internal fun upcomingLyricText(sortedLyrics: List<SubtitleEntry>, index: Int, fallback: String, furigana: FuriganaSpec, rubyFontSize: TextUnit): AnnotatedString`（测量与绘制共用，多行原样保留）
  - `internal fun marqueeLyricText(sortedLyrics: List<SubtitleEntry>, index: Int, fallback: String, furigana: FuriganaSpec, rubyFontSize: TextUnit): AnnotatedString`（跑马灯专用：逐段按 `normalizeSingleLineText` 折叠、用空格连接）

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/ui/player/NowPlayingLyricsFuriganaTest.kt`。注意包名：`nowplaying/NowPlayingControls.kt` 与 `nowplaying/NowPlayingMultilineLyrics.kt` 虽然放在 `nowplaying/` 目录，但 `package` 都是 `com.asmr.player.ui.player`，所以测试同包即可直接调 `internal` 的 `upcomingLyricText`。

```kotlin
package com.asmr.player.ui.player

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.SubtitleDisplayMode
import com.asmr.player.util.SubtitleEntry
import com.asmr.player.util.withDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricsFuriganaTest {

    private val source = object : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = 2
        override fun readingOf(surface: String) = if (surface == "日本") "にほん" else null
        override fun kanjiReadingOf(kanji: Char) = null
    }

    private val entry = SubtitleEntry(0L, 1_000L, "中文", "日本")

    @Test
    fun trackAnnotationIsDerivedFromEntryIndexSoPreviewContentKeepsPlainStrings() {
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = upcomingLyricText(
            sortedLyrics = lyrics,
            index = 0,
            fallback = "暂无歌词",
            furigana = FuriganaSpec(enabled = true, source = source),
            rubyFontSize = 11.sp
        )
        assertEquals("中文\n日本にほん", built.text)
        assertEquals(1, built.spanStyles.size)
    }

    @Test
    fun trackAnnotation_offProducesByteIdenticalPlainText() {
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = upcomingLyricText(
            lyrics,
            0,
            "暂无歌词",
            FuriganaSpec.NONE,
            11.sp
        )
        assertEquals("中文\n日本", built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun trackAnnotation_offKeepsExistingWhitespaceNormalization() {
        // 预览轨道原本对文本做「压空格 / 去 BOM / 逐行 trim」。分段本身不带这个规则，
        // 所以 upcomingLyricText 必须逐段归一化，否则关掉注音后输出就和现状不同。
        val messy = SubtitleEntry(0L, 1_000L, "中文  带空格", "日本")
        val lyrics = listOf(messy).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val normalized = normalizeMultilineText(lyrics.first().text)
        val built = upcomingLyricText(lyrics, 0, normalized, FuriganaSpec.NONE, 11.sp)
        assertEquals(normalized, built.text)
    }

    @Test
    fun marqueeAnnotation_offMatchesCurrentSingleLineCollapse() {
        // 跑马灯今天显示的是 normalizeSingleLineText(整行文本)，
        // 关掉注音时新路径必须给出同一个字符串。
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val collapsed = normalizeSingleLineText(lyrics.first().text)
        val built = marqueeLyricText(lyrics, 0, lyrics.first().text, FuriganaSpec.NONE, 11.sp)
        assertEquals(collapsed, built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun marqueeAnnotation_insertsReadingInsideTheCollapsedLine() {
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = marqueeLyricText(
            lyrics,
            0,
            lyrics.first().text,
            FuriganaSpec(enabled = true, source = source),
            11.sp
        )
        assertEquals("中文 日本にほん", built.text)
        assertEquals(1, built.spanStyles.size)
    }

    @Test
    fun trackAnnotation_unknownIndexUsesFallback() {
        val built = upcomingLyricText(emptyList(), 3, "暂无歌词", FuriganaSpec(true, source), 11.sp)
        assertEquals(AnnotatedString("暂无歌词").text, built.text)
        assertTrue(built.spanStyles.isEmpty())
    }

    @Test
    fun trackAnnotation_blankEntryTextFallsBackLikeCurrentBehavior() {
        val lyrics = listOf(SubtitleEntry(0L, 1_000L, "  ", "  ")).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val built = upcomingLyricText(lyrics, 0, "暂无歌词", FuriganaSpec(true, source), 11.sp)
        // 现有逻辑会用 normalizeMultilineText + ifBlank 兜底，注音不能破坏它
        assertEquals("", built.text.trim())
    }

    @Test
    fun trackAnnotation_followsBilingualOrderInsteadOfHardcodingIt() {
        // 上面的用例统一钉在 CHINESE_FIRST，是为了让逐字节断言专注注音本身；
        // 这一条证明注音真的跟随分段顺序，而不是把「中文在前」写死在渲染层。
        val lyrics = listOf(entry).withDisplayMode(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.JAPANESE_FIRST)
        val built = upcomingLyricText(
            lyrics,
            0,
            "暂无歌词",
            FuriganaSpec(enabled = true, source = source),
            11.sp
        )
        assertEquals("日本にほん\n中文", built.text)
        assertEquals(1, built.spanStyles.size)
    }
}
```

需要的 import：`com.asmr.player.util.SubtitleBilingualOrder`（双语顺序特性提供，见修订 8）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.ui.player.NowPlayingLyricsFuriganaTest'"`
Expected: 编译失败，`unresolved reference: upcomingLyricText`

- [ ] **Step 3: 实现**

3a. 在 `NowPlayingControls.kt` 里（`NowPlayingLyricsPreview` 定义 `:643` 之前）加共用助手：

```kotlin
/**
 * 歌词轨道里某一行的展示文本。测量与绘制共用，保证行高与文本一致。
 * 注音不存进预览内容对象：那些对象的 equals 参与 remember key，
 * 存 AnnotatedString 会让滚动时每帧重算测量。
 *
 * 分段必须逐段过 [normalizeMultilineText]：现有预览文本走的是一条压空白+去 BOM 的规则，
 * 直接从分段拼接会绕开它，导致「开关关了输出却变了」。
 * [fallback] 同时是「取不到行」和「该行分段全空」两种情况的兜底文本（修订 6）。
 */
internal fun upcomingLyricText(
    sortedLyrics: List<SubtitleEntry>,
    index: Int,
    fallback: String,
    furigana: FuriganaSpec,
    rubyFontSize: TextUnit
): AnnotatedString = buildFuriganaAnnotatedString(
    segments = sortedLyrics.getOrNull(index)
        ?.displaySegments
        ?.map { segment -> segment.copy(text = normalizeMultilineText(segment.text)) }
        .orEmpty(),
    plainFallback = fallback,
    furigana = furigana,
    rubyFontSize = rubyFontSize
)
```

`normalizeMultilineText`（`:1155`）现在是 `private`，把它改成 `internal`——这是本任务唯一必要的可见性放宽，改完同模块的构建器与测试都能用。

同一个文件再加跑马灯那一份（`:1145` 的 `normalizeSingleLineText` 同样改成 `internal`）：

```kotlin
/**
 * 跑马灯渲染点的展示文本：与 [upcomingLyricText] 同一套规则，
 * 只是逐段按 normalizeSingleLineText 折叠、用空格连接——
 * 因为那个渲染点今天显示的就是整行折叠后的单行文本，注音必须挂在同一份文本上。
 */
internal fun marqueeLyricText(
    sortedLyrics: List<SubtitleEntry>,
    index: Int,
    fallback: String,
    furigana: FuriganaSpec,
    rubyFontSize: TextUnit
): AnnotatedString = buildFuriganaAnnotatedString(
    segments = sortedLyrics.getOrNull(index)
        ?.displaySegments
        ?.map { segment -> segment.copy(text = normalizeSingleLineText(segment.text)) }
        .orEmpty(),
    plainFallback = normalizeSingleLineText(fallback),
    furigana = furigana,
    rubyFontSize = rubyFontSize,
    separator = " "
)
```

`NowPlayingLyricsPreview`（`:643`）参数表加 `furigana: FuriganaSpec`（无默认值），函数体把 `sortedLyrics` 提升为已在 `:664` 存在的局部变量，直接可用。

3b. 三处测量点替换为共用助手（`remember` 块里可以直接调用它，因为 `upcomingLyricText` 是纯函数）：

- `:798-805` `text = androidx.compose.ui.text.AnnotatedString(sourceContent.current)`
  → `text = upcomingLyricText(sortedLyrics, sourceContent.activeIndex, sourceContent.current, furigana, furiganaRubyFontSize(currentStyle))`
- `:806-815` `sourceContent.upcoming.map { lyric -> textMeasurer.measure(text = AnnotatedString(lyric.text), ...) }`
  → `text = upcomingLyricText(sortedLyrics, lyric.lyricIndex, lyric.text, furigana, furiganaRubyFontSize(upcomingStyle))`
- `measureTrackGeometry`（`:863-880`）内的 `AnnotatedString(trackContent.current)` 与 `trackContent.upcoming.forEachIndexed` 里的测量同样替换，样式参数按该函数自己的 current/upcoming 样式来。

`visibleUpcomingCount` 的 `remember` key（`:772-785`）加 `furigana`，这样开关切换会重算可显示行数——注音会让同一行的宽度需求变大，不减行就会溢出。`measureTrackGeometry` 的 `remember` key 同样要加 `furigana`。

3c. 绘制点在 `renderedLines.forEach` 里（`:1050-1070`），两处都用 `line.text` 的地方换掉：

```kotlin
                    if (renderAsCurrent && marqueeCurrentLine) {
                        SlowMarqueeText(
                            content = marqueeLyricText(
                                sortedLyrics,
                                line.lyricIndex,
                                line.text,
                                furigana,
                                furiganaRubyFontSize(currentStyle)
                            ),
                            ...
                        )
                    } else {
                        Text(
                            text = upcomingLyricText(
                                sortedLyrics,
                                line.lyricIndex,
                                line.text,
                                furigana,
                                furiganaRubyFontSize(if (renderAsCurrent) currentStyle else upcomingStyle)
                            ),
                            ...
                        )
                    }
```

`SlowMarqueeText`（`:1078` 起）的首参由 `text: String` 改成 `content: AnnotatedString`，并删掉函数体里的 `remember(text) { normalizeSingleLineText(text) }`——折叠已经上移到 `marqueeLyricText`，两处各做一遍会分叉。空白保护改成对 `AnnotatedString` 生效：

```kotlin
    val drawn = if (content.text.isBlank()) AnnotatedString(" ") else content
```

该函数里后续的 `textMeasurer.measure(text = ...)` 与 `Text(text = ...)` 都改用 `drawn`（测量与绘制同样必须是同一份带注音文本，否则滚动宽度会算错）。`furiganaRubyFontSize` 的样式参数按各渲染点自己那份样式传，别统一成一个。

3d. `NowPlayingMultilineLyrics.kt`（注意它的 `package` 是 `com.asmr.player.ui.player`）：参数表在 `style: TextStyle` 之后加**无默认值**的 `furigana: FuriganaSpec`。`MultilineCue(key: SubtitleEntry?, text: String)` 保持不变（Crossfade 的 targetState equals 语义因此不受影响），渲染处（`:81-87`）改为：

```kotlin
                Text(
                    text = remember(displayedCue, furigana, style) {
                        buildFuriganaAnnotatedString(
                            segments = displayedCue.key?.displaySegments
                                ?.map { segment -> segment.copy(text = normalizeMultilineText(segment.text)) }
                                .orEmpty(),
                            plainFallback = displayedCue.text,
                            furigana = furigana,
                            rubyFontSize = furiganaRubyFontSize(style)
                        )
                    },
                    style = style,
                    color = colors.activeText,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().testTag("multiline_lyrics_text")
                )
```

`remember` 的 key 用 `displayedCue`（含 `SubtitleEntry`，有值语义）、`furigana`、`style`，因此只在换句/切开关/改字号时重算；词典侧另有 Task 2 的行级 LRU。需要的 import：`com.asmr.player.ui.common.buildFuriganaAnnotatedString`、`com.asmr.player.ui.common.furiganaRubyFontSize`、`com.asmr.player.util.FuriganaSpec`。
`NowPlayingControls.kt:730` 的调用点加 `furigana = furigana`。

3e. `NowPlayingScreen.kt` 的 3 个 `NowPlayingLyricsPreview` 调用点（`:1827`、`:2005`、`:2426`）加 `furigana = lyricsState.furigana`。

3f. 编译验证

Run: `cmd //c "gradlew-local.bat :app:compileDebugKotlin"`
Expected: 逐个报出漏改的调用点，补完至成功。

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.ui.player.NowPlayingLyricsFuriganaTest'"`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/ui/player/nowplaying/NowPlayingControls.kt \
        app/src/main/java/com/asmr/player/ui/player/nowplaying/NowPlayingMultilineLyrics.kt \
        app/src/main/java/com/asmr/player/ui/player/NowPlayingScreen.kt \
        app/src/test/java/com/asmr/player/ui/player/NowPlayingLyricsFuriganaTest.kt
git commit -m "feat(player): annotate furigana in now-playing lyric track without touching preview state"
```

---

### Task 10: 悬浮歌词覆盖层注音

**Files:**
- Modify: `app/src/main/java/com/asmr/player/service/FloatingLyricsView.kt:89-96`
- Modify: `app/src/main/java/com/asmr/player/service/PlaybackService.kt:160, 541-543, 1288-1295`
- Test: `app/src/test/java/com/asmr/player/service/FloatingLyricsFuriganaTest.kt`

**Interfaces:**
- Consumes: Task 3 `displaySegmentsFor`、Task 6 `buildFuriganaSpanned`、Task 5 `ReadingDictionary`
- Produces:
  - `FloatingLyricsView.updateLine(text: String, cue: SubtitleEntry? = null, annotated: CharSequence? = null)`
  - `PlaybackService.@Volatile furiganaEnabled: Boolean`、`@Volatile furigana: FuriganaSpec`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/asmr/player/service/FloatingLyricsFuriganaTest.kt`：

```kotlin
package com.asmr.player.service

import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import com.asmr.player.util.DisplaySegment
import com.asmr.player.util.FuriganaSpec
import com.asmr.player.util.ReadingSource
import com.asmr.player.util.SubtitleDisplayMode
import com.asmr.player.util.SubtitleEntry
import com.asmr.player.util.displaySegmentsFor
import com.asmr.player.util.displayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsFuriganaTest {

    private val source = object : ReadingSource {
        override val ready get() = true
        override val maxSurfaceLength get() = 2
        override fun readingOf(surface: String) = if (surface == "日本") "にほん" else null
        override fun kanjiReadingOf(kanji: Char) = null
    }

    private fun overlay(): FloatingLyricsView =
        FloatingLyricsView(RuntimeEnvironment.getApplication())

    @Test
    fun updateLine_withAnnotatedText_rendersSpansIntoTextView() {
        val view = overlay()
        val entry = SubtitleEntry(0L, 1_000L, "中文", "日本")
        // 钉在 CHINESE_FIRST：这条测试要断的是注音 span 进到了 TextView，不是双语顺序。
        val plain = entry.displayText(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST)
        val annotated = com.asmr.player.ui.common.buildFuriganaSpanned(
            segments = entry.displaySegmentsFor(SubtitleDisplayMode.BILINGUAL, SubtitleBilingualOrder.CHINESE_FIRST),
            plainFallback = plain,
            furigana = FuriganaSpec(enabled = true, source = source)
        )

        view.updateLine(text = plain, cue = entry, annotated = annotated)

        val shown = findTextView(view).text
        assertEquals("中文\n日本にほん", shown.toString())
        assertNotNull((shown as Spanned).getSpans(0, shown.length, RelativeSizeSpan::class.java).firstOrNull())
    }

    @Test
    fun updateLine_chineseModeEmptySegmentsStillShowTheWholeLine() {
        // 修订 6 在覆盖层的落点：中文模式没有分段，兜底文本必须原样显示。
        val view = overlay()
        val entry = SubtitleEntry(0L, 1_000L, "只有中文")
        val plain = entry.displayText(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST)
        val annotated = com.asmr.player.ui.common.buildFuriganaSpanned(
            segments = entry.displaySegmentsFor(SubtitleDisplayMode.CHINESE, SubtitleBilingualOrder.JAPANESE_FIRST),
            plainFallback = plain,
            furigana = FuriganaSpec(enabled = true, source = source)
        )
        view.updateLine(text = plain, cue = entry, annotated = annotated)
        assertEquals("只有中文", findTextView(view).text.toString())
    }

    @Test
    fun updateLine_withoutAnnotatedText_keepsPlainBehavior() {
        val view = overlay()
        view.updateLine(text = "只有中文", cue = null, annotated = null)
        assertEquals("只有中文", findTextView(view).text.toString())
    }

    @Test
    fun updateLine_dedupesByPlainTextNotBySpannedIdentity() {
        val view = overlay()
        view.updateLine(text = "日本", annotated = "日本A")
        val first = findTextView(view).text
        // 纯文本相同则不重建，避免每帧 setText 触发覆盖层重绘
        view.updateLine(text = "日本", annotated = "日本B")
        assertEquals(first, findTextView(view).text)
    }

    @Test
    fun updateLine_plainTextChangedButAnnotationSame_replacesText() {
        val view = overlay()
        view.updateLine(text = "日本", annotated = "日本にほん")
        view.updateLine(text = "日本語", annotated = "日本語にほんご")
        assertEquals("日本語にほんご", findTextView(view).text.toString())
    }

    private fun findTextView(view: FloatingLyricsView): TextView {
        var found: TextView? = null
        fun walk(node: android.view.View): Boolean {
            if (node is TextView) { found = node; return true }
            if (node is android.view.ViewGroup) {
                for (i in 0 until node.childCount) if (walk(node.getChildAt(i))) return true
            }
            return false
        }
        walk(view)
        return requireNotNull(found) { "覆盖层里找不到 TextView" }
    }
}
```

`updateLine_dedupesByPlainTextNotBySpannedIdentity` 的期望依赖 Step 3a 的实现方式（比 plain，命中就早退），实现时如果 `first` 与第二次的是同一实例，`assertEquals` 成立；若你选择「annotated 变了也要 setText」的方案，则该测试应断言文本为 `日本B`——**保持早退语义**，因为覆盖层在播放时逐句刷新，多一次 setText 就多一次重绘。

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.service.FloatingLyricsFuriganaTest'"`
Expected: 编译失败，`updateLine` 没有 `annotated` 参数

- [ ] **Step 3: 实现**

3a. `FloatingLyricsView.kt:89` 替换 `updateLine`：

```kotlin
    fun updateLine(text: String, cue: SubtitleEntry? = null, annotated: CharSequence? = null) {
        // 去重只比纯文本：Spanned 没有值语义，直接比会导致每帧 setText。
        if (currentText == text && currentCue == cue) return
        currentText = text
        currentCue = cue
        textView.text = annotated ?: text
        resetScroll()
    }
```

3b. `PlaybackService.kt`

`:160` 的 `@Volatile subtitleDisplayMode` 之后加：

```kotlin
    @Volatile private var furigana: FuriganaSpec = FuriganaSpec.NONE
```

`:541-543` 的 collect 块扩成同时订阅注音开关（保持原有 `subtitleDisplayMode` 逻辑不变），并触发预热：

```kotlin
            launch {
                settingsDataStore.subtitleDisplayMode.collect { mode ->
                    subtitleDisplayMode = mode
                }
            }
            launch {
                settingsDataStore.japaneseFuriganaEnabled
                    .distinctUntilChanged()
                    .collect { enabled ->
                        furigana = FuriganaSpec(enabled = enabled, source = if (enabled) readingDictionary else null)
                        if (enabled) readingDictionary.warmUp()
                    }
            }
```

`readingDictionary: ReadingDictionary` 加入 `PlaybackService` 的构造注入参数（该类的注入方式与 `settingsDataStore` 一致）。

`:1290` 替换：

```kotlin
            val entry = lyrics.getOrNull(idx)
            val current = entry?.displayText(subtitleDisplayMode, subtitleBilingualOrder).orEmpty().ifBlank { " " }
            val annotated = entry?.let {
                buildFuriganaSpanned(
                    segments = it.displaySegmentsFor(subtitleDisplayMode, subtitleBilingualOrder),
                    plainFallback = current,
                    furigana = furigana
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (overlayNeeded) overlay?.updateLine(text = current, cue = entry, annotated = annotated)
            }
```

`subtitleBilingualOrder` 是双语顺序特性在 `PlaybackService` 里对应的 `@Volatile` 字段（修订 8）；名字按该类现有拼写取，编译器会因为 `displayText` 缺第二个参数而把这里点名出来，不会漏。

`plainFallback = current` 很关键：`current` 已经过 `ifBlank { " " }`，中文模式下 `displaySegmentsFor` 返回空列表时覆盖层仍旧显示那一个空格，与现在完全一致。

`buildFuriganaSpanned` 在 `furigana.enabled == false` 时返回原始 `String`，所以关闭状态的开销与行为同现在。
需要的 import：`com.asmr.player.ui.common.buildFuriganaSpanned`、`com.asmr.player.util.FuriganaSpec`、`com.asmr.player.util.displaySegmentsFor`、`com.asmr.player.data.reading.ReadingDictionary`、`kotlinx.coroutines.flow.distinctUntilChanged`。

3c. 编译验证：Run `cmd //c "gradlew-local.bat :app:compileDebugKotlin"`

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.service.FloatingLyricsFuriganaTest'"`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/asmr/player/service/FloatingLyricsView.kt \
        app/src/main/java/com/asmr/player/service/PlaybackService.kt \
        app/src/test/java/com/asmr/player/service/FloatingLyricsFuriganaTest.kt
git commit -m "feat(service): annotate furigana in floating lyrics overlay while keeping plain-text dedupe"
```

---

### Task 11: 词典资产、署名与 README

这个任务需要用户在本机提供 JMESIN/jmdict 的 XML 文件（GitHub 直连在本机超时，镜像由用户确认）。**放在代码全部落地之后**做，因为前面所有测试都不读资产；资产缺失时功能自动表现为「开关开了但没注音」，不会崩。

**Files:**
- Create: `tools/build_reading_dict.py`
- Create: `app/src/main/assets/reading/words.gz`、`kanji.gz`、`NOTICE.txt`
- Modify: `README.md`（Features 列表 + 署名）

**Interfaces:**
- Consumes: Task 4 的 `ReadingDictionaryIndex.deriveKanjiLines`（作为脚本输出的校验基准）
- Produces: `assets/reading/{words.gz,kanji.gz,NOTICE.txt}`，供 Task 5 的 `ReadingDictionary` 读取

- [ ] **Step 1: 确认词库输入可用**

问用户两件事：本机是否已有 JMESIN/jmdict 的 XML（JMdict 标签结构）文件、路径在哪；以及有没有一份「表层\t词频」的词频文件（jmdict_de_freq 之类）。**放在代码全部落地之后**做，因为前面所有测试都不读资产。没有 XML 则本任务整体暂停并向用户说明——**不要猜下载地址**；没有词频文件则按脚本的 warning 走「按输入原顺序截断」，并在提交信息里记下这一点。已有则记录路径为 `<XML>`（词频记为 `<FREQ>`，可选）。

- [ ] **Step 2: 写生成脚本**

创建 `tools/build_reading_dict.py`：

```python
#!/usr/bin/env python3
"""Generate the bundled Japanese reading dictionary assets.

Workflow (mirrors tools/encode_prompts.py: raw source stays gitignored,
only the generated artifact is committed):

    python tools/build_reading_dict.py --input <path/to/jmesin.xml> --frequency <path/to/de_freq.tsv>

Writes:
    app/src/main/assets/reading/words.gz   "surface\treading" per line, sorted
    app/src/main/assets/reading/kanji.gz   single-kanji fallback, same format
    app/src/main/assets/reading/NOTICE.txt CC-BY-SA attribution for the data

Licensing: JMESIN/jmdict are CC-BY-SA 4.0. The generated dictionary is a
derivative and ships the NOTICE file; do not strip it.
"""
import argparse
import collections
import gzip
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "reading"

KANJI = re.compile(r"[㐀-䶿一-鿿豈-﫿]")
WORD_BUDGET_BYTES = 3 * 1024 * 1024

NOTICE = """Japanese reading dictionary (app/src/main/assets/reading/)
=============================================================

Data: JMESIN / JMdict electronic dictionary.
License: Creative Commons Attribution-ShareAlike 4.0 Generic (CC-BY-SA 4.0).
Origin: Electronic Dictionary Research and Development Group.

Modifications: kept only entries whose surface form contains kanji, took the
hiragana reading (<rb>) for each surface form, deduplicated by
(surface, reading), truncated to the most frequent entries to fit a 3 MB
budget, and derived a single-kanji fallback table from entries containing
exactly one kanji. Sorted by surface form and gzip compressed.

This derivative is distributed under CC-BY-SA 4.0.
Full license: https://creativecommons.org/licenses/by-sa/4.0/legalcode
"""


def entries_from(xml_path: pathlib.Path):
    """Yield (surface, reading) pairs in file order; later duplicates lose."""
    context = ET.iterparse(str(xml_path), events=("end",))
    for _, element in context:
        if element.tag != "entry":
            continue
        for kanji_element in element.findall("k_ele"):
            surface = (kanji_element.findtext("keb") or "").strip()
            reading = (kanji_element.findtext("rb") or "").strip()
            if surface and reading and KANJI.search(surface):
                yield surface, reading
        element.clear()


def derive_kanji(words):
    """Single-kanji fallback, only from surfaces with exactly one kanji."""
    counts = collections.defaultdict(collections.Counter)
    for surface, reading in words:
        kanjis = [ch for ch in surface if KANJI.search(ch)]
        if len(kanjis) != 1:
            continue
        kanji = kanjis[0]
        tail = surface.split(kanji, 1)[1]
        value = (
            reading[: -len(tail)]
            if tail and len(reading) > len(tail) and reading.endswith(tail)
            else reading
        )
        if value:
            counts[kanji][value] += 1
    return sorted(
        (
            (kanji, max(counter.items(), key=lambda kv: (kv[1], kv[0][0]))[0])
            for kanji, counter in counts.items()
        ),
        key=lambda pair: pair[0],
    )


def write_gzipped(lines, path: pathlib.Path):
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    with gzip.open(path, "wb", compresslevel=9, mtime=0) as handle:
        handle.write(payload)
    return path.stat().st_size


def load_frequency(path: pathlib.Path):
    """Read a `surface<TAB>frequency` file (jmdict_de_freq format). Missing file -> no ordering."""
    table = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        surface, _, value = raw.partition("\t")
        if surface and value.strip().isdigit():
            table.setdefault(surface, int(value.strip()))
    return table


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument(
        "--frequency",
        type=pathlib.Path,
        help="optional surface<TAB>freq file; entries are truncated by frequency, so common words survive",
    )
    parser.add_argument("--max-entries", type=int, default=120_000)
    args = parser.parse_args()
    if not args.input.exists():
        sys.exit(f"input not found: {args.input}")

    seen = set()
    words = []
    for surface, reading in entries_from(args.input):
        pair = (surface, reading)
        if pair in seen:
            continue
        seen.add(pair)
        words.append(pair)

    if args.frequency is not None:
        if not args.frequency.exists():
            sys.exit(f"frequency file not found: {args.frequency}")
        table = load_frequency(args.frequency)
        # 稳定排序：词频高在前，没登记的保持原顺序。
        words.sort(key=lambda pair: -table.get(pair[0], 0))
    else:
        print(
            "warning: no --frequency file; truncation keeps whatever order the input has. "
            "Prefer a frequency-ordered dump (jmesin_slim / jmdict_de_freq).",
            file=sys.stderr,
        )
    words = words[: args.max_entries]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    kanji = derive_kanji(words)  # 只从最终保留的词条派生，保证与 shipped 资产自洽
    words.sort(key=lambda pair: pair[0])

    word_size = write_gzipped([f"{s}\t{r}" for s, r in words], OUT_DIR / "words.gz")
    kanji_size = write_gzipped([f"{k}\t{r}" for k, r in kanji], OUT_DIR / "kanji.gz")
    (OUT_DIR / "NOTICE.txt").write_text(NOTICE, encoding="utf-8")

    print(f"words: {len(words)} entries, {word_size} bytes gzipped")
    print(f"kanji: {len(kanji)} entries, {kanji_size} bytes gzipped")
    if word_size > WORD_BUDGET_BYTES:
        sys.exit(
            f"words.gz is {word_size} bytes, over the {WORD_BUDGET_BYTES} budget; "
            "re-run with a smaller --max-entries"
        )
    lookup = dict(words)
    for probe in ("昨日", "美味しい", "日本", "猫"):
        print(f"probe {probe} -> {lookup.get(probe, 'MISSING')}")


if __name__ == "__main__":
    main()
```

最后那个 probe 循环只是打印是否存在，不做断言——真实校验在 Step 4 用 Kotlin 侧做，避免脚本把「词典没收录」当成失败。

- [ ] **Step 3: 跑脚本生成资产**

```bash
python tools/build_reading_dict.py --input <XML 路径> --frequency <词频文件>
```
Expected: 打印 `words: N entries, X bytes gzipped`，且 `X <= 3145728`。超预算则按脚本报错提示用更小的 `--max-entries` 重跑，并把最终数字记进提交信息。

- [ ] **Step 4: 用 Kotlin 侧校验资产真实可用**

创建 `app/src/test/java/com/asmr/player/data/reading/BundledReadingDictionaryAssetTest.kt`。Robolectric 能看到真实 assets（`unitTests.isIncludeAndroidResources = true` 已在 `app/build.gradle.kts:150` 配好）：

```kotlin
package com.asmr.player.data.reading

import com.asmr.player.util.ReadingToken
import com.asmr.player.util.annotateLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 校验已提交的注音资产确实读得出来、且常用词读对了。
 * 资产不存在时跳过而不是失败：Task 11 之前跑其它测试属于正常状态。
 */
@RunWith(RobolectricTestRunner::class)
class BundledReadingDictionaryAssetTest {

    @Test
    fun bundledDictionaryReadsCommonWordsCorrectly() = runBlocking {
        val assetNames = RuntimeEnvironment.getApplication().assets.list("reading").orEmpty()
        if ("words.gz" !in assetNames) return@runBlocking

        val dictionary = ReadingDictionary(RuntimeEnvironment.getApplication())
        dictionary.warmUp()
        assertTrue("资产存在但没有就绪", dictionary.ready)

        val probes = mapOf(
            "日本語" to "にほんご",
            "美味しい" to "おいしい",
            "昨日" to "きのう"
        )
        for ((surface, reading) in probes) {
            val annotated = annotateLine(surface, dictionary).joinToString("") { token ->
                token.base + (token.reading ?: "")
            }
            assertTrue("$surface 应含 $reading", annotated.contains(reading))
        }
    }
}
```

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.data.reading.BundledReadingDictionaryAssetTest'"`
Expected: 资产已生成 → PASS 且三个探针读音都在；若某个探针不中（词库版本没收录该词形），把该探针换成确实收录的常用词并在此说明更换原因。

- [ ] **Step 5: README 补特性与署名**

`README.md` Features 列表里，紧跟现有的「字幕显示模式」那条（`:26`）插入：

```markdown
- 日文汉字注音：可在设置中开关，在日文汉字的右侧以小字显示平假名读音，字号跟随歌词字号
```

在 Disclaimer 之前插入署名小节（词典数据是 CC-BY-SA 派生物，必须署）：

```markdown
## Acknowledgements

- 日文读音数据来自 JMESIN / JMdict 电子词典（CC-BY-SA 4.0），派生词典随应用以相同许可分发，
  许可与修改说明见 `app/src/main/assets/reading/NOTICE.txt`。
```

- [ ] **Step 6: 提交**

```bash
git add tools/build_reading_dict.py app/src/main/assets/reading/ \
        app/src/test/java/com/asmr/player/data/reading/BundledReadingDictionaryAssetTest.kt \
        README.md
git commit -m "feat(readings): bundle CC-BY-SA derived Japanese reading dictionary with generator"
```

---

### Task 12: 全量测试、构建、安装与真机验收

**Files:** 无新增；只有验证与可能的修复。

- [ ] **Step 1: 全量单测**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest"`
Expected: BUILD SUCCESSFUL，无失败用例。若有失败，先读报错的符号与断言，判断是真错还是增量缓存陈旧；只有确认陈旧才 `--stop` + `-Dkotlin.incremental=false`。

- [ ] **Step 2: 确认导出仍是纯文本**

Run: `cmd //c "gradlew-local.bat :app:testDebugUnitTest --tests 'com.asmr.player.subtitle.GeneratedSubtitleFileExporterTest'"`
Expected: PASS。该测试没有覆盖注音（导出走 `displayText`），跑它是为了证明 Task 3 的改动没有牵连导出路径。

- [ ] **Step 3: 构建并安装到已连接的调试机**

```bash
cmd //c "gradlew-local.bat :app:assembleDebug"
D:/Program_Files/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`。然后 `adb shell dumpsys package com.asmr.player | grep lastUpdateTime` 与 APK mtime 对齐，确认装的是新包。

- [ ] **Step 4: 真机验收清单**（spec §10；这一步证明的是显示正确，不是编译通过）

按顺序验，任何一项不对就记下现象再修，不要一次改多项：

1. 设置 → 歌词：确认「日文汉字注音」默认关闭；开关键与文案随「字幕显示」模式切换（中文模式显示「不含日文」提示）
2. 切「日文原文」+ 开关关 → 播放页、歌词页、悬浮歌词三处文本与升级前一致
3. 开关开 → 三处汉字右侧出现小字假名；抽查 `日本語`、`美味しい` 这类词，读音位置正确、没有「びみしい」型错读
4. 切「中日双语」→ 中文行无注音，日文行有注音；长句换行后小字没有和它的汉字分到两行；把双语顺序来回切（日上 / 中上），注音始终跟着日文行，不跟位置
5. 切「中文」→ 三处完全没有注音（证明语言分段挡住了中文进词典）
6. 设置歌词字号到最小与最大 → 注音等比缩放
7. 正在播放时来回切开关 → 无需重进页面即生效；行高不跳动、色散动画正常、悬浮歌词没有闪烁
8. 生成一份导出 LRC 并用文本工具确认不含假名
9. 冷启动首句允许没有注音（词典预热），第二句起应有注音

- [ ] **Step 5: 收尾提交**

真机验证中若有任何修复，按修改的文件分别提交（`fix(lyrics): …`）。全部通过后把 spec 与计划的checkbox状态补齐并提交：

```bash
git add docs/superpowers/
git commit -m "docs(furigana): mark furigana implementation plan complete"
```

---

## 自检记录（写计划时已核对）

1. **Spec 覆盖**：§2 五个决策 → Task 1/4/5（端侧词典）、Task 6（行内小字）、Task 3（分段与导出不变）、Task 7（开关默认关）；§5 算法规格 → Task 1；§6.1-6.6 → Task 6/8/9/10；§8 降级 → Task 2/5；§4 资产与许可 → Task 11；§9/§10 测试与验收 → 各任务 + Task 12。无遗漏。
2. **占位符**：无 TBD / 「按需实现」类空步骤；每个改动点都给了代码或精确的 `文件:行` 位置。计划草稿里原有六处「故意写错、请删掉」的示例已经全部改正文里（Task 4 的空 `while`、Task 5 的假 import、Task 6 的 `spanStyles` import 与无参 `furiganaPlainText`、Task 8 的 `private val androidx...`），执行者不会再遇到需要自行判断的坏代码。
3. **类型一致性**：
   - `annotateJapanese(text, source)` / `annotateLine(text, source)` / `ReadingToken(base, reading)` / `ReadingSource(ready, maxSurfaceLength, readingOf, kanjiReadingOf)` 在 Task 1/2/4/5/6/9 一致。
   - `DisplaySegment(text, japanese)`、`orPlainFallback(plain)` 与 `displaySegmentsFor(mode, order)` 在 Task 3 新增，`withDisplayMode(mode, order)` 在 Task 3 改为顺带填段；Task 8/9 只消费 `withDisplayMode` 填好的 `entry.displaySegments`，Task 10 在悬浮歌词里直接调 `displaySegmentsFor(mode, order)`（那里拿到的是未压平的原始列表）。`order` 为兄弟特性的 `SubtitleBilingualOrder`，见修订 8。
   - `FuriganaSpec(enabled, source)` + `NONE` 在 Task 6/7/8/9/10 一致；`ReadingDictionary` 在 Task 5 定义、Task 8（`LyricsViewModel`）与 Task 10（`PlaybackService`）注入。
   - `buildFuriganaAnnotatedString(segments, plainFallback, furigana, rubyFontSize, separator = "\n")` 与 `buildFuriganaSpanned(segments, plainFallback, furigana)`：Task 6 定义，Task 8 用前者、Task 9 两个都用、Task 10 用后者；所有调用点都显式传 `plainFallback`。
   - `furiganaRubyFontSize(style)` 只在 Task 6 定义，Task 8/9 的每个绘制/测量点各自传自己那行的 `TextStyle`。
   - `FURIGANA_READING_SCALE` 只在 Task 1 定义，Task 6 引用（Compose 侧算绝对字号，TextView 侧给 `RelativeSizeSpan`）。
4. **已知的实现期风险**（真机验收要盯）：Task 9 改了 `SlowMarqueeText` 的首参类型，跑马灯那一带是本次唯一动到动画/测量结构的改动，验收清单第 7 项专门为它准备。
5. **跨特性前置（写计划后核对工作树时发现）**：「双语上下顺序」特性已经把 `SubtitleDisplayModeTest` 改成断 `displayText(mode, SubtitleBilingualOrder)`，本计划的 Task 3/8/9/10 全部按两参数形状编写，并各留了一处 `CHINESE_FIRST` / `JAPANESE_FIRST` 的顺序断言。**该特性是本计划 Task 3 的硬前置**：若开工时 `SubtitleDisplayMode.kt` 仍是单参数，先报阻塞，不要在本计划内替它实现顺序开关（Task 3 Step 0 就是这道门）。
