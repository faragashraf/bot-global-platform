import type { CameraControlCenterView, DeviceLiveState, LiveViewAvailability } from './models'
import { getEffectiveTransportState } from './monitoringHealth'
import { resolveLiveReadiness } from './liveReadiness'

export type WallAction = 'startLive' | 'stopLive' | 'startRecording' | 'stopRecording'

export type ActionEligibility = {
  eligible: boolean
  reason: string | null
  detail: string
}

export type LiveTileActionState = {
  sessionId?: string | null
  viewerIntent?: boolean
  state?: 'idle' | 'connecting' | 'streaming' | 'reconnecting' | 'failed'
  availability?: LiveViewAvailability | null
}

export type CameraActionBusyState = {
  live?: boolean
  recording?: boolean
}

export type RecordingOperationalState =
  | 'idle'
  | 'starting'
  | 'recording'
  | 'stopping'
  | 'failed'
  | 'unavailable'

export type CameraActionEligibility = {
  startLive: ActionEligibility
  stopLive: ActionEligibility
  startRecording: ActionEligibility
  stopRecording: ActionEligibility
  recordingState: RecordingOperationalState
  recordingSupported: boolean
}

/**
 * Authoritative Web action policy. It combines the Server's connection-scoped
 * Live readiness with current transport, session ownership and subsystem
 * freshness. Components may present this result, but must not recreate it.
 */
export function resolveCameraActionEligibility(
  device: DeviceLiveState,
  tile: LiveTileActionState | null | undefined,
  busy: CameraActionBusyState = {},
  cameraControl: CameraControlCenterView | null | undefined = null,
): CameraActionEligibility {
  const transport = getEffectiveTransportState(device)
  const readiness = resolveLiveReadiness(tile?.availability, transport)
  const liveActive = tile?.viewerIntent === true
    || Boolean(tile?.sessionId)
    || tile?.state === 'connecting'
    || tile?.state === 'streaming'
    || tile?.state === 'reconnecting'

  const startLive = busy.live
    ? blocked('operation_in_progress', 'A Live action is already in progress for this camera.')
    : liveActive
      ? blocked('live_session_active', 'This camera already has an active or recovering Live session.')
      : readiness.canStart
        ? allowed('Ready to start Live.')
        : blocked(readiness.reason ?? `live_${readiness.state}`, readiness.detail)

  const stopLive = busy.live
    ? blocked('operation_in_progress', 'A Live action is already in progress for this camera.')
    : liveActive
      ? allowed('The active or recovering Live session can be stopped.')
      : blocked('no_active_live_session', 'This camera has no Live session to stop.')

  const recording = resolveRecordingOperationalState(device, cameraControl)
  const recordingSupported = recording.supported
  const recordingReady = transport.isConnected
    && device.health?.isCurrent === true
    && recording.current
    && recording.state !== 'failed'
    && recording.state !== 'unavailable'

  const startRecording = busy.recording
    ? blocked('operation_in_progress', 'A recording action is already in progress for this camera.')
    : !recordingSupported
      ? blocked(recording.reason ?? 'recording_unsupported', recording.detail)
      : !transport.isConnected
        ? blocked(transport.isOffline ? 'device_offline' : 'device_recovering', 'Recording is unavailable while the device reconnects.')
        : !recordingReady
          ? blocked(recording.reason ?? 'recording_unavailable', recording.detail)
          : recording.state === 'starting' || recording.state === 'recording' || recording.state === 'stopping'
            ? blocked('recording_session_active', 'Recording is already active or changing state.')
            : allowed('Ready to start recording.')

  const stopRecording = busy.recording
    ? blocked('operation_in_progress', 'A recording action is already in progress for this camera.')
    : recording.state === 'recording' || recording.state === 'stopping'
      ? allowed('The current recording can be stopped.')
      : blocked('no_active_recording', 'This camera is not recording.')

  return {
    startLive,
    stopLive,
    startRecording,
    stopRecording,
    recordingState: recording.state,
    recordingSupported,
  }
}

export function summarizeEligibility(
  action: WallAction,
  decisions: CameraActionEligibility[],
  scopeLabel: string,
) {
  const eligible = decisions.filter((decision) => decision[action].eligible).length
  const total = decisions.length
  if (total === 0) return { eligible, total, label: `No ${scopeLabel}`, detail: `Select at least one camera.` }
  const noun = eligible === 1 ? 'camera' : 'cameras'
  const label = `${eligible} of ${total} ${scopeLabel} can ${actionLabel(action)}`
  if (eligible > 0) return { eligible, total, label, detail: `${eligible} ${noun} will receive this action.` }
  const firstReason = decisions.map((decision) => decision[action]).find((value) => value.reason)
  return { eligible, total, label, detail: firstReason?.detail ?? 'No camera is currently eligible.' }
}

export function actionLabel(action: WallAction) {
  return ({
    startLive: 'start',
    stopLive: 'stop',
    startRecording: 'record',
    stopRecording: 'stop recording',
  } as const)[action]
}

function resolveRecordingOperationalState(device: DeviceLiveState, control: CameraControlCenterView | null | undefined) {
  const deviceState = control?.deviceState
  const descriptor = deviceState?.capabilities.find((value) => value.id === 'recording')
  const supported = descriptor?.supported === true && descriptor.writable === true
  if (!supported) {
    return {
      supported: false,
      current: false,
      state: 'unavailable' as const,
      reason: descriptor ? descriptor.reason ?? 'recording_unsupported' : 'recording_capabilities_pending',
      detail: descriptor
        ? descriptor.reason ?? 'Recording is not supported by this camera.'
        : 'Waiting for this connection to confirm recording support.',
    }
  }

  const raw = deviceState!.telemetry.recordingState.trim().toLowerCase()
  const current = device.health?.isCurrent === true && control!.online
  const state: RecordingOperationalState = raw.includes('starting')
    ? 'starting'
    : raw.includes('stopping')
      ? 'stopping'
      : raw.includes('record')
        ? 'recording'
        : raw.includes('fail')
          ? 'failed'
          : raw === 'idle' || raw === 'ready' || raw === 'completed'
            ? 'idle'
            : 'unavailable'

  if (!current) {
    return {
      supported,
      current,
      state: state === 'recording' || state === 'stopping' ? state : 'unavailable' as RecordingOperationalState,
      reason: 'recording_state_stale',
      detail: 'Waiting for a current recording status from this camera.',
    }
  }
  return {
    supported,
    current,
    state,
    reason: state === 'failed' ? 'recording_failed' : null,
    detail: state === 'failed' ? 'The recording subsystem requires attention.' : 'Recording status is current.',
  }
}

function allowed(detail: string): ActionEligibility {
  return { eligible: true, reason: null, detail }
}

function blocked(reason: string, detail: string): ActionEligibility {
  return { eligible: false, reason, detail }
}
