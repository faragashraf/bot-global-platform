import { useEffect } from 'react'
import { formatRelative, formatStorage, readable } from '../format'
import type { CommandAction, DeviceMonitoringDetails, LiveViewAvailability } from '../models'
import { CommandBar } from './CommandBar'
import { MetricCard } from './MetricCard'
import { StatusBadge } from './StatusBadge'
import { Icon } from './Icon'
import { DeviceRemovalDialog } from './DeviceRemovalDialog'
import type { DeviceRemovalImpact, DeviceRemovalResult } from '../models'
import { RecoveryDashboard } from './RecoveryDashboard'
import { getEffectiveConnectionState, getEffectiveTransportState } from '../monitoringHealth'
import { monitoringTrace } from '../monitoringDiagnostics'
import { resolveLiveReadiness } from '../liveReadiness'
import { resolveMotionStatus } from '../motionStatus'
import { resolveCameraActionEligibility } from '../liveWallEligibility'

const commandNames: Record<number, string> = { 1: 'Start monitoring', 2: 'Stop monitoring', 6: 'Ping', 7: 'Refresh status' }
const commandStates: Record<number, { label: string; tone: 'warning' | 'positive' | 'danger' }> = {
  1: { label: 'Pending', tone: 'warning' },
  2: { label: 'Succeeded', tone: 'positive' },
  3: { label: 'Failed', tone: 'danger' },
  4: { label: 'Timeout', tone: 'danger' },
}

export function DeviceDetails({ details, busy, onCommand, onViewRecordings, onViewLive, liveAvailability, loadRemovalImpact, removeDevice, onDeviceRemoved }: {
  details: DeviceMonitoringDetails
  busy: CommandAction | null
  onCommand: (action: CommandAction) => void
  onViewRecordings?: () => void
  onViewLive?: () => void
  liveAvailability?: LiveViewAvailability | null
  loadRemovalImpact?: (deviceId: string) => Promise<DeviceRemovalImpact>
  removeDevice?: (deviceId: string, deviceName: string) => Promise<DeviceRemovalResult>
  onDeviceRemoved?: (result: DeviceRemovalResult) => void
}) {
  const { device, recentCommands } = details
  const effectiveConnection = getEffectiveConnectionState(device)
  const liveReadiness = resolveLiveReadiness(liveAvailability, getEffectiveTransportState(device))
  const liveAction = resolveCameraActionEligibility(device, { availability: liveAvailability }).startLive
  const motion = resolveMotionStatus(device)
  const battery = device.batteryPercent === null ? 'Unknown' : `${device.batteryPercent}%`
  const offlineClass = effectiveConnection.isOffline
    ? ' device-main--offline'
    : effectiveConnection.isRecovering ? ' device-main--recovering' : ''
  const metricTone = effectiveConnection.tone === 'danger'
    ? 'warning'
    : effectiveConnection.tone === 'neutral'
      ? 'default'
      : effectiveConnection.tone

  useEffect(() => {
    monitoringTrace('device_card_rendered', {
      deviceId: device.deviceId,
      connectionState: effectiveConnection.connectionState,
      label: effectiveConnection.label,
      tone: effectiveConnection.tone,
      className: `device-main${offlineClass}`,
      lastHeartbeatAtUtc: device.lastHeartbeatAtUtc,
    })
  }, [
    device.deviceId,
    device.lastHeartbeatAtUtc,
    effectiveConnection.connectionState,
    effectiveConnection.label,
    effectiveConnection.tone,
    offlineClass,
  ])

  return (
    <main className={`device-main${offlineClass}`}>
      <section className="device-hero">
        <div className={offlineClass ? 'device-hero--offline' : ''}>
          <div className="device-hero__badges">
            <StatusBadge tone={effectiveConnection.tone} pulse={effectiveConnection.pulse}>{effectiveConnection.label}</StatusBadge>
            <StatusBadge tone={device.monitoring ? 'accent' : 'neutral'}>{device.monitoring ? 'Monitoring' : 'Standby'}</StatusBadge>
            {onViewLive && <StatusBadge tone={liveReadiness.tone}>Live {liveReadiness.label}</StatusBadge>}
          </div>
          <h1>{device.friendlyName}</h1>
          <p>{device.platform} device · Last heartbeat {formatRelative(device.lastHeartbeatAtUtc)}</p>
        </div>
        <div className="device-hero__actions"><CommandBar monitoring={device.monitoring} busy={busy} onCommand={onCommand} />{onViewLive && <button className="button button--primary" onClick={onViewLive} disabled={!liveAction.eligible} title={liveAction.detail}><Icon name="play"/>View Live</button>}{onViewRecordings && <button className="button button--quiet" onClick={onViewRecordings}>View recordings</button>}{loadRemovalImpact && removeDevice && onDeviceRemoved && <DeviceRemovalDialog deviceId={device.deviceId} loadImpact={loadRemovalImpact} removeDevice={removeDevice} onRemoved={onDeviceRemoved}/>}</div>
      </section>

      <section className="metrics" aria-label="Live device state">
        <MetricCard icon="pulse" label="Connection" value={effectiveConnection.label} detail={`Heartbeat ${formatRelative(device.lastHeartbeatAtUtc)}`} tone={metricTone} />
        <MetricCard icon="motion" label="Motion" value={motion.label} detail={motion.detail} tone={motion.tone === 'danger' ? 'warning' : motion.tone === 'neutral' ? 'default' : motion.tone} />
        <MetricCard icon="recording" label="Recording" value={readable(device.recording)} detail={device.monitoring ? 'Monitoring session active' : 'Monitoring is stopped'} />
        <MetricCard icon="battery" label="Battery" value={battery} detail="Reported by device" tone={device.batteryPercent !== null && device.batteryPercent < 20 ? 'warning' : 'default'} />
        <MetricCard icon="storage" label="Available storage" value={formatStorage(device.availableStorageBytes)} detail="Local recording capacity" />
      </section>

      <RecoveryDashboard
        connectionState={device.connectionState}
        health={device.health}
        history={details.recoveryHistory ?? []}
        lastHeartbeatAtUtc={device.lastHeartbeatAtUtc}
      />

      <section className="panel state-panel">
        <div className="panel__heading">
          <div><span className="eyebrow">Activity</span><h2>Recent commands</h2></div>
          <span className="snapshot-version">Snapshot v{device.snapshotVersion ?? '—'}</span>
        </div>
        {recentCommands.length === 0 ? (
          <div className="empty-state">Commands sent from this dashboard will appear here.</div>
        ) : (
          <div className="command-list">
            {recentCommands.slice(0, 8).map((command) => {
              const state = commandStates[command.state] ?? { label: 'Unknown', tone: 'warning' as const }
              return (
                <div className="command-row" key={command.commandId}>
                  <div><strong>{commandNames[command.commandType] ?? 'Device command'}</strong><span>{formatRelative(command.requestedAtUtc)}</span></div>
                  <div className="command-row__result">{command.resultCode && <span>{readable(command.resultCode)}</span>}<StatusBadge tone={state.tone}>{state.label}</StatusBadge></div>
                </div>
              )
            })}
          </div>
        )}
      </section>
    </main>
  )
}
