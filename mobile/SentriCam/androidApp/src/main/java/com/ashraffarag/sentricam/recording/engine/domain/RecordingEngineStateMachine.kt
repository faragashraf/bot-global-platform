package com.ashraffarag.sentricam.recording.engine.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecordingEngineStateMachine(initialState: RecordingState = RecordingState.Idle) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()

    fun transitionTo(next: RecordingState): Boolean {
        val current = mutableState.value
        if (!isAllowed(current, next)) return false
        mutableState.value = next
        return true
    }

    private fun isAllowed(current: RecordingState, next: RecordingState): Boolean = when (current) {
        RecordingState.Idle -> next is RecordingState.Preparing || next == RecordingState.Idle
        is RecordingState.Preparing ->
            next is RecordingState.Ready || next is RecordingState.Failed || next == RecordingState.Idle
        is RecordingState.Ready ->
            next is RecordingState.Ready || next is RecordingState.Starting || next is RecordingState.Preparing ||
                next is RecordingState.Failed || next == RecordingState.Idle
        is RecordingState.Starting ->
            next is RecordingState.Starting || next is RecordingState.Recording || next is RecordingState.Stopping ||
                next is RecordingState.Failed || next is RecordingState.Ready
        is RecordingState.Recording ->
            next is RecordingState.Recording || next is RecordingState.RotatingSegment ||
                next is RecordingState.Stopping || next is RecordingState.Failed
        is RecordingState.RotatingSegment ->
            next is RecordingState.RotatingSegment || next is RecordingState.Starting || next is RecordingState.Stopping ||
                next is RecordingState.Failed
        is RecordingState.Stopping ->
            next is RecordingState.Stopping || next is RecordingState.Completed || next is RecordingState.Failed
        is RecordingState.Completed ->
            next is RecordingState.Preparing || next == RecordingState.Idle
        is RecordingState.Failed ->
            next is RecordingState.Preparing || next == RecordingState.Idle
    }
}
