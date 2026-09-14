package io.github.zoot.englishreader.util

import io.github.zoot.englishreader.data.entity.ArticleEntity

/**
 * 测试数据生成器
 */
object TestDataGenerator {

    /**
     * 样本数据版本号。每次修改 [getSampleArticles] 的内容（新增文章、改译文等）
     * 都应 +1，MainActivity 检测到版本落后会重新灌入样本数据。
     */
    const val SAMPLE_DATA_VERSION = 2

    /**
     * 内置样本文章使用的 source 白名单。刷新样本时仅删除这些 source 的文章，
     * 用户导入（file/paste）或任何其他来源的文章都不受影响。
     * 新增样本文章时若用了新的 source，必须同步登记到此处。
     */
    val SAMPLE_SOURCES = listOf("Sample Article", "Environmental News", "Psychology Today")

    fun getSampleArticles(): List<ArticleEntity> = listOf(
        ArticleEntity(
            title = "The Benefits of Reading",
            content = """
                Reading is one of the most beneficial activities for our minds. It expands our vocabulary, improves our focus, and enhances our imagination. When we read regularly, we develop better writing skills and critical thinking abilities.

                Studies have shown that reading can reduce stress levels by up to 68%. It's more effective than listening to music or taking a walk. Just six minutes of reading can slow down the heart rate and ease tension in the muscles.

                Moreover, reading before bed can help you sleep better. It creates a ritual that tells your brain it's time to wind down. Many successful people, including Bill Gates and Warren Buffett, credit their success partly to their reading habits.
            """.trimIndent(),
            translation = """
                阅读是对我们的大脑最有益的活动之一。它扩展我们的词汇量，提高我们的专注力，并增强我们的想象力。当我们定期阅读时，我们会培养更好的写作技巧和批判性思维能力。

                研究表明，阅读可以将压力水平降低多达68%。它比听音乐或散步更有效。仅仅六分钟的阅读就能减慢心率并缓解肌肉紧张。

                此外，睡前阅读可以帮助你睡得更好。它创造了一个仪式，告诉你的大脑是时候放松了。许多成功人士，包括比尔·盖茨和沃伦·巴菲特，将他们的成功部分归功于他们的阅读习惯。
            """.trimIndent(),
            source = "Sample Article"
        ),

        ArticleEntity(
            title = "Climate Change and Our Future",
            content = """
                Climate change is one of the most pressing issues facing humanity today. Global temperatures have risen by approximately 1.1 degrees Celsius since pre-industrial times. This seemingly small change has already led to more frequent extreme weather events.

                Scientists warn that if we don't take immediate action, the consequences could be catastrophic. Rising sea levels threaten coastal cities, while changing weather patterns affect agriculture worldwide. The good news is that we still have time to make a difference.

                Individual actions matter. Reducing our carbon footprint, supporting renewable energy, and advocating for policy changes are all important steps. Every choice we make today shapes the world our children will inherit tomorrow.
            """.trimIndent(),
            translation = """
                气候变化是当今人类面临的最紧迫问题之一。自工业化前时代以来，全球气温已经上升了约1.1摄氏度。这个看似很小的变化已经导致了更频繁的极端天气事件。

                科学家警告说，如果我们不立即采取行动，后果可能是灾难性的。海平面上升威胁着沿海城市，而不断变化的天气模式影响着全球农业。好消息是我们仍然有时间做出改变。

                个人行动很重要。减少我们的碳足迹，支持可再生能源，并倡导政策变革都是重要的步骤。我们今天做出的每一个选择都塑造着我们的孩子明天将继承的世界。
            """.trimIndent(),
            source = "Environmental News"
        ),

        ArticleEntity(
            title = "The Power of Habits",
            content = """
                Habits shape our lives more than we realize. Research suggests that about 40% of our daily actions are driven by habits rather than conscious decisions. Understanding how habits work can help us build better ones and break bad ones.

                Every habit follows a simple loop: cue, routine, and reward. The cue triggers the behavior, the routine is the behavior itself, and the reward is what makes your brain remember the habit for the future. By identifying these components, we can modify our habits effectively.

                Small changes can lead to remarkable results over time. If you improve by just 1% each day, you'll be 37 times better after one year. The key is consistency, not intensity. Start small, stay consistent, and watch your life transform.
            """.trimIndent(),
            translation = """
                习惯塑造我们的生活远超我们的认知。研究表明，我们大约40%的日常行为是由习惯驱动的，而不是有意识的决定。了解习惯是如何运作的可以帮助我们建立更好的习惯并打破坏习惯。

                每个习惯都遵循一个简单的循环：提示、惯例和奖励。提示触发行为，惯例是行为本身，奖励是让你的大脑记住这个习惯以备将来使用的东西。通过识别这些组成部分，我们可以有效地修改我们的习惯。

                随着时间的推移，小的变化可以带来显著的结果。如果你每天进步1%，一年后你会好37倍。关键是一致性，而不是强度。从小处着手，保持一致，看着你的生活转变。
            """.trimIndent(),
            source = "Psychology Today"
        )
    )
}