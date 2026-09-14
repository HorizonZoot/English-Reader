package io.github.zoot.englishreader.util

/**
 * 词形还原器（纯规则法，无 Android 依赖，可 JVM 单测）。
 *
 * 背景：离线词典（ECDICT 精简为 4 列 TSV）主键 `word` 只存**原形**，
 * 不含 ECDICT 的 exchange 变形字段，故无法查表精准还原。长按变形词
 * （lives / suggested / children）会在词典 miss。本类用规则后缀剥离 +
 * 内置不规则词表，产出**候选原形列表**，交由上层逐个重查词典，命中即用。
 *
 * 设计要点：
 * - [candidates] 返回**去重、按优先级排序**的候选，不含原词本身（原词由调用方先查）。
 * - 规则法必然过度产出（如 "es→∅" 与 "s→∅" 都会为 boxes 产候选），
 *   靠"上层拿候选逐个查词典、以词库实际命中为准"来纠偏——词典里存在的才算数。
 * - 不规则词命中时，其映射值**排在候选首位**，优先于规则猜测。
 */
object WordLemmatizer {

    /**
     * 高频不规则变形 → 原形。规则法无力覆盖，硬编码兜底。
     * 覆盖常见不规则动词（过去式/过去分词）、不规则复数、不规则比较级。
     */
    private val IRREGULAR: Map<String, String> = mapOf(
        // be 动词
        "am" to "be", "is" to "be", "are" to "be", "was" to "be", "were" to "be", "been" to "be", "being" to "be",
        // 高频不规则动词
        "went" to "go", "gone" to "go", "goes" to "go",
        "did" to "do", "done" to "do", "does" to "do",
        "had" to "have", "has" to "have",
        "made" to "make",
        "said" to "say",
        "got" to "get", "gotten" to "get",
        "came" to "come",
        "took" to "take", "taken" to "take",
        "saw" to "see", "seen" to "see",
        "knew" to "know", "known" to "know",
        "gave" to "give", "given" to "give",
        "found" to "find",
        "thought" to "think",
        "told" to "tell",
        "became" to "become",
        "left" to "leave",
        "felt" to "feel",
        "brought" to "bring",
        "began" to "begin", "begun" to "begin",
        "kept" to "keep",
        "held" to "hold",
        "wrote" to "write", "written" to "write",
        "stood" to "stand",
        "heard" to "hear",
        "let" to "let",
        "meant" to "mean",
        "set" to "set",
        "met" to "meet",
        "ran" to "run",
        "paid" to "pay",
        "sat" to "sit",
        "spoke" to "speak", "spoken" to "speak",
        "lay" to "lie", "lain" to "lie",
        "led" to "lead",
        "read" to "read",
        "grew" to "grow", "grown" to "grow",
        "lost" to "lose",
        "fell" to "fall", "fallen" to "fall",
        "sent" to "send",
        "built" to "build",
        "understood" to "understand",
        "drew" to "draw", "drawn" to "draw",
        "broke" to "break", "broken" to "break",
        "spent" to "spend",
        "cut" to "cut",
        "rose" to "rise", "risen" to "rise",
        "drove" to "drive", "driven" to "drive",
        "bought" to "buy",
        "wore" to "wear", "worn" to "wear",
        "chose" to "choose", "chosen" to "choose",
        "ate" to "eat", "eaten" to "eat",
        "beat" to "beat",
        "won" to "win",
        "forgot" to "forget", "forgotten" to "forget",
        "threw" to "throw", "thrown" to "throw",
        "flew" to "fly", "flown" to "fly",
        "fought" to "fight",
        "taught" to "teach",
        "caught" to "catch",
        "sold" to "sell",
        "put" to "put",
        // 不规则复数
        "children" to "child",
        "men" to "man", "women" to "woman",
        "feet" to "foot", "teeth" to "tooth",
        "mice" to "mouse", "geese" to "goose",
        "people" to "person",
        "lives" to "life", // 注意：lives 也可能是 live 的三单，规则法会另产 live 候选
        "leaves" to "leaf", "wolves" to "wolf", "knives" to "knife", "wives" to "wife",
        "halves" to "half", "shelves" to "shelf",
        // 不规则比较级 / 最高级
        "better" to "good", "best" to "good",
        "worse" to "bad", "worst" to "bad",
        "more" to "much", "most" to "much",
        "further" to "far", "farther" to "far", "furthest" to "far", "farthest" to "far"
    )

    /**
     * 歧义变形词 → 两个都合法的原形（按优先级排序，主原形在前）。
     *
     * 这些词的两种解释在语法上都成立，且**两个原形都在词库里**，纯规则法无从判别词性：
     * - lives：live 的三单（"He lives here"）/ life 的复数（"Save lives"）
     * - leaves：leave 的三单（"She leaves early"）/ leaf 的复数（"Fallen leaves"）
     *
     * 若沿用"首个命中即返回"，无论押哪一边都会在另一类句子里给出错误释义，
     * 故上层对这些词**同时展示两个原形的释义**，由用户按上下文自行判断。
     *
     * 仅收录"IRREGULAR 映射值与规则候选皆在词库"的词：halves/knives/wives 等虽同属
     * -ves→-f，但其规则候选（halve/knive/wive）不在词库，不会误命中，无需并列展示。
     */
    private val AMBIGUOUS: Map<String, List<String>> = mapOf(
        "lives" to listOf("live", "life"),
        "leaves" to listOf("leave", "leaf")
    )

    /**
     * 若 [word] 是需并列展示多个原形的歧义变形词，返回其原形列表（主原形在前）；否则返回空。
     * 上层据此改用"收集所有命中"而非"首个命中即返回"。
     */
    fun ambiguousBases(word: String): List<String> =
        AMBIGUOUS[word.trim().lowercase()] ?: emptyList()

    /**
     * 返回 [word] 的候选原形列表（去重、按优先级排序，不含原词本身）。
     * 上层应先用原词查词典，miss 后再遍历本列表逐个查，第一个命中的即为结果。
     */
    fun candidates(word: String): List<String> {
        val w = word.trim().lowercase()
        if (w.length < 2) return emptyList()

        // 用 LinkedHashSet 保序去重；不规则映射优先入列（排在最前）。
        val result = LinkedHashSet<String>()

        IRREGULAR[w]?.let { result.add(it) }

        result.addAll(pluralAndThirdPerson(w))
        result.addAll(pastTense(w))
        result.addAll(presentParticiple(w))
        result.addAll(comparativeSuperlative(w))
        result.addAll(adverb(w))

        result.remove(w) // 去掉与原词相同的候选
        return result.toList()
    }

    /**
     * 复数 / 动词三单。
     *
     * ⚠️ 下面是 `when`（**单选**，不是叠加）：按 -ies / -es / -s 顺序取**首个**匹配的分支。
     * 因此每个分支都必须自己补齐所有需要的候选，不能指望后续分支兜底。
     *
     * - `-ies` → `-y` 与去 s 两个候选（studies→study、dies→die）
     * - `-es`  → 去 s 与去 es 两个候选（uses→use、boxes→box）
     * - `-s`   → `∅`（cats→cat）
     */
    private fun pluralAndThirdPerson(w: String): List<String> {
        val out = mutableListOf<String>()
        when {
            w.endsWith("ies") && w.length > 3 -> {
                out.add(w.dropLast(3) + "y")  // studies→study
                // when 互斥，-es 分支不会再执行，故此处补去 s 候选，否则 dies/lies/ties
                // 只剩 dy/ly/ty，而 die/lie/tie 都在词库里、原词都不在，会全 miss 走在线查询。
                // 追加在末尾：studies 的 dropLast(1)="studie" 不是真词，不会截获 study。
                out.add(w.dropLast(1))        // dies→die、lies→lie、ties→tie
            }
            w.endsWith("es") && w.length > 2 -> {
                // when 互斥：以 e 结尾的三单/复数（likes/makes/uses/notes/toes）不会再走
                // 下面的 -s→∅ 分支，故此处补去 s 候选（等价 -es→-e）。
                // 顺序关键：去 s 候选必须排在去 es 之前——uses 的 dropLast(2)="us"、notes 的
                // "not"、toes 的 "to" 恰好都是词库里的高频真词，若排在前面会被"首个命中即返回"
                // 优先命中，弹出错误原形。而 boxes/wishes 的 dropLast(1)（"boxe"/"wishe"）不是
                // 真词、绝不会误命中，故去 s 优先对 boxes 这类无害，strictly better。
                out.add(w.dropLast(1))        // uses→use、notes→note、toes→toe、likes→like
                out.add(w.dropLast(2))        // boxes→box、wishes→wish（兜底）
            }
            w.endsWith("s") && !w.endsWith("ss") && w.length > 1 -> out.add(w.dropLast(1)) // cats→cat
        }
        return out
    }

    /** 过去式 / 过去分词：-ied→-y、-ed→-e（优先）、-ed→∅、双写辅音还原。此处是独立 `if`，可叠加。 */
    private fun pastTense(w: String): List<String> {
        val out = mutableListOf<String>()
        if (w.endsWith("ied") && w.length > 3) {
            out.add(w.dropLast(3) + "y") // studied→study
        }
        if (w.endsWith("ed") && w.length > 2) {
            val stem = w.dropLast(2)
            // 顺序同 -es 分支：补 e 候选优先于裸 stem。hoped/cared/noted/taped 的裸 stem
            // （hop/car/not/tap）恰好都是词库高频真词，排前面会被"首个命中即返回"截获；
            // 而 walked/reached 的补 e（walke/reache）不是真词，绝不误命中，故换序无回归。
            out.add(stem + "e")    // hoped→hope、cared→care、lived→live
            out.add(stem)          // walked→walk（兜底）
            // 双写辅音：stopped→stop（末两字符相同且为辅音）
            if (stem.length >= 2 && stem[stem.length - 1] == stem[stem.length - 2] && isConsonant(stem.last())) {
                out.add(stem.dropLast(1))
            }
        }
        return out
    }

    /** 现在分词：-ing→+e（优先）、-ing→∅、双写辅音还原、-ying→-ie。此处是独立 `if`，可叠加。 */
    private fun presentParticiple(w: String): List<String> {
        val out = mutableListOf<String>()
        if (w.endsWith("ing") && w.length > 3) {
            val stem = w.dropLast(3)
            // 顺序同 -ed 分支：hoping/caring/taping 的裸 stem（hop/car/tap）是词库真词，
            // 会截获正确原形；reading 的补 e（reade）不是真词，故换序无回归。
            out.add(stem + "e")  // hoping→hope、making→make
            out.add(stem)        // reading→read（兜底）
            // 双写辅音：running→run
            if (stem.length >= 2 && stem[stem.length - 1] == stem[stem.length - 2] && isConsonant(stem.last())) {
                out.add(stem.dropLast(1))
            }
            // -ying→-ie：lying→lie、tying→tie。前面的候选是 ly/lye，都不是词库真词，
            // 故追加在末尾即可命中，且不影响 trying→try（-y 由 IRREGULAR 之外的规则覆盖）。
            if (w.endsWith("ying") && w.length > 4) {
                out.add(w.dropLast(4) + "ie")
            }
        }
        return out
    }

    /**
     * 比较级 / 最高级。
     *
     * ⚠️ `when` **单选**，且分支顺序即代码顺序：`-iest` → `-est` → `-ier` → `-er`。
     * 顺序是语义关键——`-iest` 必须先于 `-est`，否则 `happiest` 会走错分支。调整顺序前先看测试。
     *
     * - `-iest` → `-y`（happiest→happy）
     * - `-est`  → `∅`、`-e`、双写辅音还原（fastest→fast、largest→large、biggest→big）
     * - `-ier`  → `-y`（happier→happy）
     * - `-er`   → `∅`、`-e`、双写辅音还原（faster→fast、larger→large、bigger→big）
     */
    private fun comparativeSuperlative(w: String): List<String> {
        val out = mutableListOf<String>()
        when {
            w.endsWith("iest") && w.length > 4 -> out.add(w.dropLast(4) + "y") // happiest→happy
            w.endsWith("est") && w.length > 3 -> {
                val stem = w.dropLast(3)
                out.add(stem)                 // fastest→fast
                out.add(stem + "e")           // largest→large
                // 双写辅音还原，同 pastTense/presentParticiple：biggest→big、hottest→hot
                if (stem.length >= 2 && stem[stem.length - 1] == stem[stem.length - 2] && isConsonant(stem.last())) {
                    out.add(stem.dropLast(1))
                }
            }
            w.endsWith("ier") && w.length > 3 -> out.add(w.dropLast(3) + "y")  // happier→happy
            w.endsWith("er") && w.length > 2 -> {
                val stem = w.dropLast(2)
                out.add(stem)                 // faster→fast
                out.add(stem + "e")           // larger→large
                // 双写辅音还原：bigger→big、hotter→hot、thinner→thin、beginner→begin
                if (stem.length >= 2 && stem[stem.length - 1] == stem[stem.length - 2] && isConsonant(stem.last())) {
                    out.add(stem.dropLast(1))
                }
            }
        }
        return out
    }

    /** 副词：-ily→-y、-ly→∅ 与 -le（humbly→humble）。`when` 单选。 */
    private fun adverb(w: String): List<String> {
        val out = mutableListOf<String>()
        when {
            w.endsWith("ily") && w.length > 3 -> out.add(w.dropLast(3) + "y") // happily→happy
            w.endsWith("ly") && w.length > 2 -> {
                out.add(w.dropLast(2))        // quickly→quick
                // -ly→-le：humbly→humble、nobly→noble、subtly→subtle、doubly→double。
                // 追加在末尾：quickly 的 "quickle" 不是真词，不会截获 quick。
                out.add(w.dropLast(2) + "le")
            }
        }
        return out
    }

    private fun isConsonant(c: Char): Boolean = c.isLetter() && c !in "aeiou"
}
