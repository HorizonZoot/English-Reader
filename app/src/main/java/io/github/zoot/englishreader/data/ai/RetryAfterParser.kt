package io.github.zoot.englishreader.data.ai

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** 解析有界的 Retry-After 元数据，本身不发起重试。 */
class RetryAfterParser(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    fun parse(value: String?): Long? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.all { it in '0'..'9' }) {
            val seconds = normalized.toLongOrNull() ?: return null
            return seconds.takeIf { it <= MAX_RETRY_AFTER_SECONDS }
        }

        val parsedMillis = parseHttpDate(normalized) ?: return null
        val remainingMillis = parsedMillis - currentTimeMillis()
        val seconds = if (remainingMillis <= 0L) {
            0L
        } else {
            (remainingMillis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND
        }
        return seconds.takeIf { it <= MAX_RETRY_AFTER_SECONDS }
    }

    private fun parseHttpDate(value: String): Long? {
        val formatter = SimpleDateFormat(HTTP_DATE_PATTERN, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val position = ParsePosition(0)
        val parsed = formatter.parse(value, position) ?: return null
        return parsed.time.takeIf { position.index == value.length }
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS = 86_400L
        const val MILLIS_PER_SECOND = 1_000L
        const val HTTP_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz"
    }
}
