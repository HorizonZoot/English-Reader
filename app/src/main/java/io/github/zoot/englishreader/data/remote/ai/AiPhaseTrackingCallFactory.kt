package io.github.zoot.englishreader.data.remote.ai

import io.github.zoot.englishreader.data.ai.AiTimeoutException
import io.github.zoot.englishreader.data.ai.TimeoutPhase
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference

/** 单次 call 独有的可变阶段状态。它挂在 request 上，不会泄漏成全局状态。 */
class AiCallPhaseTracker {
    private val _phase = AtomicReference<TimeoutPhase?>(null)
    val phase: TimeoutPhase? get() = _phase.get()
    fun markConnect() = _phase.set(TimeoutPhase.CONNECT)
    fun markRead() = _phase.set(TimeoutPhase.READ)
    fun markCall() = _phase.compareAndSet(null, TimeoutPhase.CALL)
}

/** 挂上 tracker tag，并同时包装 OkHttp 的同步与异步失败。 */
class AiPhaseTrackingCallFactory(
    private val delegate: Call.Factory
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val tracker = AiCallPhaseTracker()
        val taggedRequest = request.newBuilder()
            .tag(AiCallPhaseTracker::class.java, tracker)
            .build()
        return TrackingCall(delegate, taggedRequest, tracker)
    }

    private class TrackingCall(
        private val callFactory: Call.Factory,
        private val taggedRequest: Request,
        private val tracker: AiCallPhaseTracker
    ) : Call {
        private val delegate: Call = callFactory.newCall(taggedRequest)
        override fun request(): Request = delegate.request()
        override fun execute(): Response = try {
            delegate.execute()
        } catch (error: IOException) {
            throw classified(error, tracker)
        }

        override fun enqueue(responseCallback: Callback) {
            delegate.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    responseCallback.onFailure(this@TrackingCall, classified(e, tracker))
                }

                override fun onResponse(call: Call, response: Response) {
                    responseCallback.onResponse(this@TrackingCall, response)
                }
            })
        }

        override fun cancel() = delegate.cancel()
        override fun isExecuted(): Boolean = delegate.isExecuted()
        override fun isCanceled(): Boolean = delegate.isCanceled()
        override fun timeout(): Timeout = delegate.timeout()
        override fun clone(): Call {
            val clonedTracker = AiCallPhaseTracker()
            val clonedRequest = taggedRequest.newBuilder()
                .tag(AiCallPhaseTracker::class.java, clonedTracker)
                .build()
            return TrackingCall(callFactory, clonedRequest, clonedTracker)
        }

        private fun classified(error: IOException, tracker: AiCallPhaseTracker): IOException =
            if (error is InterruptedIOException) {
                AiTimeoutException(
                    checkNotNull(AiTimeoutClassifier.classify(error, tracker.phase)),
                    error
                )
            } else {
                error
            }
    }
}

/** 把 OkHttp 的 event 阶段转交给 call 局部的 tracker。 */
class AiTimeoutEventListenerFactory : EventListener.Factory {
    override fun create(call: Call): EventListener {
        val tracker = call.request().tag(AiCallPhaseTracker::class.java)
            ?: return EventListener.NONE
        return object : EventListener() {
            override fun connectStart(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy
            ) = tracker.markConnect()

            override fun secureConnectStart(call: Call) = tracker.markConnect()
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = tracker.markConnect()
            override fun connectEnd(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?
            ) = tracker.markRead()

            override fun responseHeadersStart(call: Call) = tracker.markRead()
            override fun responseBodyStart(call: Call) = tracker.markRead()
        }
    }
}
