package io.github.zoot.englishreader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WordLemmatizer 单元测试
 *
 * 验证候选原形产出：
 * - 规则后缀剥离（复数/三单、过去式、现在分词、比较级、副词）
 * - 双写辅音还原（stopped→stop、running→run）
 * - 不规则词表命中且排在首位
 * - 边界：过短词、原词不出现在候选中
 *
 * 注意：candidates 会**过度产出**（规则法特性），测试只断言"正确原形在候选中且顺序合理"，
 * 由上层以词库实际命中纠偏，故不苛求候选列表精确等于某个集合。
 */
class WordLemmatizerTest {

    @Test
    fun candidates_commonInflectionRules_includeExpectedBase() {
        val cases = listOf(
            "regular plural" to ("cats" to "cat"),
            "-es plural" to ("boxes" to "box"),
            "-ies plural" to ("studies" to "study"),
            "past tense" to ("walked" to "walk"),
            "past tense with e" to ("lived" to "live"),
            "-ied past tense" to ("studied" to "study"),
            "doubled consonant past" to ("stopped" to "stop"),
            "present participle" to ("reading" to "read"),
            "present participle with e" to ("making" to "make"),
            "doubled consonant participle" to ("running" to "run"),
            "comparative" to ("faster" to "fast"),
            "superlative" to ("fastest" to "fast"),
            "-iest superlative" to ("happiest" to "happy"),
            "-ly adverb" to ("quickly" to "quick"),
            // -es 分支曾漏掉去 s 候选，导致 likes / uses / makes 离线 miss。
            "third person likes" to ("likes" to "like"),
            "third person uses" to ("uses" to "use"),
            "third person makes" to ("makes" to "make"),
            "irregular verb" to ("went" to "go"),
            "irregular comparative" to ("better" to "good"),
            // -ies 分支也需去 s，避免 die / lie / tie 漏查后回退网络。
            "-ies dies" to ("dies" to "die"),
            "-ies lies" to ("lies" to "lie"),
            "-ies ties" to ("ties" to "tie"),
            // 比较级 / 最高级也需要双写辅音还原。
            "doubled comparative big" to ("bigger" to "big"),
            "doubled superlative big" to ("biggest" to "big"),
            "doubled comparative hot" to ("hotter" to "hot"),
            "doubled comparative thin" to ("thinner" to "thin"),
            "doubled comparative sad" to ("sadder" to "sad"),
            "comparative with e" to ("larger" to "large"),
            // -ying → -ie 与 -ly → -le 不能漏掉末尾字母。
            "-ying lying" to ("lying" to "lie"),
            "-ying tying" to ("tying" to "tie"),
            "-ly humbly" to ("humbly" to "humble"),
            "-ly nobly" to ("nobly" to "noble"),
            "-ly subtly" to ("subtly" to "subtle"),
            "-ly doubly" to ("doubly" to "double"),
            "-ily adverb" to ("happily" to "happy")
        )

        cases.forEach { (description, inputAndExpected) ->
            val (input, expected) = inputAndExpected
            assertTrue(
                "$description: $input should include $expected",
                WordLemmatizer.candidates(input).contains(expected)
            )
        }
    }

    @Test
    fun candidates_esThirdPerson_dropSBeforeDropEs() {
        // 顺序关键：去 s 候选（use/note/toe）必须排在去 es 候选（us/not/to）之前。
        // uses/notes/toes 的 dropLast(2) 恰好是词库里的高频真词（us/not/to），
        // 而 DictionaryRepository "首个命中即返回"——若去 es 在前会优先命中错误原形。
        // 仅断言 contains 挡不住此回归，必须断言相对顺序。
        listOf("uses" to "use", "notes" to "note", "toes" to "toe").forEach { (word, base) ->
            val candidates = WordLemmatizer.candidates(word)
            val dropS = candidates.indexOf(base)          // use / note / toe
            val dropEs = candidates.indexOf(word.dropLast(2)) // us / not / to
            assertTrue("$word: 应含去 s 候选 $base", dropS >= 0)
            assertTrue(
                "$word: 去 s 候选 $base 必须排在去 es 候选 ${word.dropLast(2)} 之前",
                dropEs < 0 || dropS < dropEs
            )
        }
    }

    @Test
    fun candidates_edAndIng_restoreEBeforeBareStem() {
        // 与 -es 同一根因：hoped/cared/noted/taped 的裸 stem（hop/car/not/tap）是词库里的
        // 高频真词，会截获正确原形（hope/care/note/tape）。故补 e 候选必须排在裸 stem 之前。
        // 反向无回归：walked/reading 的补 e（walke/reade）不是真词，绝不会误命中。
        listOf(
            "hoped" to "hope", "cared" to "care", "noted" to "note", "taped" to "tape",
            "hoping" to "hope", "caring" to "care", "taping" to "tape"
        ).forEach { (word, base) ->
            val candidates = WordLemmatizer.candidates(word)
            val withE = candidates.indexOf(base)
            val bareStem = candidates.indexOf(base.dropLast(1)) // hop / car / not / tap
            assertTrue("$word: 应含补 e 候选 $base", withE >= 0)
            assertTrue(
                "$word: 补 e 候选 $base 必须排在裸 stem ${base.dropLast(1)} 之前",
                bareStem < 0 || withE < bareStem
            )
        }
    }

    @Test
    fun candidates_irregular_mapsToBaseFirst() {
        // 不规则词：children→child，且排在候选首位
        val candidates = WordLemmatizer.candidates("children")
        assertTrue(candidates.contains("child"))
        assertEquals("child", candidates.first())
    }

    @Test
    fun candidates_livesHasBothIrregularAndRuleCandidates() {
        // lives 既是 life 的复数（不规则表），又可能是 live 的三单（规则法）
        // 词库以实际命中纠偏，故两者都应在候选中，life（不规则）在前
        val candidates = WordLemmatizer.candidates("lives")
        assertEquals("life", candidates.first())
        assertTrue(candidates.contains("live"))
    }

    @Test
    fun ambiguousBases_knownForms_normalizesInputAndKeepsVerbFirst() {
        // lives/leaves 的两种词性解释都合法且两个原形都在词库，无从判别，
        // 故并列展示；动词三单更常见，排在前面作主释义。
        listOf(
            "lives" to listOf("live", "life"),
            "leaves" to listOf("leave", "leaf"),
            "  LIVES " to listOf("live", "life")
        ).forEach { (input, expected) ->
            assertEquals(input, expected, WordLemmatizer.ambiguousBases(input))
        }
    }

    @Test
    fun ambiguousBases_nonAmbiguousWord_returnsEmpty() {
        // 仅 lives/leaves 走并列展示；knives/halves 的规则候选（knive/halve）不在词库、
        // 不会误命中，boxes/uses 等亦无歧义，均应返回空走常规"首个命中即返回"。
        listOf("knives", "halves", "boxes", "uses", "children", "cats").forEach {
            assertTrue("$it 不应被视为歧义词", WordLemmatizer.ambiguousBases(it).isEmpty())
        }
    }

    @Test
    fun candidates_newRulesAppendAfterExisting_soFirstHitWinsIsPreserved() {
        // 上层是"首个命中即返回"，新增候选必须排在原有候选之后，否则会截获正确原形。
        // 这里锁住相对顺序：常规规则的产出仍在新增规则之前。
        val studies = WordLemmatizer.candidates("studies")
        assertTrue(studies.containsAll(listOf("study", "studie")))
        assertTrue(studies.indexOf("study") < studies.indexOf("studie"))
        val quickly = WordLemmatizer.candidates("quickly")
        assertTrue(quickly.containsAll(listOf("quick", "quickle")))
        assertTrue(quickly.indexOf("quick") < quickly.indexOf("quickle"))
        val faster = WordLemmatizer.candidates("faster")
        assertTrue(faster.containsAll(listOf("fast", "faste")))
        assertTrue(faster.indexOf("fast") < faster.indexOf("faste"))
    }

    @Test
    fun candidates_tooShort_returnsEmpty() {
        assertTrue(WordLemmatizer.candidates("a").isEmpty())
    }

    @Test
    fun candidates_excludesOriginalWord() {
        // 原词本身不应出现在候选中（原词由调用方先查）
        assertTrue(WordLemmatizer.candidates("cats").none { it == "cats" })
    }
}
