package com.mohamed3on.iptv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Idle {
    Watching,
    /** "Still watching?" is up; any key answers it. */
    Asking,
    /** Nobody answered: every stream was stopped; [IdleWatch.resume] starts watching again. */
    TimedOut,
}

/**
 * The idle stop (R24): after [IDLE_MS] without a remote press the app asks "Still watching?",
 * and if nobody answers within [ANSWER_MS] it calls [stopAll]. A forgotten TV then doesn't hold
 * the account's one connection all night. Call from the main thread; [scope] runs the timers.
 */
class IdleWatch(private val scope: CoroutineScope, private val stopAll: () -> Unit) {
    private val _state = MutableStateFlow(Idle.Watching)
    val state: StateFlow<Idle> = _state
    private var timer: Job? = null

    init {
        watch()
    }

    /** Any key event: restarts the 4h timer and answers the prompt. Ignored once timed out. */
    fun keyPressed() {
        if (_state.value != Idle.TimedOut) watch()
    }

    fun resume() = watch()

    private fun watch() {
        timer?.cancel()
        _state.value = Idle.Watching
        timer = scope.launch {
            delay(IDLE_MS)
            _state.value = Idle.Asking
            delay(ANSWER_MS)
            _state.value = Idle.TimedOut
            stopAll()
        }
    }

    private companion object {
        const val IDLE_MS = 4 * 60 * 60_000L
        const val ANSWER_MS = 60_000L
    }
}
