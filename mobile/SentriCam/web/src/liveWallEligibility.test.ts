import { describe, expect, it } from 'vitest'
import type { CameraControlCenterView, DeviceLiveState, LiveViewAvailability } from './models'
import { resolveCameraActionEligibility, summarizeEligibility } from './liveWallEligibility'

describe('Live Wall action eligibility', () => {
  it('allows only Start Live for one selected Idle/Ready camera', () => {
    const result = decide()

    expect(result.startLive.eligible).toBe(true)
    expect(result.stopLive).toMatchObject({ eligible: false, reason: 'no_active_live_session' })
  })

  it('allows Stop Live but not Start Live for a streaming camera', () => {
    const result = decide({ state: 'streaming', viewerIntent: true, sessionId: 'session-1' })

    expect(result.startLive).toMatchObject({ eligible: false, reason: 'live_session_active' })
    expect(result.stopLive.eligible).toBe(true)
  })

  it('allows Stop Recording but not Record for a recording camera', () => {
    const result = decide(undefined, 'connected', cameraControl('recording'))

    expect(result.startRecording).toMatchObject({ eligible: false, reason: 'recording_session_active' })
    expect(result.stopRecording.eligible).toBe(true)
  })

  it('summarizes mixed Ready and Streaming selections by eligible count', () => {
    const decisions = [decide(), decide({ state: 'streaming', viewerIntent: true, sessionId: 'session-2' })]

    expect(summarizeEligibility('startLive', decisions, 'selected')).toMatchObject({
      eligible: 1,
      total: 2,
      label: '1 of 2 selected can start',
    })
    expect(summarizeEligibility('stopLive', decisions, 'selected').eligible).toBe(1)
  })

  it('separates supported from unsupported recording capability', () => {
    expect(decide(undefined, 'connected', cameraControl('idle', true)).startRecording.eligible).toBe(true)
    expect(decide(undefined, 'connected', cameraControl('idle', false)).startRecording)
      .toMatchObject({ eligible: false, reason: 'recording_unsupported' })
  })

  it('blocks new actions while a camera is Recovering but permits stopping its owned session', () => {
    const idle = decide(undefined, 'recovering')
    const active = decide({ state: 'reconnecting', viewerIntent: true, sessionId: 'session-1' }, 'recovering')

    expect(idle.startLive).toMatchObject({ eligible: false, reason: 'device_recovering' })
    expect(idle.startRecording.eligible).toBe(false)
    expect(active.stopLive.eligible).toBe(true)
  })

  it('blocks recording when the capability report is stale or unavailable', () => {
    const stale = device('connected')
    stale.health = { ...stale.health!, isCurrent: false }

    expect(resolveCameraActionEligibility(stale, { availability: availability() }, {}, cameraControl()).startRecording)
      .toMatchObject({ eligible: false, reason: 'recording_state_stale' })
    expect(resolveCameraActionEligibility(device(), { availability: availability() }).startRecording)
      .toMatchObject({ eligible: false, reason: 'recording_capabilities_pending' })
  })

  it('blocks conflicting and duplicate actions while an operation is in progress', () => {
    const result = resolveCameraActionEligibility(
      device(),
      { availability: availability() },
      { live: true, recording: true },
      cameraControl(),
    )

    expect(result.startLive.reason).toBe('operation_in_progress')
    expect(result.stopLive.reason).toBe('operation_in_progress')
    expect(result.startRecording.reason).toBe('operation_in_progress')
    expect(result.stopRecording.reason).toBe('operation_in_progress')
  })

  it('keeps Stop all disabled when no wall camera owns an active session', () => {
    const summary = summarizeEligibility('stopLive', [decide(), decide()], 'wall cameras')

    expect(summary.eligible).toBe(0)
    expect(summary.detail).toBe('This camera has no Live session to stop.')
  })
})

function decide(
  tile: { state?: 'idle' | 'connecting' | 'streaming' | 'reconnecting' | 'failed'; viewerIntent?: boolean; sessionId?: string | null } = {},
  connectionState: DeviceLiveState['connectionState'] = 'connected',
  control = cameraControl(),
) {
  return resolveCameraActionEligibility(
    device(connectionState),
    { availability: availability(), ...tile },
    {},
    control,
  )
}

function availability(): LiveViewAvailability {
  return {
    deviceId: 'device-1', available: true, unavailableReason: null, capabilities: null,
    qualityOptions: [], readiness: 'ready', readinessReportedAtUtc: new Date().toISOString(),
  }
}

function cameraControl(recordingState = 'idle', supported = true): CameraControlCenterView {
  const now = new Date().toISOString()
  const settings = {
    lens: 'back', zoom: 1, torch: false, exposureCompensation: 0, preview: 'visible',
    framesPerSecond: 30, resolution: '1280x720', bitrate: 2_500_000, quality: 'medium', nightProfile: 'auto',
  }
  return {
    deviceId: 'device-1', online: true, desiredSettings: settings, updatedAtUtc: now, recentCommands: [],
    deviceState: {
      deviceId: 'device-1', settings, reportedAtUtc: now, motionSettings: null,
      capabilities: [{
        id: 'recording', supported, writable: supported,
        currentValue: { boolean: null, number: null, text: recordingState },
        minimum: null, maximum: null, step: null, allowedValues: ['start', 'stop'], unit: null,
        reason: supported ? null : 'recording_unsupported',
      }],
      telemetry: {
        cameraOnline: true, streaming: false, recording: recordingState === 'recording', recordingState,
        recordingOrigin: 'remote', motionArmed: true, batteryPercent: 80, temperatureCelsius: 31,
        availableStorageBytes: 1_024, charging: false, connectionQuality: 'good', previewAvailable: true,
        audioAvailable: false, latestRecordingUpload: null,
      },
    },
  }
}

function device(connectionState: DeviceLiveState['connectionState'] = 'connected'): DeviceLiveState {
  const current = connectionState === 'connected'
  const now = new Date().toISOString()
  return {
    deviceId: 'device-1', friendlyName: 'Camera', platform: 'Android', connectionState,
    operationalState: current ? 'active' : connectionState, monitoring: true, motion: 'NO_MOTION',
    recording: 'idle', batteryPercent: 80, availableStorageBytes: 1_024,
    lastHeartbeatAtUtc: now, lastSeenAtUtc: now, snapshotVersion: 1, snapshot: null,
    health: {
      overall: current ? 'healthy' : connectionState, subsystems: [], reportedAtUtc: now,
      presenceState: current ? 'connected' : connectionState, isCurrent: current,
      outageStartedAtUtc: current ? null : now, outageCause: current ? null : 'network',
    },
  }
}
