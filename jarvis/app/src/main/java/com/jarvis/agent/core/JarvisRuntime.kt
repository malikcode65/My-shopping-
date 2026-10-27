package com.jarvis.agent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AgentPhase { OFF, WAITING_WAKE_WORD, LISTENING, THINKING, ACTING, WAITING_CONFIRMATION, SPEAKING }

data class RuntimeState(
    val serviceRunning: Boolean = false,
    val phase: AgentPhase = AgentPhase.OFF,
    val lastHeard: String = "",
    val lastReply: String = "",
    val currentStep: Int = 0,
    val currentAction: String = "",
    val wakeWordError: String? = null,
)

/** Observable state shared by the service, the overlay and the UI. */
object JarvisRuntime {
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    fun update(block: (RuntimeState) -> RuntimeState) = _state.update(block)

    fun phase(p: AgentPhase) = update { it.copy(phase = p) }
}
