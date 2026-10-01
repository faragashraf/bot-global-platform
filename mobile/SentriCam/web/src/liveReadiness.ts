import type { EffectiveConnectionState } from './monitoringHealth'
import type { LiveReadinessState, LiveViewAvailability } from './models'

export type LiveReadinessPresentation = {
  state: LiveReadinessState
  label: string
  detail: string
  tone: 'positive' | 'warning' | 'danger' | 'neutral'
  canStart: boolean
  reason: string | null
}

/** Presentation-only mapping for the Server's authoritative, connection-scoped readiness. */
export function resolveLiveReadiness(
  availability: LiveViewAvailability | null | undefined,
  transport: EffectiveConnectionState,
): LiveReadinessPresentation {
  if (transport.connectionState === 'offline') {
    return result('unavailable', 'Unavailable', 'Device is offline.', 'danger', false, 'device_offline')
  }
  if (transport.connectionState === 'recovering') {
    return result('recovering', 'Recovering', 'Control connection is being restored.', 'warning', false, 'device_recovering')
  }
  if (!availability) {
    return result('initializing', 'Initializing', 'Checking camera readiness…', 'warning', false, 'live_readiness_pending')
  }

  const reason = availability.unavailableReason
  switch (availability.readiness) {
    case 'ready':
      return result('ready', 'Ready', 'Ready for Live.', 'positive', availability.available, reason)
    case 'starting':
      return result('starting', 'Starting', 'Opening the camera…', 'warning', false, reason)
    case 'live':
      return result('live', 'Live', 'Live publisher is active.', 'positive', false, reason)
    case 'recovering':
      return result('recovering', 'Recovering', friendlyLiveReason(reason), 'warning', false, reason)
    case 'failed':
      return result('failed', 'Failed', friendlyLiveReason(reason), 'danger', false, reason)
    case 'initializing':
      return result('initializing', 'Initializing', friendlyLiveReason(reason), 'warning', false, reason)
    default:
      return result('unavailable', 'Unavailable', friendlyLiveReason(reason), 'neutral', false, reason)
  }
}

export function friendlyLiveReason(code: string | null | undefined) {
  if (!code) return 'Live is temporarily unavailable.'
  if (code.includes('device_offline') || code.includes('device_recovering')) return 'Camera unavailable while the device reconnects.'
  if (code.includes('live_capabilities_stale') || code.includes('live_readiness_pending')) return 'Waiting for this connection to confirm camera readiness.'
  if (code.includes('camera_permission')) return 'Camera permission is required on the device.'
  if (code.includes('foreground_camera') || code.includes('background')) return 'The device blocked camera access while in the background.'
  if (code.includes('camera_busy') || code.includes('session_conflict')) return 'The camera is already in use or its previous session is being cleaned up.'
  if (code.includes('camera_initializing')) return 'The camera service is still initializing.'
  if (code.includes('camera_publisher') || code.includes('publisher_start')) return 'The Live publisher could not start.'
  if (code.includes('webrtc_session') || code.includes('negotiation')) return 'The video session could not be created.'
  if (code.includes('session_timeout')) return 'The device did not complete the Live request in time.'
  if (code.includes('device_acknowledgement_timeout')) return 'The device did not acknowledge the Live request in time.'
  if (code.includes('stale_device_connection')) return 'The device control connection was replaced. Waiting for the current connection.'
  if (code.includes('live_session_recovering')) return 'The existing Live session is being recovered.'
  return 'The camera could not start Live. Other cameras were not affected.'
}

function result(
  state: LiveReadinessState,
  label: string,
  detail: string,
  tone: LiveReadinessPresentation['tone'],
  canStart: boolean,
  reason: string | null,
): LiveReadinessPresentation {
  return { state, label, detail, tone, canStart, reason }
}
