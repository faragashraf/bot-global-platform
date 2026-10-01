import { describe, expect, it } from 'vitest'
import type { DeviceLiveState, SubsystemHealth } from './models'
import { resolveMotionStatus } from './motionStatus'

describe('authoritative Motion status', () => {
  it('maps Android NO_MOTION plus a fresh Running report to Armed', () => {
    const result = resolveMotionStatus(device())

    expect(result).toMatchObject({
      support: 'supported',
      enabled: true,
      detector: 'running',
      activity: 'none',
      label: 'Armed',
      tone: 'positive',
    })
  })

  it('separates disabled, initializing, failed, and temporarily unavailable states', () => {
    expect(resolveMotionStatus(device({ motion: 'DISABLED', snapshot: { motionEnabled: false } })).detector).toBe('disabled')
    expect(resolveMotionStatus(device({ motion: 'INITIALIZING', subsystem: motionSubsystem('starting', 'recovering', 'motion_initializing') })).detector).toBe('initializing')
    expect(resolveMotionStatus(device({ motion: 'ERROR', subsystem: motionSubsystem('failed', 'degraded', 'camera_unavailable') }))).toMatchObject({ detector: 'failed', reason: 'camera_unavailable' })
    expect(resolveMotionStatus(device({ connectionState: 'offline' }))).toMatchObject({ detector: 'temporarily_unavailable', reason: 'device_transport_unavailable' })
  })

  it('does not infer availability from a healthy heartbeat without a Motion capability report', () => {
    const value = device({ motion: 'unknown', snapshot: null, subsystem: null })

    expect(resolveMotionStatus(value)).toMatchObject({
      support: 'unknown',
      enabled: null,
      detector: 'unknown',
      label: 'Unavailable',
      reason: 'motion_capability_not_reported',
    })
  })

  it('requires a fresh report again after reconnect', () => {
    const value = device()
    value.health = { ...value.health!, isCurrent: false }

    expect(resolveMotionStatus(value)).toMatchObject({
      detector: 'temporarily_unavailable',
      label: 'Temporarily unavailable',
      reason: 'motion_report_stale',
    })
  })
})

function device(overrides: {
  motion?: string
  connectionState?: DeviceLiveState['connectionState']
  snapshot?: Record<string, unknown> | null
  subsystem?: SubsystemHealth | null
} = {}): DeviceLiveState {
  const connectionState = overrides.connectionState ?? 'connected'
  const current = connectionState === 'connected'
  const subsystem = overrides.subsystem === undefined ? motionSubsystem('running', 'healthy', null) : overrides.subsystem
  return {
    deviceId: 'device-1', friendlyName: 'Huawei', platform: 'Android', connectionState,
    operationalState: 'monitoring', monitoring: true, motion: overrides.motion ?? 'NO_MOTION', recording: 'IDLE',
    batteryPercent: 80, availableStorageBytes: 1024, lastHeartbeatAtUtc: new Date().toISOString(),
    lastSeenAtUtc: new Date().toISOString(), snapshotVersion: 1,
    snapshot: overrides.snapshot === undefined
      ? { motionEnabled: true, motionConfiguration: { enabled: true } }
      : overrides.snapshot,
    health: {
      overall: current ? 'healthy' : connectionState,
      subsystems: subsystem ? [subsystem] : [],
      reportedAtUtc: new Date().toISOString(), presenceState: current ? 'connected' : connectionState,
      isCurrent: current, outageStartedAtUtc: current ? null : new Date().toISOString(),
      outageCause: current ? null : 'network',
    },
  }
}

function motionSubsystem(lifecycle: string, health: string, recoveryReason: string | null): SubsystemHealth {
  return {
    subsystem: 'motion', lifecycle, health, recoveryReason, reconnectCount: 0,
    lastFailureAtUtc: null, lastRecoveryAtUtc: null, recoveryDurationMilliseconds: null,
    updatedAtUtc: new Date().toISOString(),
  }
}
