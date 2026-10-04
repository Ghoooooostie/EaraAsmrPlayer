# 日语汉字注音开关 — 设计文档

日期：2026-10-04
状态：已确认，待实现
范围：EaraAsmrPlayer（Kotlin + Jetpack Compose，`minSdk = 24`）
实现计划：`docs/superpowers/plans/2026-10-04-japanese-furigana.md`（计划落地时对本文 §3.1、§3.2、§4.2、§6.1、§6.2、§6.3、§6.4、§6.5、§8、§9、§11 做了 8 处必要修订，均已回写本文，两份文档一致）

## 1. 背景与目标

应用已经能显示日文原文字幕（设备端 ASR 生成 + AI 翻译成中文），并已有「字幕显示模式：中文译文 / 日文原文 / 中日双语」这条完整链路。但纯汉字+假名混排的日文对中文使用者有读不出来的门槛，例如「貴方」「素敵」「言えない」中的汉字。

目标：新增一个用户可开关的设置项，开启后在**日文文本**的汉字右侧以小字号平假名显示读音（行内注音）。

非目标（明确不做，避免范围漂移）：

- 不做汉字上方的双行 ruby 排版（`minSdk = 24`，系统级 ruby 基本只有 Android 12+ 可用）
- 不给注音单独的可调字号（用比例常量自动跟随歌词字号）
- 不给作品标题、歌手名、搜索词做注音（只做歌词/字幕的三个显示面）
- 不改导出的 LRC/VTT/SRT 内容
- 不按需下载词典，不让 AI 生成读音，不做罗马音
- 不做纵向文本、不做分词形态分析

## 2. 已确认的决策

| 决策点 | 选定 | 理由 |
|---|---|---|
| 读音来源 | 端侧内置词典 | 离线、对所有已存在字幕立即生效、不花 AI token、不需重新生成 |
| 显示形态 | 行内小字注音 | `minSdk 24` 下唯一低成本可靠方案；复用现有字号/描边/阴影参数 |
| 生效范围 | 播放页 + 歌词页 + 悬浮歌词；导出不加 | 保持字幕文件干净可复用 |
| 未命中处理 | 词级优先，落单字音读表，仍无则不注音 | 覆盖率与正确率的平衡 |
| 接口形状 | 方案 A：`SubtitleEntry` 携带语言分段 | 唯一能确保中文译文不被误注的形状 |

## 3. 架构与边界

三层，依赖方向单一：

```
data/reading/ReadingDictionary.kt      assets I/O + 进程内单次尝试守卫（唯一有 Android 依赖的层）
        │ 解析委派给
data/reading/ReadingDictionaryIndex.kt 排序数组 + 二分 + 单字派生（纯 JVM）
        │ implements
util/JapaneseReadingSupport.kt         ReadingSource 抽象 + 匹配算法 + 行级缓存（纯 JVM，可单测）
        │ produces List<ReadingToken>
ui/common/FuriganaText.kt              格式映射：AnnotatedString（Compose）/ Spanned（TextView）
```

关键边界：分词算法不接触 `Context` 和 assets。算法层只依赖 `ReadingSource` 接口，测试时注入内存词典即可。将来若要改成汉字上方 ruby，只改 `ui/common/FuriganaText.kt`，算法与数据层不动。

### 3.1 数据结构（`util/JapaneseReadingSupport.kt`，新建）

```kotlin
/** 一个注音单元：base 为原文字面，reading 为需要以小字显示的假名；null 表示不注音。 */
data class ReadingToken(val base: String, val reading: String? = null)

/** 注音相对基字的字号比例。 */
const val FURIGANA_READING_SCALE = 0.55f

/** 词典查询能力，由 data 层实现，便于算法层在 JVM 上测试。 */
interface ReadingSource {
    /** 词典是否已加载可用；未就绪时缓存层必须跳过写入（见 §8）。 */
    val ready: Boolean
    val maxSurfaceLength: Int
    fun readingOf(surface: String): String?
    fun kanjiReadingOf(kanji: Char): String?
}

/** 对一段文本做注音切分；source 为 null 或未就绪时返回不注音的结果（每个 token 的 reading 均为 null）。 */
fun annotateJapanese(text: String, source: ReadingSource?): List<ReadingToken>

/** 带行级 LRU 缓存的入口，渲染层只用这个。 */
fun annotateLine(text: String, source: ReadingSource?): List<ReadingToken>
```

`ready` 是必需的：§8 的「未就绪不写缓存」规则要求缓存层能区分「词典还没加载完」和「词典已加载但这行查不到读音」。若只看 `readingOf` 返回 null，两种情况无法分辨，会把预热期的空结果永久缓存下来，注音就再也不会出现。

`annotateLine` 内部持有行级 LRU（见 §8），是 Compose 与 `PlaybackService` 共用的唯一入口，这样两个进程边界的缓存行为与「未就绪不缓存」规则只有一份实现。

### 3.2 词典实现（`data/reading/ReadingDictionary.kt`，新建）

`@Singleton`（Hilt），实现 `ReadingSource`：

- 存储结构：排序后的双数组 `Array<String>` surfaces + `Array<String>` readings；查询用二分定位前缀下界，再线性扫描到前缀结束。选它而不是 `HashMap`，省掉约 40% 的桶开销，在手机上值得。
- 单字回退表：同样排序数组，键为单个汉字。
- 加载：仅在「开关为开」时由 `suspend fun warmUp()` 触发，`Dispatchers.Default` + `Mutex` + `attempted` 守卫保证整个进程只尝试一次（失败也不重试，避免重试风暴）。
- 未就绪时 `ready` 为 false，`readingOf`/`kanjiReadingOf` 返回 null，不抛异常。

解析与 IO 分两个类：`ReadingDictionaryIndex`（纯 JVM，`parse(wordLines, kanjiLines)` 与 `deriveKanjiLines(wordLines)`，无任何 Android 依赖，单测直接喂字符串列表）与 `ReadingDictionary`（`@Inject constructor(@ApplicationContext context)`，只做 assets 解压与委派）。

这里有一个必须遵守的形状约束：Dagger 无法注入带默认值的构造参数，所以「可替换的资产打开方式」不能写成构造参数，只能是方法参数——`internal suspend fun warmUpWith(open: (String) -> InputStream)` 供测试注入失败路径，生产的 `warmUp()` 委托到 `context.assets.open(it)`。

## 4. 词典资产与构建工具

### 4.1 数据来源与许可

JMESIN（EDRP 维护，基于 jmdict 的 CC-BY-SA 4.0 派生词典）。取 `<k_ele>` 里的 `<keb>`（表层）与 `<rb>`（**平假名**读音），不取 `<rok>`（片假名）——注在汉字右侧读起来更符合预期。

许可义务（必须履行，不是可选项）：

- `app/src/main/assets/reading/NOTICE.txt` 记录出处、许可、修改说明
- README 的免责声明/致谢区补一行词典署名
- 派生词典按 CC-BY-SA 相同许可共享

### 4.2 构建脚本 `tools/build_reading_dict.py`（新建）

沿用 `tools/encode_prompts.py` 的既有模式：原始 XML 留在本机 gitignore 目录，仓库只提交生成物。`.gitignore` 已有全局 `*.xml` 规则（仅 `app/src/**/*.xml` 例外），原始词库天然不会被误提交。

```
输入：--input <本地 jmesin/jmdict XML>
      --frequency <可选：表层\t词频 的 TSV，用于截断与读音加权>
输出：app/src/main/assets/reading/words.gz    // 表层\t读音，一行一条，按表层排序
      app/src/main/assets/reading/kanji.gz    // 汉字\t读音
      app/src/main/assets/reading/NOTICE.txt
参数：--max-entries N   超预算时按词频截断（不静默塞爆 APK）
```

处理顺序是固定的，不能边解析边截断：解析并去重 → （有 `--frequency` 时）按词频排序 → 按 `--max-entries` 截断 → 从**截断后**的词条派生单字表 → 按表层排序 → gzip。截断若发生在派生之前，单字表就会建立在「文件顺序的前 N 条」这种毫无代表性的子集上；未提供 `--frequency` 时脚本必须在 stderr 明确警告截断是按词库顺序而非词频进行的。

处理规则：

1. 只保留表层含汉字的词条（纯假名词对注音无用）
2. 按 (表层, 读音) 去重；同一表层多个读音时保留词频最高的一条
3. 单字回退表**只**从「表层恰含一个汉字」的词条派生，取词频加权最高的读音。多汉字词条（`日本語`）的读音根本不构成该字的候选音，混进来会把「日」读成「にほんご」这类污染结果。这样仍少一个外部依赖、且与词库版本自洽。
4. 排序后 gzip 写入

派生单字表的已知代价：`食` 只会来自单词条 `食`/しょく 之类，而 `食べる` 不参与派生。可接受，因为送假名场景（食べる）本来就先被词级命中，单字表只兜底生僻字。

### 4.3 预算

- `words.gz` 压缩后 ≤ 3 MB
- 词典常驻内存 ≤ 20 MB
- 单行 `annotateJapanese` 目标 < 1 ms；LRU 命中时接近 0

超预算时的处理见 4.2 的 `--max-entries`。

## 5. 匹配算法规格

扫描整行，按位置推进。「汉字」判定为 CJK 统一表意文字 `U+4E00–U+9FFF`、扩展 A `U+3400–U+4DBF`、兼容汉字 `U+F900–U+FAFF`；假名、数字、拉丁、标点、长音符「ー」一律原样输出且永不注音。

```
i = 0
while i < len:
  ① 若 text[i..] 是「汉字段 + （假名）」形式的自带注音（括号见下）：
     输出 ReadingToken(base = 汉字段, reading = 括号内假名)，跳过括号
  ② 若当前字符不是汉字：原样累积输出（reading = null），i++
  ③ 汉字段 [i, kanjiEnd)，尝试匹配，命中后指针推进到被匹配表层之后：
     a. 送假名优先：对窗口长度 runLen..1 的每个汉字前缀，
        依次尝试「汉字前缀 + 后续 0..4 个假名」，取最长命中的表层 K
        命中后：okurigana = K 中跟在汉字后面的假名
                displayReading = 词典读音去掉尾部与 okurigana 相同的那段（不完全相同则不删，保留整条读音）
                输出 ReadingToken(base = K 的汉字部分, reading = displayReading)
                再把 okurigana 作为独立 token 原样输出（reading = null）
        例：美味しい → 词典 美味しい/おいしい → base 美味 + reading おい，再原样输出 しい
            显示效果：美味 + 小字おい + しい，即标准 ruby 的「美味(おい)しい」
            指针跨过 美味しい 整段，しい 不再被二次处理
     b. 纯汉字段最长匹配（长度 ≥ 2），支持部分消耗：日本語 → 日本(にほん) + 語(ご)
     c. 单字回退表
     d. 全不命中 → 该汉字不注音（base 为该汉字，reading = null），i 前进一个字符
```

规则 ① 的括号形态：`（）`、`()`、`［］`、`[]`，且括号内必须是纯假名（含拗音「ゃゅょ」促音「っ」长音「ー」）。已自带注音的抓取来源字幕不重复叠加。

规则 ③a 是本设计的核心质量赌注：没有送假名扩展，「美味しい」会被拆成「美味(びみ) + しい」→ 显示成「びみしい」，属于明显错读。有了 ③a，读音正确且指针不会重复消费送假名。

行内注音的位置约定：reading 始终紧跟它所修饰的**汉字部分**，因此送假名场景下小字出现在汉字与送假名之间（`美味おいしい`），而不是整个词之后。这是行内形态唯一能做到的、且与标准 ruby 一致的插入位置。

输出可逆性：所有 token 的 `base` 依次拼接，等于原文去掉规则 ① 那对括号之外的全部内容——即注音不改变基字文本本身。

## 6. 渲染层规格

### 6.1 注音形式

平假名以小字号紧贴汉字右侧，无括号、无分隔符、同色，继承现有的阴影与描边。

注音字号必须**按比例从所在行的字号推导**，这是刻意的；但两端的落地形式不同：

- Compose：构建器接收一个绝对 `TextUnit` 参数 `rubyFontSize`，由调用点用 `furiganaRubyFontSize(style) = style.fontSize * FURIGANA_READING_SCALE` 从**该行实际生效的** `TextStyle` 算出。不在 `SpanStyle` 里写 `.em` 相对单位——本项目锁的 Compose BOM（2024.02.00）下，`AnnotatedString` 里的相对字号在测量与绘制两条路径上的解析基准并不一致，会直接导致行高与实测高度对不上；传入 `style.fontSize` 又漏掉了 `lineHeight`/`MaterialTheme` 覆写。显式绝对值是唯一可靠的形状。
- TextView：`RelativeSizeSpan(FURIGANA_READING_SCALE)`，天然相对 `textView.textSize`，无需换算。

歌词字号本就是用户可调的（`LyricsPageSettings.fontSizeSp`、`FloatingLyricsSettings.size`），比例常量即自动跟随，因此不需要新增「注音字号」设置项。副作用是测量与绘制必须使用同一个 `rubyFontSize`（见 §6.5 的测量行）。

### 6.2 硬约束

**开关关闭、或该行不含日文段时，构建器输出必须与现状逐字节一致**（即等价于纯文本的 `AnnotatedString` / 纯 `String`）。这是防止新功能意外改变任何现有显示的安全网，由自动化测试守住。

允许两类文本变化，且只有这两类：

1. 开启注音且原文自带括号注音时（算法规则 ①），括号被吸收进读音。
2. `BILINGUAL` 模式下，中/日两段之间由现有 `displayText` 的单次 `\n` 拼接变成显式分段拼接——结果字符串相同。

注意由此引出的一个必须防住的实现陷阱：`CHINESE` 模式下 `displaySegments` 是**空列表**（见 §6.4），外部抓取字幕同样不填段。任何「只看 segments」的渲染点会在中文模式下把整行渲染成空白。因此每个渲染与测量点都必须通过 `orPlainFallback(plain)` 兜底（见 §6.3），不允许直接消费可能为空的 segments。

### 6.3 共享构建器（`ui/common/FuriganaText.kt`，新建）

为避免把「开关 + 词典」两个参数逐个透传到 5 个渲染点，把设置侧与数据侧打包成一个值对象，定义在 `util/SubtitleDisplayMode.kt`（紧挨 `DisplaySegment`）：

```kotlin
/** 渲染注音所需的全部输入；无词典可用时 source 为 null，等价于不注音。 */
data class FuriganaSpec(val enabled: Boolean, val source: ReadingSource?) {
    companion object {
        val NONE = FuriganaSpec(enabled = false, source = null)
    }
}
```

`FuriganaSpec.NONE` 作为默认值，让任何未接入的调用点自动落在「无注音」行为上。

构建器签名：

```kotlin
fun buildFuriganaAnnotatedString(
    segments: List<DisplaySegment>,
    plainFallback: String,
    furigana: FuriganaSpec,
    rubyFontSize: TextUnit,
    separator: String = "\n"
): AnnotatedString

fun buildFuriganaSpanned(segments: List<DisplaySegment>, plainFallback: String, furigana: FuriganaSpec): CharSequence

/** 剥离读音后的纯文本；悬浮歌词的去重门与单测的逐字节断言都用它。 */
fun furiganaPlainText(segments: List<DisplaySegment>, plainFallback: String): String

/** 段的空/blank 过滤 + 空列表兜底，三个构建器共用同一份实现。 */
private fun resolveSegments(segments: List<DisplaySegment>, plainFallback: String) =
    segments.filter { it.text.isNotBlank() }.orPlainFallback(plainFallback)
```

内部对每个 `japanese == true` 的段调用 `annotateLine`，其余段直接取文本。段间以 `separator` 连接（默认 `\n`，沿用现有双语拼接行为）；中文段永不送进词典。

`plainFallback` 不是可选的装饰，而是 §6.2 那个空段陷阱的结构性封堵：构建器一律先 `resolveSegments`，segments 为空（CHINESE 模式、外部字幕）时退化为单段纯文本，因此**任何**渲染点都不可能画出空白行。`separator` 的存在是因为跑马灯接缝把多行压成一行（见 §6.5）。

`furigana.enabled == false` 时构建器**不做任何词典调用**，直接走纯文本路径——这是 §6.2 硬约束的实现保证，也让关闭状态下开销严格为零。

### 6.4 语言分段（方案 A）

`util/SubtitleParser.kt:12` 的 `SubtitleEntry` 增加带默认值的字段：

```kotlin
data class SubtitleEntry(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val japaneseText: String = "",
    val displaySegments: List<DisplaySegment> = emptyList()
)

data class DisplaySegment(val text: String, val japanese: Boolean)
```

```kotlin
/** 按显示模式与双语顺序取该条字幕的语言分段。 */
fun SubtitleEntry.displaySegmentsFor(mode: SubtitleDisplayMode, order: SubtitleBilingualOrder): List<DisplaySegment>

/** segments 可能为空（CHINESE 模式、外部抓取字幕），渲染点必须兜底。 */
fun List<DisplaySegment>.orPlainFallback(plain: String): List<DisplaySegment> =
    if (isNotEmpty()) this else listOf(DisplaySegment(plain, japanese = false))
```

`displayText` / `withDisplayMode` 在本文确认后又因「双语上下顺序」特性变成了 `(mode, order)` 两参数（`order: SubtitleBilingualOrder`，默认 `JAPANESE_FIRST`），所以「签名不变」这句已不成立；本功能的约束改为**分段顺序必须与 `displayText(mode, order)` 的压平顺序逐字等价**，且该顺序参数没有默认值，漏传会被编译器点名。双语顺序特性是本功能的硬前置。

`withDisplayMode(mode, order)` 只在 JAPANESE/BILINGUAL 分支额外填 `displaySegments`：

| 模式 | segments |
|---|---|
| `CHINESE` | 空列表，且沿用现有「CHINESE 不压平文本」的短路语义（现有测试 `withDisplayMode_chineseReturnsSameList` 比较内容而非引用，必须继续通过） |
| `JAPANESE` | `[(text=日文, japanese=true)]`；无日文原文时退回 `[(中文, false)]` |
| `BILINGUAL` | 顺序随 `order`：`JAPANESE_FIRST` → `[(日文, true), (中文, false)]`，`CHINESE_FIRST` → `[(中文, false), (日文, true)]`；「日文为空 / 中文为空 / 中日相同」三条退化分支的段与 `text` 一致 |

CHINESE 分支不填段（它不把整轨压平，改它会破坏上述测试），所以经 `withDisplayMode` 处理过的列表读 `entry.displaySegments`，而悬浮歌词那种拿到的是**未压平原始列表**的路径必须读 `displaySegmentsFor(mode, order)`；两者对 CHINESE 都返回空列表，由 `orPlainFallback` 完成兜底。

`text` 字段的压平行为一个字都不改。因此 `GeneratedSubtitleFileExporter.kt:195`（导出）、`SubtitleHash`、`LyricsLoader.normalizeAndDistinct`、Room 实体全部不感知注音，导出文件保持纯文本。

### 6.5 渲染接入点（共 6 处）

| 文件 | 现状 | 改动 |
|---|---|---|
| `AppleLyricsView.kt:138` | 无注音参数 | 增加 `furigana: FuriganaSpec`，**不给默认值**，强制两个调用点（`LyricsPage.kt:142`、`NowPlayingSurfaceComponents.kt:263`）显式传，杜绝漏改 |
| `AppleLyricsView.kt:904` `LyricLineText` | `text: String`，调用点 L613 | 改 `AnnotatedString`；色散动画那两次 ghost `Text` 自动继承 span 样式。L613 处把内联的 `MaterialTheme.typography.titleLarge.copy(...)` 提为 `val lineStyle`，注音字号由它经 `furiganaRubyFontSize(lineStyle)` 得出 |
| `AppleLyricsView.kt:885` `measureLyricItemHeight` | `AnnotatedString(entry.text)` | measure 同一个 AnnotatedString，且 `rubyFontSize` 由**入参 `measurementStyle`** 算出——测量与绘制必须用同一个绝对字号，否则行高会跳 |
| `NowPlayingControls.kt:681` `sourceContent` | `current: String` + `upcoming: List<String>`，L799/L806 用它测高 | **保持 `String` 不变**，见下方说明；改为在测量与绘制这两个真正使用文本的地方按需派生 `AnnotatedString` |
| `NowPlayingControls.kt:1050` `SlowMarqueeText` / `Text` | `text = line.text`（`String`），`marqueeCurrentLine = true`（`NowPlayingScreen.kt:2440`）时当前行被压成单行滚动 | 两个分支都改为接收 `AnnotatedString`；`SlowMarqueeText` 首参类型跟着换。跑马灯路径必须用 `marqueeLyricText(...)`（逐段 `normalizeSingleLineText` + `separator = " "`），普通路径用 `upcomingLyricText(...)`（逐段 `normalizeMultilineText`） |
| `FloatingLyricsView.kt:89` `updateLine(text: String, cue)` | 内部 `textView.text = text`，L89 有 `currentText == text` 去重门 | 增加可选 `annotated: CharSequence?` 参数，`textView.text` 用 `annotated ?: text`；**去重门继续比纯文本**（`SpannedString` 无值语义，直接比会导致每帧重复 setText）；`PlaybackService.kt:1290` 改为构建 Spanned |

`NowPlayingControls` 这一行与原设计的差别是刻意的，理由是性能与正确性两重：

`sourceContent` / `settledContent` / `renderedLines` 全都住在 `remember(...)` 里，并且被用作动画与几何的 key。`AnnotatedString` 的 `equals` 会把 span 一起比较，把它塞进 `remember` 键或 `NowPlayingLyricsTrackLine` 这类参与 `key(...)` 与差分的状态里，等于每帧都可能判定「变了」，触发整条歌词的重新测量与动画重启。而这些 remember 块的键（`currentFontSize`、`upcomingStyle.fontSize` 等）并不包含注音开关，改了类型也不会自动失效——这是最难发现的一类回归。

因此让文本状态继续存 `String`（现状形状一字不改），只在 `measureTrackGeometry`（`:868`、`:881-888`）与绘制（`:1050-1070`）两处经 `annotateLine` 的行级 LRU 派生注音结果。LRU 命中时开销接近零，而状态图、动画键与 `NowPlayingMultilineLyrics` 的 `MultilineCue` 全部保持原样。

`NowPlayingMultilineLyrics`（`nowplaying/NowPlayingMultilineLyrics.kt:37`）同样只在 `Text` 处派生：参数表加无默认值的 `furigana: FuriganaSpec`，而 `MultilineCue(key, text: String)` 一个字都不改——它是 `Crossfade` 的 `targetState`，改成 `AnnotatedString` 会连带影响淡入淡出的差分判定。

`normalizeSingleLineText` / `normalizeMultilineText`（`:1145-1165`，现为 `private`）需要改为 `internal`，因为跑马灯与预览的注音文本必须**逐段**做同样的规范化后再拼接。直接对原始 segments 注音会跳过规范化，破坏 §6.2 的逐字节一致：现状是「先拼成 `"$中文\n$日文"` 再整串规范化」，注音版必须复现同样的结果。

### 6.6 数据流单一来源

注音输入在两处组装成 `FuriganaSpec`，UI 层不再各自 collect（避免滚动/切页时闪烁）：

- **Compose 侧**：`LyricsViewModel.kt:39` 的 `combine` 同时并入 `settingsDataStore.japaneseFuriganaEnabled`，产出 `FuriganaSpec(enabled, readingDictionary)`，与歌词列表一起在 `LyricsUiState` 里下发；`AppleLyricsView`、`NowPlayingControls`、`LyricsPage` 从 state 取，不注入词典本身
- **悬浮歌词侧**：照 `PlaybackService.kt:160` 的 `@Volatile subtitleDisplayMode` 模式新增 `@Volatile furigana: FuriganaSpec = FuriganaSpec.NONE`，在 `:541` 那个 collect 里一起更新；开关变为 true 时在同一协程作用域启动 `readingDictionary.warmUp()`

词典单例因此只有两个持有者（`LyricsViewModel`、`PlaybackService`），渲染 composable 一律只收 `FuriganaSpec`。

## 7. 设置项

放在 `data/local/datastore/SettingsDataStore.kt`，与 `subtitleDisplayModeKey`（`:50`、`:111`、`:233`）同一处，不放 `data/settings/SettingsDataStore.kt`：

```kotlin
private val japaneseFuriganaEnabledKey = booleanPreferencesKey("japanese_furigana_enabled")
val japaneseFuriganaEnabled: Flow<Boolean>   // 默认 false
suspend fun setJapaneseFuriganaEnabled(enabled: Boolean)
```

`SettingsViewModel.kt` 加 `StateFlow`（对齐 `:162` 的 `subtitleDisplayMode`）。

设置 UI 接在 `SettingsScreen.kt` 的 `SubtitleDisplayModeSection`（`:1371`）之后，复用私有组件 `SettingsToggleRow`（`:2671`）：

- 标题：`日文汉字注音`
- 副标题（正常态）：`在日文汉字的右侧以小字显示平假名读音，字号自动跟随歌词字号；不影响导出的字幕文件`
- 副标题（`subtitleDisplayMode == CHINESE` 时）：`当前字幕显示模式不含日文，切到「日文原文」或「中日双语」才会生效`
  ——这是唯一额外的 UI 判断，专门挡住「开了怎么没反应」这类反馈
- 默认关闭

文案继续硬编码简体中文：项目 `strings.xml` 只有 `app_name`，没有 i18n 系统，不为此功能新造一套。

## 8. 错误处理与降级

| 情况 | 行为 |
|---|---|
| 资产缺失 / 解压失败 / 格式非法 | 进程内标记词典不可用，日志只记一次，行为完全等同开关关闭；不抛、不崩溃；`Mutex` 保证不会重试风暴 |
| 词典尚未预热完成 | 该行按无注音渲染 |
| 汉字查不到任何读音 | 该汉字正常显示，不注音 |
| 用户关闭开关 | 不加载 assets，不解析词典，渲染层零开销 |

**关键规则：词典未就绪或加载失败时不写 LRU 缓存。** 判定依据是 `source.ready`（§3.1），不是「这一行的结果里有没有读音」——后者会把预热期的正常空命中永久缓存。这让降级链自动愈合——预热完成后下一次渲染自然带上注音，不需要「就绪后通知 UI 刷新」的额外状态机制。代价是最早一两句可能没注音。

行级缓存：由 §3.1 的 `annotateLine` 独占持有（`LinkedHashMap(accessOrder = true)`，容量约 2000，加锁保护），Compose 与 `PlaybackService` 共享同一份缓存实现，不各自造缓存。`NowPlayingControls` 的按需派生（§6.5）正是靠这份缓存把重复计算压掉。

## 9. 测试计划

测试目录 `app/src/test/java/com/asmr/player/`，JUnit4 + `org.junit.Assert`，命名沿用 `method_行为` 风格。

1. **`util/JapaneseReadingSupportTest`**（纯 JVM，注入内存 `ReadingSource`，不读 assets）
   - 词级优先于单字：`昨日` → `きのう`，不是 `さく` + `じつ`
   - 送假名匹配：`美味しい` → `おいしい`，不是「びみしい」
   - 部分消耗：`日本語` → `日本`(にほん) + `語`(ご)
   - 假名 / 数字 / 拉丁 / 标点 / 长音「ー」/ 拗音原样且 `reading == null`
   - 未命中汉字不注音；空串与纯假名行不产生任何 reading
   - 自带括号注音 `漢字（かんじ）` 不重复叠加，且括号被吸收
   - 四种括号形态各一条
   - `annotateLine`：同一行二次调用命中缓存；`source` 为 null 时**不写缓存**（预热完成后同一次调用能立刻拿到注音）
2. **`util/SubtitleDisplayModeTest` 扩展**：三种模式的 segments 结果；CHINESE 段为空（老断言保持不动）；BILINGUAL 退化分支段与 `text` 一致
3. **`data/reading/ReadingDictionaryIndexTest`**（纯 JVM）：排序数组二分前缀命中、(表层, 读音) 去重、单字表只从恰含一个汉字的表层派生、`ready` 在空索引时为 false
4. **`data/reading/ReadingDictionaryTest`**：经 `warmUpWith` 注入失败流，断「只尝试一次」（含 `attempted` 守卫计数）、失败后 `ready` 保持 false、不抛异常
5. **`ui/common/FuriganaTextTest`**（Robolectric）：`FuriganaSpec(enabled = false, source = 可用词典)` 时输出与纯文本逐字节相等；开启时 span 只覆盖读音区间、字号等于传入的 `rubyFontSize`；`RelativeSizeSpan` 起止一致；双语段只在 `japanese == true` 那段产生 span；segments 为空 / 全 blank 时退回 `plainFallback`；`separator = " "` 的跑马灯形态
6. **设置往返**：照 `ui/library/LibraryPreferencesStoreTest.kt` 的范式（`@RunWith(RobolectricTestRunner::class)` + `PreferenceDataStoreFactory.create` + 临时目录），断默认值 `false`、key 名稳定、set 后读回。注意 `data/local/datastore/SearchCacheStoreTest.kt` 是纯函数测试，不是 DataStore 往返范式，不要照它写
7. **渲染接缝各测两条**：`AppleLyricsViewFuriganaTest`（ViewModel 组装的 `FuriganaSpec` 是否走词典 + 测量与绘制共用的 `lyricLineAnnotated`）、`NowPlayingLyricsFuriganaTest`（`upcomingLyricText` 与 `marqueeLyricText` 的规范化结果必须与现状逐字节一致）、`FloatingLyricsFuriganaTest`（去重门比纯文本、`annotated` 为空时行为不变）。不尝试在 Robolectric 里渲染完整 `AppleLyricsView`
8. **`BundledReadingDictionaryAssetTest`**（Robolectric，真实 assets）：`words.gz`/`kanji.gz` 能解压、格式合法、条数与体积在 §4.3 预算内

## 10. 真机验收清单

类型检查与单测证明不了显示正确，以下必须真机走一遍：

- 三种显示模式 × 开关两态 × 三个面（播放页、歌词页、悬浮歌词）共 18 格观感
- 歌词字号调到最小与最大，注音是否等比跟随
- 双语长句换行时，小字是否与所属汉字保持在同一行
- 行高在开关切换前后不跳动；色散动画、OLED 像素平移保护无回归
- 开关切到开 → 正在播放的歌词无需重进页面即生效
- 导出 LRC 后用文本比对确认内容不含假名
- 冷启动首句歌词可能无注音（预热），第二句起应正常——确认这是可接受体验

## 11. 改动文件清单

新建：

- `app/src/main/java/com/asmr/player/util/JapaneseReadingSupport.kt`
- `app/src/main/java/com/asmr/player/data/reading/ReadingDictionaryIndex.kt`
- `app/src/main/java/com/asmr/player/data/reading/ReadingDictionary.kt`
- `app/src/main/java/com/asmr/player/ui/common/FuriganaText.kt`
- `tools/build_reading_dict.py`
- `app/src/main/assets/reading/{words.gz,kanji.gz,NOTICE.txt}`
- 测试：`util/JapaneseReadingSupportTest.kt`、`data/reading/ReadingDictionaryIndexTest.kt`、`data/reading/ReadingDictionaryTest.kt`、`ui/common/FuriganaTextTest.kt`、`data/local/datastore/FuriganaSettingStoreTest.kt`、`ui/player/AppleLyricsViewFuriganaTest.kt`、`ui/player/NowPlayingLyricsFuriganaTest.kt`、`service/FloatingLyricsFuriganaTest.kt`、`data/reading/BundledReadingDictionaryAssetTest.kt`（`util/SubtitleDisplayModeTest.kt` 为追加方法，现有断言不动）

修改：

- `util/SubtitleParser.kt`（`SubtitleEntry` 加字段）
- `util/SubtitleDisplayMode.kt`（`DisplaySegment`、`orPlainFallback`、`displaySegmentsFor`、`FuriganaSpec`、填段逻辑）
- `data/local/datastore/SettingsDataStore.kt`（新 key）
- `ui/settings/SettingsViewModel.kt`、`ui/settings/SettingsScreen.kt`
- `ui/player/LyricsViewModel.kt`、`ui/player/AppleLyricsView.kt`、`ui/player/LyricsPage.kt`
- `ui/player/nowplaying/NowPlayingControls.kt`（含 `normalizeSingleLineText`/`normalizeMultilineText` 改 `internal`、文件内私有的 `SlowMarqueeText` 首参改 `AnnotatedString`）、`ui/player/nowplaying/NowPlayingSurfaceComponents.kt`、`ui/player/nowplaying/NowPlayingMultilineLyrics.kt`
- `service/FloatingLyricsView.kt`、`service/PlaybackService.kt`
- `README.md`（特性列表 + 词典署名）

## 12. 已知局限

写在这里以免日后被当成 bug 上报：

- 专有名词、人名、特殊读法（例：`行定` → `くんじょう`、台本里的角色名与造词）会读成常规音读
- 没有形态分析，动词/形容词活用形能否读对取决于词库是否收录该形
- 词库未覆盖的汉字直接不注音（不猜）
- 冷启动最初一两句可能没有注音（词典预热未完成）
- 行内小字注音不等于真 ruby：假名在右侧而非上方，长句换行时小字可能与其汉字被换行分开
