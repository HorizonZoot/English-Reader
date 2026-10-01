package io.github.zoot.englishreader.ui.screen.vocabulary

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.transformLatest

/** 同一次采样的时间和时区，避免分桶与午夜等待使用不同的系统状态。 */
data class VocabularyGroupingTime(val nowMillis: Long, val zoneId: ZoneId) {
    fun millisUntilNextMidnight(): Long {
        val nextMidnight = Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        return (nextMidnight - nowMillis).coerceAtLeast(1L)
    }

    companion object {
        fun current() = VocabularyGroupingTime(System.currentTimeMillis(), ZoneId.systemDefault())
    }
}

/** 每个系统事件都重置等待，即使校时后仍在同一天；取消由页面生命周期负责。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeVocabularyTime(
    refreshSignals: Flow<Unit>,
    readTime: () -> VocabularyGroupingTime = { VocabularyGroupingTime.current() }
): Flow<VocabularyGroupingTime> = refreshSignals.transformLatest {
    while (true) {
        val time = readTime()
        emit(time)
        delay(time.millisUntilNextMidnight())
    }
}

internal fun vocabularyTimeSignals(context: Context, lifecycle: Lifecycle): Flow<Unit> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            trySend(Unit)
        }
    }
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) trySend(Unit)
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    try {
        lifecycle.addObserver(observer)
        trySend(Unit)
        awaitClose()
    } finally {
        lifecycle.removeObserver(observer)
        context.unregisterReceiver(receiver)
    }
}
