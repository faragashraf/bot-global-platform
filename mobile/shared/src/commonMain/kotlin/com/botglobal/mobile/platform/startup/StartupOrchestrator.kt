package com.botglobal.mobile.platform.startup

enum class StartupStage {
    PlatformInitialization,
    Localization,
    SessionRestoration,
    BiometricUnlock,
    UpdatePolicy,
    NotificationRegistration,
    ActiveGameRecovery,
    Navigation,
}

data class StartupStep(
    val stage: StartupStage,
    val critical: Boolean,
    val run: suspend () -> Unit,
)

data class StartupFailure(val stage: StartupStage, val critical: Boolean, val cause: Throwable)
data class StartupResult(val completed: List<StartupStage>, val failures: List<StartupFailure>) {
    val canNavigate: Boolean get() = failures.none(StartupFailure::critical)
}

class StartupOrchestrator(private val steps: List<StartupStep>) {
    suspend fun run(): StartupResult {
        val completed = mutableListOf<StartupStage>()
        val failures = mutableListOf<StartupFailure>()
        for (step in steps) {
            try {
                step.run()
                completed += step.stage
            } catch (cause: Throwable) {
                failures += StartupFailure(step.stage, step.critical, cause)
                if (step.critical) break
            }
        }
        return StartupResult(completed, failures)
    }
}
