import type { DeviceLiveState, SubsystemHealth } from './models'
import { getEffectiveTransportState } from './monitoringHealth'

export type MotionSupportState = 'supported' | 'unsupported' | 'unknown'
export type MotionDetectorState =
  | 'disabled'
  | 'initializing'
  | 'running'
  | 'temporarily_unavailable'
  | 'failed'
  | 'unknown'

export type MotionActivityState =
  | 'none'
  | 'suspected'
  | 'detected'
  | 'holding'
  | 'cooldown'
  | 'unknown'

export type MotionStatus = {
  support: MotionSupportState
  enabled: boolean | null
  detector: MotionDetectorState
  activity: MotionActivityState
  label: string
  detail: string
  reason: string | null
  tone: 'positive' | 'warning' | 'danger' | 'neutral'
}

const normalize = (value: string | null | undefined) => value?.trim().toLowerCase() ?? ''

export function resolveMotionStatus(device: DeviceLiveState): MotionStatus {
  const transport = getEffectiveTransportState(device)
  const subsystem = findMotionSubsystem(device.health?.subsystems ?? [])
  const raw = normalize(device.motion)
  const reason = normalize(subsystem?.recoveryReason)
  const enabled = readMotionEnabled(device.snapshot, raw)
  const support = resolveSupport(raw, reason, subsystem, enabled)

  if (support === 'unsupported') {
    return status(support, enabled, 'unknown', 'unknown', 'Unsupported', 'Motion detection is not supported by this device.', subsystem?.recoveryReason ?? 'motion_unsupported', 'neutral')
  }

  if (!transport.isConnected) {
    return status(
      support,
      enabled,
      'temporarily_unavailable',
      activityFromRaw(raw),
      'Temporarily unavailable',
      transport.connectionState === 'offline'
        ? 'Motion status is unavailable while the device is offline.'
        : 'Waiting for the device control connection to recover.',
      'device_transport_unavailable',
      'neutral',
    )
  }

  if (device.health?.isCurrent !== true) {
    return status(support, enabled, 'temporarily_unavailable', activityFromRaw(raw), 'Temporarily unavailable', 'Waiting for a fresh Motion report from the device.', 'motion_report_stale', 'neutral')
  }

  const subsystemHealth = normalize(subsystem?.health)
  const subsystemLifecycle = normalize(subsystem?.lifecycle)
  if (raw === 'error' || subsystemHealth === 'failed' || subsystemHealth === 'action_required' || subsystemLifecycle === 'failed' || subsystemLifecycle === 'action_required') {
    return status(support, enabled, 'failed', activityFromRaw(raw), 'Failed', motionFailureDetail(subsystem?.recoveryReason), subsystem?.recoveryReason ?? 'motion_failed', 'danger')
  }

  if (raw === 'initializing' || subsystemHealth === 'recovering' || subsystemLifecycle === 'starting' || subsystemLifecycle === 'recovering') {
    return status(support, enabled, 'initializing', activityFromRaw(raw), 'Initializing', 'The Motion detector is starting and will report when it is ready.', subsystem?.recoveryReason ?? 'motion_initializing', 'warning')
  }

  if (enabled === false || raw === 'disabled') {
    return status(support, false, 'disabled', 'none', 'Disabled', 'Motion detection is turned off.', null, 'neutral')
  }

  if (support === 'unknown' || !subsystem) {
    return status(support, enabled, 'unknown', activityFromRaw(raw), 'Unavailable', 'Motion capability was not included in the current device report.', 'motion_capability_not_reported', 'neutral')
  }

  const activity = activityFromRaw(raw)
  if (activity === 'detected' || activity === 'holding') {
    return status(support, enabled, 'running', activity, 'Motion detected', activity === 'holding' ? 'The active Motion event is being held.' : 'The detector reports current Motion activity.', null, 'warning')
  }
  if (activity === 'suspected') {
    return status(support, enabled, 'running', activity, 'Motion suspected', 'The detector is validating possible Motion activity.', null, 'warning')
  }
  if (activity === 'cooldown') {
    return status(support, enabled, 'running', activity, 'Armed', 'Detector running · cooldown after recent Motion.', null, 'positive')
  }

  if (subsystemHealth === 'healthy' && (subsystemLifecycle === 'running' || subsystemLifecycle === 'idle')) {
    return status(support, enabled, 'running', activity, 'Armed', 'Detector running · no Motion detected.', null, 'positive')
  }

  if (subsystemHealth === 'degraded' || subsystemLifecycle === 'degraded') {
    return status(support, enabled, 'failed', activity, 'Action required', motionFailureDetail(subsystem?.recoveryReason), subsystem?.recoveryReason ?? 'motion_degraded', 'danger')
  }

  return status(support, enabled, 'temporarily_unavailable', activity, 'Temporarily unavailable', 'The latest Motion report does not confirm that the detector is running.', subsystem?.recoveryReason ?? 'motion_state_unconfirmed', 'neutral')
}

function findMotionSubsystem(subsystems: SubsystemHealth[]): SubsystemHealth | undefined {
  return subsystems.find((item) => normalize(item.subsystem) === 'motion')
}

function readMotionEnabled(snapshot: Record<string, unknown> | null, raw: string): boolean | null {
  if (snapshot) {
    const configuration = snapshot.motionConfiguration
    if (configuration && typeof configuration === 'object' && !Array.isArray(configuration)) {
      const enabled = (configuration as Record<string, unknown>).enabled
      if (typeof enabled === 'boolean') return enabled
    }
    if (typeof snapshot.motionEnabled === 'boolean') return snapshot.motionEnabled
  }
  if (raw === 'disabled') return false
  if (['initializing', 'no_motion', 'suspected', 'confirmed', 'holding', 'cooldown', 'error'].includes(raw)) return true
  return null
}

function resolveSupport(
  raw: string,
  reason: string,
  subsystem: SubsystemHealth | undefined,
  enabled: boolean | null,
): MotionSupportState {
  if (raw.includes('unsupported') || reason.includes('unsupported') || reason.includes('not_supported')) return 'unsupported'
  if (subsystem || enabled !== null) return 'supported'
  return 'unknown'
}

function activityFromRaw(raw: string): MotionActivityState {
  if (raw === 'no_motion' || raw === 'disabled' || raw === 'initializing') return 'none'
  if (raw === 'suspected') return 'suspected'
  if (raw === 'confirmed') return 'detected'
  if (raw === 'holding') return 'holding'
  if (raw === 'cooldown') return 'cooldown'
  return 'unknown'
}

function motionFailureDetail(reason: string | null | undefined) {
  if (!reason) return 'The Motion detector reported a failure.'
  const normalized = normalize(reason)
  if (normalized.includes('permission')) return 'Camera permission is required for Motion detection.'
  if (normalized.includes('camera')) return 'The camera is not available to the Motion detector.'
  if (normalized.includes('background')) return 'Device background restrictions interrupted Motion detection.'
  return `Motion detector unavailable · ${reason.replaceAll('_', ' ')}.`
}

function status(
  support: MotionSupportState,
  enabled: boolean | null,
  detector: MotionDetectorState,
  activity: MotionActivityState,
  label: string,
  detail: string,
  reason: string | null,
  tone: MotionStatus['tone'],
): MotionStatus {
  return { support, enabled, detector, activity, label, detail, reason, tone }
}
