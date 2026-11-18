package com.quickmath.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiState(
    val running: Boolean = false,
    val ocrStatus: String = "Idle",
    val lastQuestion: String = "\u2014",
    val lastAnswer: String = "\u2014",
    val avgMs: Long = 0L,
    val solved: Int = 0,
    val skipped: Int = 0,
    val scans: Int = 0,
    val message: String = ""
)

/** Shared between the capture service and the UI (same process). */
object MonitorState {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var totalMs = 0L

    @Synchronized
    fun start() {
        totalMs = 0L
        _state.value = UiState(running = true, ocrStatus = "Waiting for a question")
    }

    @Synchronized
    fun stop() {
        _state.update { it.copy(running = false, ocrStatus = "Idle") }
    }

    fun setMessage(msg: String) {
        _state.update { it.copy(message = msg) }
    }

    fun setOcr(status: String) {
        _state.update { it.copy(ocrStatus = status) }
    }

    fun frameScanned(status: String) {
        _state.update { it.copy(scans = it.scans + 1, ocrStatus = status) }
    }

    fun skippedUnclear() {
        _state.update { it.copy(skipped = it.skipped + 1, ocrStatus = "Unclear read skipped") }
    }

    @Synchronized
    fun solved(question: String, answer: String, ms: Long) {
        totalMs += ms
        _state.update {
            val n = it.solved + 1
            it.copy(
                solved = n,
                lastQuestion = question,
                lastAnswer = answer,
                avgMs = totalMs / n,
                ocrStatus = "Question found",
                message = ""
            )
        }
    }
}
