package io.github.zoot.englishreader.data.importer

/**
 * 导入预算。
 *
 * 三组上限而非一个，因为它们防的是不同的东西：
 *
 * - [MAX_TEXT_SOURCE_BYTES]：**安全**上限。防 OOM 与恶意源文件。不能由字符数反推——
 *   Markdown 源文件含大量将被剥离的标记/URL/代码块，EPUB 是压缩包，源字节与正文字符
 *   没有稳定比例。
 * - [MAX_IMPORT_CHARS]：**产品**上限。正文字符数。
 * - [MAX_IMPORT_PARAGRAPHS] / [MAX_PARAGRAPH_CHARS]：**渲染**上限。同为 10 万字符的
 *   「100 个长段」与「5000 个短段」渲染成本差一个数量级，故字符数单独不足以保护阅读页。
 *
 * ⚠️ **渲染上限当前为未校准的保守值**（见各常量注释）。9.5 已完成 LazyColumn 化
 * （`f79cfcc`，见 ReadingScreen 的 LazyColumn 段落渲染），不再一次性组合全部段落，但**真机基准测试仍未
 * 完成**。须用基准输入（8k/20k/50k/100k 字符 × 100/500/2000 段 × 开关译文）实测首帧
 * 时间、主线程卡顿、内存峰值、滚动流畅度，再据此校准 `MAX_IMPORT_CHARS`。在那之前
 * 这些数字是防御性下限，不是产品承诺。
 */
object ImportBudget {

    /**
     * 源文件字节安全上限（8 MiB）。
     *
     * 依据：纯 UTF-8 英文约 1 字节/字符，8 MiB 足以覆盖任何合理的单篇文章；
     * 同时一个 8 MiB 的 ByteArray 加解码后的 String 峰值内存可控（约 24 MiB 量级），
     * 不会在低端机上直接 OOM。这是**可以现在定**的数字——它只与内存有关，与阅读页渲染无关。
     */
    const val MAX_TEXT_SOURCE_BYTES: Int = 8 * 1024 * 1024

    /**
     * 正文字符上限（40000）。
     *
     * ⚠️ 待真机基准测试校准。取 40000 而非曾经写过的 100000：后者当时无任何证据支撑，
     * 已撤回。40000 约合英文 6000-7000 词，覆盖绝大多数文章与短篇。
     * **LazyColumn 本身不构成提高此上限的证据**——它只消除了「一次性组合全部段落」这一项
     * 成本，而导入、分句、对齐与数据库读写仍作用于全文。要提高必须有实测数据。
     */
    const val MAX_IMPORT_CHARS: Int = 40_000

    /**
     * 段落数上限（1200）。
     *
     * ⚠️ 待真机基准测试校准。每段在阅读页都要独立跑 SentenceSplitter、
     * extractAccessibilityWords、构造 accessibilityActions 与 AnnotatedString、
     * 并由 BasicText 测量取 TextLayoutResult；ParagraphAligner 还会为 sentenceOffset
     * 再分句一遍，即每段至少被分句两次。段落数是比字符数更贴近渲染成本的指标。
     */
    const val MAX_IMPORT_PARAGRAPHS: Int = 1200

    /**
     * 单段字符上限（8000）。
     *
     * ⚠️ 待真机基准测试校准。防「整篇没有空行」的退化输入——那会变成单个巨型段落，
     * 一次 AnnotatedString 构造与一次文本测量都作用在全文上，且 Lazy 化也救不了
     * （LazyColumn 的最小组合单位就是一个段落）。
     */
    const val MAX_PARAGRAPH_CHARS: Int = 8_000

    /** 文章标题字符上限。防恶意 EPUB metadata 塞超长 dc:title 撑爆列表页。 */
    const val MAX_TITLE_CHARS: Int = 120

    /**
     * 全文 AI 解释的字符上限（8000）。
     *
     * 超过时**不拦截导入、不静默截断**，只在导入成功提示里附带说明，
     * 用户仍可正常阅读与逐句解释。
     */
    const val MAX_FULL_EXPLANATION_CHARS: Int = 8_000

    // ---- EPUB 独立上限 ----
    // 不套用 MAX_TEXT_SOURCE_BYTES：EPUB 里的图片会让压缩包很大而正文很短，
    // 用同一个字节上限会误拒合法的短篇。以下五项合起来是 ZIP bomb 防线。

    /** EPUB 压缩包字节上限（32 MiB）。含图片资源，故比纯文本上限宽松。 */
    const val MAX_EPUB_ARCHIVE_BYTES: Int = 32 * 1024 * 1024

    /** ZIP entry 数量上限。防「百万个小文件」型 bomb。 */
    const val MAX_ZIP_ENTRIES: Int = 2_000

    /**
     * 单个 XML/XHTML entry 解压后字节上限（4 MiB）。
     *
     * 必须在**解压过程中**硬性计数，不能信 ZipEntry.getSize()（可被伪造）。
     */
    const val MAX_XML_ENTRY_BYTES: Int = 4 * 1024 * 1024

    /**
     * 单本 EPUB 解压总字节上限（24 MiB）。
     *
     * **单 entry 上限不是累计预算**：500 个各自 4 MiB 的 entry 都不超单项限制，
     * 但累计解压约 2 GiB——正文字符数仍可能低于 [MAX_IMPORT_CHARS]（内容多是注释、
     * 标签或被跳过的 script/style），于是 archive/entry/spine/字符四道限制全部放行，
     * 而设备已经付出了 2 GiB 的解压、分配与 GC 代价。
     *
     * 故 container.xml、OPF、encryption.xml 与所有 spine XHTML 必须从**同一个**
     * 计数器扣除。24 MiB 略低于压缩包上限——正文类资源解压后总量超过整个包的体积
     * 就已经不正常了。
     */
    const val MAX_EPUB_TOTAL_INFLATED_BYTES: Int = 24 * 1024 * 1024

    /**
     * spine 条目数上限（章节数）。
     *
     * 注意只统计**成功解析且 linear != "no"** 的条目：大量 `linear="no"` 的 itemref
     * 不计入本限额，它们的解压成本由 [MAX_EPUB_TOTAL_INFLATED_BYTES] 兜底。
     */
    const val MAX_SPINE_ITEMS: Int = 500

    // ---- 整本书上限 ----
    // 与上面的单页/单档上限分开：一本书是「多个可独立阅读的章节」，
    // 每章各自受 MAX_CHAPTER_CHARS 约束，全书另有独立的总量上限。
    // 把两者混成一个数字必然出错——实测一本公版长篇的正文约 74 万字符，
    // 是 MAX_IMPORT_CHARS 的 18 倍以上，但其中任何**单章**都远低于该值。
    //
    // ⚠️ 「单章远低于全书上限」这句话只对**切分够细**的书成立。32 本语料实测：真实章节有
    // 8.7% 越过 40,000，而一本书要求每章都过，所以只有 34% 的书能导入。Standard Ebooks
    // 一章一文件仍有 5/10 被章节闸门拒——那些超限资源就是单个真实章节，没有东西可切。
    // 详见 ADR-013 与 `CorpusImportSurveyTest`。

    /**
     * 单章字符上限（40000）。
     *
     * ⚠️ 待真机基准测试校准，与 [MAX_IMPORT_CHARS] **各自独立**。
     *
     * ## 为什么不再是 `= MAX_IMPORT_CHARS`
     *
     * 渲染成本上两者同构——一个章节就是一次
     * [io.github.zoot.englishreader.ui.screen.ReadingScreen] 渲染，与单篇导入文章没有区别。
     * 但**约束来源不同**，所以它们是两个可以分别校准的产品决定：
     *
     * - [MAX_IMPORT_CHARS] 管的是**用户自己挑的文件**。超限时用户可以换一篇更短的，
     *   所以保守取值代价很小。
     * - 本项管的是**出版方切好的章节**。用户对章节边界无从干预，超限时唯一可行动作是
     *   换一个版本的书。
     *
     * 写成别名时，这个差异被抹掉了：32 本公版语料实测只有 11 本（34%）能导入，而抬高本项
     * 需要连带抬高单篇上限，后者被 `AGENTS.md` 的不变量锁住（真机长文章基准未完成前不得
     * 超过 40,000）。于是「章节覆盖率」被一个与它无关的约束挡着。解耦后两者可以分别校准，
     * 单篇路径（已冻结）完全不受影响。
     *
     * 解耦本身不改变任何行为——两者当前都是 40,000。见 ADR-013。
     */
    const val MAX_CHAPTER_CHARS: Int = 40_000

    /**
     * 单本书章节数上限。
     *
     * 与 [MAX_SPINE_ITEMS] 取同值：章节由 linear reading-order item 派生，
     * 两个数字若不一致，先触发的那个会让另一个永远不可达，等于埋一个死限制。
     */
    const val MAX_BOOK_CHAPTERS: Int = MAX_SPINE_ITEMS

    /**
     * 单本书正文总字符上限（400 万）。
     *
     * **这是保守起点，不是实测门槛**。它的作用是拒绝异常巨大的输入，
     * 而不是声称设备能流畅处理 400 万字符。真实上限必须由真机基准
     * （导入耗时、峰值 heap、章节切换、进程恢复）校准后才能调整——
     * 在拿到设备数据前调高此值等于用用户设备做实验。
     *
     * **实际上很少由本项先触发**：本项按字符数判定，需要先解析出全部章节；
     * 而 [MAX_EPUB_TOTAL_INFLATED_BYTES]（24 MiB）在 preflight 阶段就按解压字节
     * 拦截。400 万字符的正文在 XHTML 标记下解压量通常已超 24 MiB，故多数超大书
     * 会先报 [ImportFailure.BookArchiveTooLarge]。两者都是"书太大"，用户看到的
     * 提示都指向"换一本更小的"，不会像早期实现那样误报"文件损坏"。
     */
    const val MAX_BOOK_TEXT_CHARS: Int = 4_000_000
}
