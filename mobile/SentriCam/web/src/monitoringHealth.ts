import { readable } from './format'
import type { DeviceLiveState } from './models'

export type EffectiveConnectionTone = 'positive' | 'warning' | 'danger' | 'neutral'

export type EffectiveConnectionState = {
  label: string
  tone: EffectiveConnectionTone
  connectionState: 'connected' | 'offline' | 'recovering' | 'degraded' | 'actionRequired' | 'unknown'
  isRecovering: boolean
  isOffline: boolean
  isHealthy: boolean
  isConnected: boolean
  pulse: boolean
}

const neutralUnknown = 'unknown'
const heartbeatOfflineAfterMilliseconds = 25_000

const ageFromNow = (value: string | null, now: number): number | null => {
  if (value === null) return null
  const parsed = Date.parse(value)
  if (Number.isNaN(parsed)) return null
  const age = now - parsed
  return age >= 0 ? age : 0
}

const isHeartbeatExpired = (lastHeartbeatAtUtc: string | null, now: number): boolean => {
  const age = ageFromNow(lastHeartbeatAtUtc, now)
  return age !== null && age >= heartbeatOfflineAfterMilliseconds
}

const normalize = (value: string | null | undefined): string => value?.trim().toLowerCase() ?? neutralUnknown

export type EffectiveConnectionInput = Pick<
  DeviceLiveState,
  'connectionState' | 'health' | 'lastHeartbeatAtUtc'
>

export function getEffectiveConnectionState(
  device: EffectiveConnectionInput,
  now: number = Date.now(),
): EffectiveConnectionState {
  if (device.connectionState === 'offline') {
    return {
      label: 'Offline',
      tone: 'danger',
      connectionState: 'offline',
      isRecovering: false,
      isOffline: true,
      isHealthy: false,
      isConnected: false,
      pulse: false,
    }
  }

  if (device.connectionState === 'recovering') {
    return {
      label: 'Recovering',
      tone: 'warning',
      connectionState: 'recovering',
      isRecovering: true,
      isOffline: false,
      isHealthy: false,
      isConnected: false,
      pulse: true,
    }
  }

  const health = device.health
  const presence = normalize(health?.presenceState)
  if (presence === 'recovering') {
    return {
      label: 'Recovering',
      tone: 'warning',
      connectionState: 'recovering',
      isRecovering: true,
      isOffline: false,
      isHealthy: false,
      isConnected: false,
      pulse: true,
    }
  }

  const isPresent = presence === 'connected'
    || (presence === neutralUnknown && health != null)
  if (!isPresent) {
    return {
      label: 'Offline',
      tone: 'danger',
      connectionState: 'offline',
      isRecovering: false,
      isOffline: true,
      isHealthy: false,
      isConnected: false,
      pulse: false,
    }
  }

  if (isHeartbeatExpired(device.lastHeartbeatAtUtc, now)) {
    return {
      label: 'Offline',
      tone: 'danger',
      connectionState: 'offline',
      isRecovering: false,
      isOffline: true,
      isHealthy: false,
      isConnected: false,
      pulse: false,
    }
  }

  const isCurrent = health?.isCurrent === true
  if (isCurrent === false) {
    return {
      label: 'Recovering',
      tone: 'warning',
      connectionState: 'recovering',
      isRecovering: true,
      isOffline: false,
      isHealthy: false,
      isConnected: false,
      pulse: true,
    }
  }

  const overall = normalize(health?.overall)
  if (overall === 'healthy') {
    return {
      label: 'Healthy',
      tone: 'positive',
      connectionState: 'connected',
      isRecovering: false,
      isOffline: false,
      isHealthy: true,
      isConnected: true,
      pulse: true,
    }
  }

  if (overall === 'recovering') {
    return {
      label: 'Recovering',
      tone: 'warning',
      connectionState: 'recovering',
      isRecovering: true,
      isOffline: false,
      isHealthy: false,
      isConnected: false,
      pulse: true,
    }
  }

  if (overall === 'degraded') {
    return {
      label: 'Degraded',
      tone: 'warning',
      connectionState: 'degraded',
      isRecovering: true,
      isOffline: false,
      isHealthy: false,
      isConnected: true,
      pulse: false,
    }
  }

  if (overall === 'action_required' || overall === 'failed' || overall === 'offline' || overall === 'unavailable') {
    return {
      label: readable(overall),
      tone: 'danger',
      connectionState: overall === 'action_required' ? 'actionRequired' : 'offline',
      isRecovering: false,
      isOffline: overall === 'offline' || overall === 'unavailable',
      isHealthy: false,
      isConnected: overall !== 'offline' && overall !== 'unavailable',
      pulse: false,
    }
  }

  return {
    label: readable(overall),
    tone: 'neutral',
    connectionState: 'unknown',
    isRecovering: false,
    isOffline: false,
    isHealthy: false,
    isConnected: true,
    pulse: false,
  }
}

/**
 * Resolves only the device-to-Hub transport. Operational subsystem recovery
 * (for example Live starting or a camera handoff) must never be interpreted as
 * a lost control connection.
 */
export function getEffectiveTransportState(
  device: EffectiveConnectionInput,
  now: number = Date.now(),
): EffectiveConnectionState {
  if (device.connectionState === 'offline') return offlineState()
  if (device.connectionState === 'recovering') return recoveringState()

  const presence = normalize(device.health?.presenceState)
  if (presence === 'offline' || presence === 'unavailable') return offlineState()
  if (presence === 'recovering') return recoveringState()
  if (isHeartbeatExpired(device.lastHeartbeatAtUtc, now)) return offlineState()

  return {
    label: 'Healthy',
    tone: 'positive',
    connectionState: 'connected',
    isRecovering: false,
    isOffline: false,
    isHealthy: true,
    isConnected: true,
    pulse: true,
  }
}

function offlineState(): EffectiveConnectionState {
  return {
    label: 'Offline',
    tone: 'danger',
    connectionState: 'offline',
    isRecovering: false,
    isOffline: true,
    isHealthy: false,
    isConnected: false,
    pulse: false,
  }
}

function recoveringState(): EffectiveConnectionState {
  return {
    label: 'Recovering',
    tone: 'warning',
    connectionState: 'recovering',
    isRecovering: true,
    isOffline: false,
    isHealthy: false,
    isConnected: false,
    pulse: true,
  }
}

const connectionPriority: Record<EffectiveConnectionState['connectionState'], number> = {
  connected: 0,
  unknown: 1,
  degraded: 2,
  recovering: 3,
  actionRequired: 4,
  offline: 5,
}

/**
 * The dashboard receives the selected device from both the fleet response and
 * the detail response. Never allow a less critical, independently fetched
 * snapshot to hide a more critical state for the same device.
 */
export function selectMoreCriticalDeviceState(
  detailDevice: DeviceLiveState,
  fleetDevice: DeviceLiveState | undefined,
): DeviceLiveState {
  if (!fleetDevice || fleetDevice.deviceId !== detailDevice.deviceId) return detailDevice

  const detailPriority = connectionPriority[getEffectiveConnectionState(detailDevice).connectionState]
  const fleetPriority = connectionPriority[getEffectiveConnectionState(fleetDevice).connectionState]
  return fleetPriority > detailPriority ? fleetDevice : detailDevice
}
