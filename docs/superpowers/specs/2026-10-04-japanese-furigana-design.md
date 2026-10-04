# 日语汉字注音开关 — 设计文档

日期：2026-10-04
状态：已确认，待实现
范围：EaraAsmrPlayer（Kotlin + Jetpack Compose，`minSdk = 24`）

## 1. 背景与目标

应用已经能显示日文原文字幕（设备端 ASR 生成 + AI 翻译成中文），并已有「字幕显示模式：中文译文 / 日文原文 / 中日双语」这条完整链路。但纯汉字+假名混排的日文对中文使用者有读不出来的门槛，例如「貴方」「素敵」「言えない」中的汉字。

目标：新增一个用户可开关的设置项，开启后在**日文文本**的汉字右侧以小字号平假名显示读音（行内注音）。

非目标（明确不做，避免范围漂移）：

- 不做汉字上方的双行 ruby 排版（`minSdk = 24`，系统级 ruby 基本只有 Android 12+ 可用）
- 不给注音单独的可调字号（用相对单位自动跟随歌词字号）
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
data/reading/ReadingDictionary.kt      Android assets I/O + 存储结构（唯一有 Android 依赖的层）
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

/** 词典查询能力，由 data 层实现，便于算法层在 JVM 上测试。 */
interface ReadingSource {
    val maxSurfaceLength: Int
    fun readingOf(surface: String): String?
    fun kanjiReadingOf(kanji: Char): String?
}

/** 对一段文本做注音切分；source 为 null 或未就绪时返回不注音的结果（每个 token 的 reading 均为 null）。 */
fun annotateJapanese(text: String, source: ReadingSource?): List<ReadingToken>

/** 带行级 LRU 缓存的入口，渲染层只用这个。 */
fun annotateLine(text: String, source: ReadingSource?): List<ReadingToken>
```

`annotateLine` 内部持有行级 LRU（见 §8），是 Compose 与 `PlaybackService` 共用的唯一入口，这样两个进程边界的缓存行为与「未就绪不缓存」规则只有一份实现。

### 3.2 词典实现（`data/reading/ReadingDictionary.kt`，新建）

`@Singleton`（Hilt），实现 `ReadingSource`：

- 存储结构：排序后的双数组 `Array<String>` surfaces + `Array<String>` readings；查询用二分定位前缀下界，再线性扫描到前缀结束。选它而不是 `HashMap`，省掉约 40% 的桶开销，在手机上值得。
- 单字回退表：同样排序数组，键为单个汉字。
- 加载：仅在「开关为开」时由 `suspend fun warmUp()` 触发，`Dispatchers.Default` + `Mutex` 保证整个进程只尝试一次。
- 未就绪时 `readingOf`/`kanjiReadingOf` 返回 null，不抛异常。

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
输出：app/src/main/assets/reading/words.gz    // 表层\t读音，一行一条，按表层排序
      app/src/main/assets/reading/kanji.gz    // 汉字\t读音
      app/src/main/assets/reading/NOTICE.txt
参数：--max-entries N   超预算时按词频截断（不静默塞爆 APK）
```

处理规则：

1. 只保留表层含汉字的词条（纯假名词对注音无用）
2. 按 (表层, 读音) 去重；同一表层多个读音时保留词频最高的一条
3. 单字回退表由词库自动派生：同一汉字在所有词条中出现时，取按词频加权最高的读音。这样少一个外部依赖、且与词库版本自洽
4. 排序后 gzip 写入

派生单字表的已知代价：「食」会派生成「しょく」而非「た」。可接受，因为送假名场景（食べる）本来就先被词级命中，单字表只兜底生僻字。

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

字号必须用**相对单位**，这是刻意的：

- Compose：`SpanStyle(fontSize = 0.55.em)`，`em` 相对段落 `TextStyle.fontSize`
- TextView：`RelativeSizeSpan(0.55f)`，相对 `textView.textSize`

歌词字号本就是用户可调的（`LyricsPageSettings.fontSizeSp`、`FloatingLyricsSettings.size`），用相对单位即自动跟随，因此不需要新增「注音字号」设置项。

### 6.2 硬约束

**开关关闭、或该行不含日文段时，构建器输出必须与现状逐字节一致**（即等价于纯文本的 `AnnotatedString` / 纯 `String`）。这是防止新功能意外改变任何现有显示的安全网，由自动化测试守住。

唯一允许的文本变化：开启注音且原文自带括号注音时（算法规则 ①），括号被吸收进读音——这是预期改进。

### 6.3 共享构建器（`ui/common/FuriganaText.kt`，新建）

为避免把「开关 + 词典」两个参数逐个透传到 5 个渲染点，把设置侧与数据侧打包成一个值对象，定义在 `util/SubtitleDisplayMode.kt`（紧挨 `DisplaySegment`）：

```kotlin
/** 渲染注音所需的全部输入；无词典可用时 source 为 null，等价于不注音。 */
data class FuriganaSpec(val enabled: Boolean, val source: ReadingSource?)
```

`FuriganaSpec.NONE`（`enabled = false, source = null`）作为默认值，让任何未接入的调用点自动落在「无注音」行为上。

构建器签名：

```kotlin
fun buildFuriganaAnnotatedString(segments: List<DisplaySegment>, furigana: FuriganaSpec): AnnotatedString
fun buildFuriganaSpanned(segments: List<DisplaySegment>, furigana: FuriganaSpec): CharSequence
```

内部对每个 `japanese == true` 的段调用 `annotateLine`，其余段直接取文本。段间以 `\n` 连接（沿用现有双语拼接行为）；中文段永不送进词典。

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

`SubtitleDisplayMode.kt:43` 的 `withDisplayMode(mode)` **签名不变**，只额外填 `displaySegments`：

| 模式 | segments |
|---|---|
| `CHINESE` | 空列表，且沿用现有「原样返回同一个 list」的短路（现有测试 `withDisplayMode_chineseReturnsSameList` 必须继续通过） |
| `JAPANESE` | `[(text=日文, japanese=true)]` |
| `BILINGUAL` | `[(中文, false), (日文, true)]`；「日文为空 / 中文为空 / 中日相同」三条退化分支的段与 `text` 一致 |

`text` 字段的压平行为一个字都不改。因此 `GeneratedSubtitleFileExporter.kt:195`（导出）、`SubtitleHash`、`LyricsLoader.normalizeAndDistinct`、Room 实体全部不感知注音，导出文件保持纯文本。

### 6.5 渲染接入点（共 5 处）

| 文件 | 现状 | 改动 |
|---|---|---|
| `AppleLyricsView.kt:138` | 无注音参数 | 增加 `furigana: FuriganaSpec`，**不给默认值**，强制两个调用点（`LyricsPage.kt:142`、`NowPlayingSurfaceComponents.kt:263`）显式传，杜绝漏改 |
| `AppleLyricsView.kt:904` `LyricLineText` | `text: String`，调用点 L613 | 改 `AnnotatedString`；色散动画那两次 ghost `Text` 自动继承 span 样式 |
| `AppleLyricsView.kt:885` `measureLyricItemHeight` | `AnnotatedString(entry.text)` | 直接 measure 同一个 AnnotatedString，测量与绘制一致 |
| `NowPlayingControls.kt:681` `sourceContent` | `current: String` + `upcoming: List<String>`，L799/L806 用它测高 | `current`/`upcoming` 类型换成 `AnnotatedString`；`NowPlayingMultilineLyrics`（`nowplaying/NowPlayingMultilineLyrics.kt:37`）参数跟着换；不留 plain 副本 |
| `FloatingLyricsView.kt:89` `updateLine(text: String, cue)` | 内部 `textView.text = text`，L89 有 `currentText == text` 去重门 | 参数改 `CharSequence`；**去重门继续比纯文本**（`SpannedString` 无值语义，直接比会导致每帧重复 setText）；`PlaybackService.kt:1290` 改为构建 Spanned |

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

**关键规则：词典未就绪或加载失败时不写 LRU 缓存。** 这让降级链自动愈合——预热完成后下一次渲染自然带上注音，不需要「就绪后通知 UI 刷新」的额外状态机制。代价是最早一两句可能没注音。

行级缓存：由 §3.1 的 `annotateLine` 独占持有（`LinkedHashMap(accessOrder = true)`，容量约 2000，加锁保护），Compose 与 `PlaybackService` 共享同一份缓存实现，不各自造缓存。

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
3. **`data/reading/ReadingDictionaryTest`**：排序数组二分前缀命中、(表层, 读音) 去重、单字读音按词频派生、资产损坏时的降级路径
4. **`ui/common/FuriganaTextTest`**（Robolectric）：`FuriganaSpec(enabled = false, source = 可用词典)` 时输出与纯文本逐字节相等；开启时 span 只覆盖读音区间、`fontSize == 0.55.em`；`RelativeSizeSpan` 起止一致；双语段只在 `japanese == true` 那段产生 span
5. **设置往返**：照 `data/local/datastore/SearchCacheStoreTest` 的 `PreferenceDataStoreFactory.createDataStore` 模式，断默认值 `false`、key 名稳定、set 后读回

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
- `app/src/main/java/com/asmr/player/data/reading/ReadingDictionary.kt`
- `app/src/main/java/com/asmr/player/ui/common/FuriganaText.kt`
- `tools/build_reading_dict.py`
- `app/src/main/assets/reading/{words.gz,kanji.gz,NOTICE.txt}`
- 对应的 5 个测试文件

修改：

- `util/SubtitleParser.kt`（`SubtitleEntry` 加字段）
- `util/SubtitleDisplayMode.kt`（`DisplaySegment`、`FuriganaSpec`、填段逻辑）
- `data/local/datastore/SettingsDataStore.kt`（新 key）
- `ui/settings/SettingsViewModel.kt`、`ui/settings/SettingsScreen.kt`
- `ui/player/LyricsViewModel.kt`、`ui/player/AppleLyricsView.kt`、`ui/player/LyricsPage.kt`
- `ui/player/nowplaying/NowPlayingControls.kt`、`ui/player/nowplaying/NowPlayingSurfaceComponents.kt`、`ui/player/nowplaying/NowPlayingMultilineLyrics.kt`
- `service/FloatingLyricsView.kt`、`service/PlaybackService.kt`
- `README.md`（特性列表 + 词典署名）

## 12. 已知局限

写在这里以免日后被当成 bug 上报：

- 专有名词、人名、特殊读法（例：`行定` → `くんじょう`、台本里的角色名与造词）会读成常规音读
- 没有形态分析，动词/形容词活用形能否读对取决于词库是否收录该形
- 词库未覆盖的汉字直接不注音（不猜）
- 冷启动最初一两句可能没有注音（词典预热未完成）
- 行内小字注音不等于真 ruby：假名在右侧而非上方，长句换行时小字可能与其汉字被换行分开
