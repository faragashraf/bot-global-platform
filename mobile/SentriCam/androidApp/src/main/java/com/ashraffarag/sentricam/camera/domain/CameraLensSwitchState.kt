package com.ashraffarag.sentricam.camera.domain

enum class CameraLensFacing {
    REAR,
    FRONT,
}

data class CameraLensSwitchState(
    val selectedLens: CameraLensFacing,
    val availableLenses: Set<CameraLensFacing> = emptySet(),
    val pendingLens: CameraLensFacing? = null,
) {
    val switchInProgress: Boolean
        get() = pendingLens != null

    val showSwitchControl: Boolean
        get() = availableLenses.containsAll(CameraLensFacing.entries)

    fun canSwitch(cameraReady: Boolean, recordingInProgress: Boolean): Boolean =
        showSwitchControl && cameraReady && !recordingInProgress && !switchInProgress

    fun withAvailableLenses(lenses: Set<CameraLensFacing>): CameraLensSwitchState =
        copy(availableLenses = lenses)

    fun beginSwitch(cameraReady: Boolean, recordingInProgress: Boolean): CameraLensSwitchState {
        if (!canSwitch(cameraReady, recordingInProgress)) return this
        val next = when (selectedLens) {
            CameraLensFacing.REAR -> CameraLensFacing.FRONT
            CameraLensFacing.FRONT -> CameraLensFacing.REAR
        }
        if (next !in availableLenses) return this
        return copy(pendingLens = next)
    }

    fun completeSwitch(): CameraLensSwitchState = pendingLens?.let { selected ->
        copy(selectedLens = selected, pendingLens = null)
    } ?: this

    fun cancelSwitch(): CameraLensSwitchState = copy(pendingLens = null)
}

class CameraLensSwitchController(initialLens: CameraLensFacing) {
    var state: CameraLensSwitchState = CameraLensSwitchState(initialLens)
        private set

    fun updateAvailableLenses(lenses: Set<CameraLensFacing>) {
        state = state.withAvailableLenses(lenses)
    }

    fun requestSwitch(cameraReady: Boolean, recordingInProgress: Boolean): CameraLensFacing? {
        val requestedState = state.beginSwitch(cameraReady, recordingInProgress)
        if (requestedState == state) return null
        state = requestedState
        return state.pendingLens
    }

    fun completeSwitch() {
        state = state.completeSwitch()
    }

    fun cancelSwitch() {
        state = state.cancelSwitch()
    }
}
