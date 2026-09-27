package io.github.zoot.englishreader.ui.screen.vocabulary

import io.github.zoot.englishreader.data.entity.VocabularyEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * 按时间分组器
 *
 * 分组规则：
 * - 今天
 * - 昨天
 * - 本周
 * - 更早（按日期）
 */
class TimeGrouper(
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val timeZoneProvider: () -> TimeZone = TimeZone::getDefault
) {

    private data class Snapshot(
        val words: List<VocabularyEntity>,
        val todayStart: Long,
        val yesterdayStart: Long,
        val thisWeekStart: Long,
        val timeZone: TimeZone,
        val groups: List<VocabularyGroup>
    )

    // 一次发布完整快照；展开状态不参与分桶，日期与时区变化仍会使缓存失效。
    @Volatile private var snapshot: Snapshot? = null

    fun group(
        words: List<VocabularyEntity>,
        expandedGroups: Set<VocabularyGroupId>
    ): List<VocabularyGroup> {
        if (words.isEmpty()) {
            snapshot = null
            return emptyList()
        }

        val now = nowProvider()
        val timeZone = timeZoneProvider()
        val todayStart = startOfDay(now, timeZone)
        val yesterdayStart = (todayStart.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, -1)
        }
        val thisWeekStart = startOfWeek(now, timeZone)
        val cached = snapshot
        if (cached != null && (cached.words === words || cached.words == words) &&
            cached.todayStart == todayStart.timeInMillis &&
            cached.yesterdayStart == yesterdayStart.timeInMillis &&
            cached.thisWeekStart == thisWeekStart.timeInMillis && cached.timeZone == timeZone
        ) {
            return withExpansion(cached.groups, expandedGroups)
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            this.timeZone = timeZone
        }
        val buckets = linkedMapOf<VocabularyGroupId, MutableList<VocabularyEntity>>()

        words.forEach { word ->
            val groupId = when {
                word.createdAt >= todayStart.timeInMillis -> VocabularyGroupId.Today
                word.createdAt >= yesterdayStart.timeInMillis -> VocabularyGroupId.Yesterday
                word.createdAt >= thisWeekStart.timeInMillis -> VocabularyGroupId.ThisWeek
                else -> VocabularyGroupId.OlderDate(dateFormat.format(Date(word.createdAt)))
            }
            buckets.getOrPut(groupId) { mutableListOf() } += word
        }

        val orderedIds = buildList {
            listOf(
                VocabularyGroupId.Today,
                VocabularyGroupId.Yesterday,
                VocabularyGroupId.ThisWeek
            ).filterTo(this) { it in buckets }
            buckets.keys
                .filterIsInstance<VocabularyGroupId.OlderDate>()
                .sortedByDescending { it.value }
                .forEach(::add)
        }

        val groups = orderedIds.map { id ->
            VocabularyGroup(id = id, words = buckets.getValue(id))
        }
        snapshot = Snapshot(
            words, todayStart.timeInMillis, yesterdayStart.timeInMillis, thisWeekStart.timeInMillis,
            timeZone.clone() as TimeZone, groups
        )
        return withExpansion(groups, expandedGroups)
    }

    private fun withExpansion(groups: List<VocabularyGroup>, expanded: Set<VocabularyGroupId>) =
        groups.map { group -> if (group.id in expanded) group.copy(isExpanded = true) else group }

    private fun startOfDay(timestamp: Long, timeZone: TimeZone): Calendar {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar
    }

    private fun startOfWeek(timestamp: Long, timeZone: TimeZone): Calendar {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = timestamp

        // 获取当前是星期几（1=周日, 2=周一, ..., 7=周六）
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        // 计算到本周一的天数差
        val daysToMonday = if (dayOfWeek == Calendar.SUNDAY) {
            6  // 周日往前推 6 天到周一
        } else {
            dayOfWeek - Calendar.MONDAY  // 其他日子往前推到周一
        }

        // 减去天数差，设置到本周一
        calendar.add(Calendar.DAY_OF_MONTH, -daysToMonday)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar
    }
}
