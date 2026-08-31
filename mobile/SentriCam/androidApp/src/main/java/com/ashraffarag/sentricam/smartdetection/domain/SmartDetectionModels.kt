package com.ashraffarag.sentricam.smartdetection.domain

enum class SmartDetectionType { PERSON, ANIMAL, VEHICLE }
enum class IdentityKind { KNOWN, UNKNOWN }

data class DetectionIdentity(val id: String, val displayLabel: String?, val kind: IdentityKind)
data class DetectionSubject(
    val type: SmartDetectionType,
    val confidence: Double,
    val identity: DetectionIdentity? = null,
)

data class DetectionEvent(
    val eventId: String,
    val detectedAtMillis: Long,
    val subjects: List<DetectionSubject>,
)

enum class DetectionAction { RECORD, IGNORE, NOTIFY }

data class DetectionSelector(
    val types: Set<SmartDetectionType> = emptySet(),
    val identityKinds: Set<IdentityKind> = emptySet(),
    val identityIds: Set<String> = emptySet(),
) {
    fun matches(subject: DetectionSubject): Boolean =
        (types.isEmpty() || subject.type in types) &&
            (identityKinds.isEmpty() || subject.identity?.kind in identityKinds) &&
            (identityIds.isEmpty() || subject.identity?.id in identityIds)
}

data class DetectionRule(
    val id: String,
    val priority: Int,
    val selector: DetectionSelector,
    val actions: Set<DetectionAction>,
    val enabled: Boolean = true,
)

data class DetectionDecision(
    val actions: Set<DetectionAction>,
    val matchedRuleIds: List<String>,
) {
    val shouldIgnore: Boolean get() = DetectionAction.IGNORE in actions
    val shouldRecord: Boolean get() = !shouldIgnore && DetectionAction.RECORD in actions
    val shouldNotify: Boolean get() = !shouldIgnore && DetectionAction.NOTIFY in actions
}

interface SmartDetectionEngine {
    suspend fun analyze(eventId: String, frameTimestampMillis: Long): SmartDetectionResult
}

sealed interface SmartDetectionResult {
    data class Detected(val event: DetectionEvent) : SmartDetectionResult
    data object NoSubjects : SmartDetectionResult
    data class Unavailable(val reasonCode: String) : SmartDetectionResult
}

interface DetectionRulesEngine {
    fun decide(event: DetectionEvent, rules: List<DetectionRule>): DetectionDecision
}

class DefaultDetectionRulesEngine : DetectionRulesEngine {
    override fun decide(event: DetectionEvent, rules: List<DetectionRule>): DetectionDecision {
        val matched = rules.filter { rule ->
            rule.enabled && event.subjects.any(rule.selector::matches)
        }.sortedByDescending(DetectionRule::priority)
        val actions = matched.flatMap(DetectionRule::actions).toSet()
        return DetectionDecision(actions, matched.map(DetectionRule::id))
    }
}
