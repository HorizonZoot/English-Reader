package io.github.zoot.englishreader.util

import org.junit.Test
import org.junit.Assert.*

/**
 * CacheKeyFactory 单元测试
 */
class CacheKeyFactoryTest {

    @Test
    fun generate_knownInputs_returnsExpectedSha256() {
        val cases = listOf(
            Triple("ASCII", arrayOf("Hello world"), "64ec88ca00b268e5ba1a35678a1b5316d212f4f366b2477232534a8aeca37f3c"),
            Triple("multiple inputs", arrayOf("sentence", "context"), "15fbc57a88245652c486728ca62774cebbe5057bb522425bee4f0d6c66a0b71b"),
            Triple("empty", arrayOf(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            Triple("Unicode", arrayOf("你好世界 🌍"), "530c5734242219d3789dcb70fa375c7e405108a3863d87e9845c702d6dd29e6c"),
            Triple("10 KB", arrayOf("a".repeat(10000)), "27dd1f61b867b6a0f6e9d8a41c43231de52107e53ae424de8f847b821db4b711")
        )
        cases.forEach { (case, inputs, expected) ->
            assertEquals(case, expected, CacheKeyFactory.generate(*inputs))
        }
    }

    @Test
    fun generate_sameInput_returnsSameHash() {
        // 准备
        val input = "The quick brown fox"

        // 执行
        val result1 = CacheKeyFactory.generate(input)
        val result2 = CacheKeyFactory.generate(input)

        // 断言
        assertEquals(result1, result2)
    }

    @Test
    fun generate_differentInputs_returnsDifferentHashes() {
        // 准备
        val input1 = "Hello"
        val input2 = "World"

        // 执行
        val hash1 = CacheKeyFactory.generate(input1)
        val hash2 = CacheKeyFactory.generate(input2)

        // 断言
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun generate_separatorPreventsConcatenationCollision() {
        // 用例：["AB", "C"] 必须与 ["A", "BC"] 得到不同的 key
        // 不加分隔符："ABC" == "ABC"（发生碰撞）
        // 加分隔符: "ABC" != "ABC"（不再碰撞）

        // 准备
        val hash1 = CacheKeyFactory.generate("AB", "C")
        val hash2 = CacheKeyFactory.generate("A", "BC")

        // 执行并断言
        assertNotEquals(hash1, hash2) // 两者必须不同
    }

}
