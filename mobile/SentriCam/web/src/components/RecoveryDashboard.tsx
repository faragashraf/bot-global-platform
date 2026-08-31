import { useEffect, useState } from 'react'
import { formatRelative, readable } from '../format'
import type { DeviceOperationalHealth, RecoveryHistoryEntry } from '../models'
import { StatusBadge } from './StatusBadge'
import { getEffectiveConnectionState } from '../monitoringHealth'

const labels: Record<string, string> = {
  realtime: 'Realtime',
  live: 'Live',
  recording: 'Recording',
  upload: 'Upload',
  motion: 'Motion',
  camera: 'Camera',
  battery: 'Battery',
  storage: 'Storage',
  command_queue: 'Command queue',
  commandQueue: 'Command queue',
}

const tone = (health: string): 'positive' | 'warning' | 'danger' | 'neutral' => {
  if (health === 'healthy') return 'positive'
  if (health === 'action_required' || health === 'offline' || health === 'failed') return 'danger'
  if (health === 'recovering' || health === 'degraded') return 'warning'
  return 'neutral'
}

const severity = (health: string): 'green' | 'amber' | 'red' | 'gray' => {
  if (health === 'healthy') return 'green'
  if (health === 'recovering' || health === 'degraded') return 'amber'
  if (health === 'action_required' || health === 'offline' || health === 'failed') return 'red'
  return 'gray'
}

const duration = (milliseconds: number | null) => {
  if (milliseconds === null) return '—'
  if (milliseconds < 1_000) return `${milliseconds} ms`
  if (milliseconds < 60_000) return `${Math.round(milliseconds / 1_000)} s`
  if (milliseconds < 3_600_000) return `${Math.round(milliseconds / 60_000)} min`
  return `${Math.round(milliseconds / 3_600_000)} h`
}

const healthLabel = (health: string) => health === 'action_required'
  ? 'Action required on device'
  : readable(health)

const asDuration = (now: number, startedAt: string | null) => {
  if (!startedAt) return '—'
  return duration(now - new Date(startedAt).getTime())
}

const offlineReasonLabel = (reason: string | null) => {
  if (reason === 'heartbeat_expired') return 'Heartbeat expired'
  if (reason === 'connection_lost') return 'Connection lost'
  return 'Unknown'
}

export function RecoveryDashboard({ connectionState, health, history, lastHeartbeatAtUtc }: {
  connectionState: 'connected' | 'recovering' | 'offline'
  health?: DeviceOperationalHealth
  history: RecoveryHistoryEntry[]
  lastHeartbeatAtUtc: string | null
}) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

  const overall = health?.overall ?? 'unknown'
  const effectiveState = getEffectiveConnectionState({
    connectionState,
    health: health ?? {
      overall: 'unknown',
      subsystems: [],
      reportedAtUtc: null,
      presenceState: null,
      isCurrent: false,
      outageStartedAtUtc: null,
      outageCause: null,
    },
    lastHeartbeatAtUtc,
  })
  const isOffline = effectiveState.isOffline
  const isRecovering = effectiveState.isRecovering
  const isCritical = isOffline
  const summaryClass = isCritical
    ? 'recovery-dashboard__summary--critical'
    : isRecovering ? 'recovery-dashboard__summary--recovering' : 'recovery-dashboard__summary--default'
  const outageLabel = isOffline ? offlineReasonLabel(health?.outageCause ?? null) : '—'
  const subsystems = health?.subsystems ?? []
  const effectiveSubsystems = subsystems.map((item) => {
    if (isOffline) {
      return {
        ...item,
        lifecycle: 'unavailable',
        health: 'unavailable',
        recoveryReason: item.recoveryReason ?? 'connection_lost',
      }
    }
    if (isRecovering) {
      return {
        ...item,
        lifecycle: item.lifecycle === 'unavailable' ? 'unavailable' : 'recovering',
        health: item.health === 'offline' ? 'unavailable' : 'recovering',
        recoveryReason: item.recoveryReason ?? 'waiting_for_health_report',
      }
    }
    return item
  })
  const title = isOffline ? 'Device offline' : isRecovering ? 'Device recovering' : 'Operations'
  const panelClass = isCritical
    ? 'panel recovery-dashboard recovery-dashboard--critical'
    : 'panel recovery-dashboard'

  return (
    <section className={panelClass} aria-labelledby="recovery-dashboard-title">
      <div className="panel__heading recovery-dashboard__heading">
        <div>
          <span className="eyebrow">Operations</span>
          <h2 id="recovery-dashboard-title">{title}</h2>
        </div>
        <StatusBadge tone={effectiveState.tone} pulse={effectiveState.pulse}>{effectiveState.label}</StatusBadge>
      </div>
      <div className={`recovery-dashboard__summary ${summaryClass}`}>
        <span><small>Last heartbeat</small><strong>{formatRelative(lastHeartbeatAtUtc)}</strong></span>
        <span><small>Health reported</small><strong>{formatRelative(health?.reportedAtUtc ?? null)}</strong></span>
        <span><small>Total reconnects</small><strong>{subsystems.reduce((total, item) => total + item.reconnectCount, 0)}</strong></span>
      </div>
      {isOffline ? (
        <div className="recovery-offline-banner" role="status" aria-live="polite">
          <div>
            <strong>Offline</strong>
            <p>{outageLabel}</p>
          </div>
          <span className="recovery-offline-banner__time">Since {formatRelative(health?.outageStartedAtUtc ?? null)} · {asDuration(now, health?.outageStartedAtUtc ?? null)}</span>
        </div>
      ) : isRecovering ? (
        <div className="recovery-reconnecting-banner" role="status" aria-live="polite">
          <span className="spinner" aria-hidden="true" />
          <div>
            <strong>Connection lost</strong>
            <p>Reconnecting…</p>
          </div>
          <span className="recovery-reconnecting-banner__time">Current reconnect attempt · Since {formatRelative(health?.outageStartedAtUtc ?? null)} · {asDuration(now, health?.outageStartedAtUtc ?? null)}</span>
        </div>
      ) : null}
      <div className={`recovery-dashboard__summary ${summaryClass}`}>
        <span><small>Subsystem state</small><strong>{isOffline ? 'Offline' : isRecovering ? 'Recovering' : healthLabel(overall)}</strong></span>
        <span><small>Last known health</small><strong>{formatRelative(health?.reportedAtUtc ?? null)}</strong></span>
        <span><small>Current freshness</small><strong>{health?.isCurrent ? 'Fresh' : 'Stale'}</strong></span>
      </div>
      {effectiveSubsystems.length === 0 ? <p className="recovery-dashboard__empty">Waiting for the camera’s first v0.5 health report.</p> : (
        <div className="recovery-grid">
          {effectiveSubsystems.map((item) => (
            <article className="recovery-card" key={item.subsystem}>
              <header><strong>{labels[item.subsystem] ?? readable(item.subsystem)}</strong><StatusBadge tone={tone(item.health)}>{healthLabel(item.health)} {severity(item.health) === 'red' ? '⚠' : ''}</StatusBadge></header>
              <dl>
                <div><dt>State</dt><dd>{readable(item.lifecycle)}</dd></div>
                <div><dt>Reconnects</dt><dd>{item.reconnectCount}</dd></div>
                <div><dt>Last failure</dt><dd>{formatRelative(item.lastFailureAtUtc)}</dd></div>
                <div><dt>Last recovery</dt><dd>{formatRelative(item.lastRecoveryAtUtc)}</dd></div>
                <div><dt>Recovery time</dt><dd>{duration(item.recoveryDurationMilliseconds)}</dd></div>
              </dl>
              {item.recoveryReason && <p>{readable(item.recoveryReason)}</p>}
            </article>
          ))}
        </div>
      )}
      <details className="recovery-history">
        <summary>Health history <span>{history.length}</span></summary>
        {history.length === 0 ? <p>No health transitions have been persisted yet.</p> : (
          <ol>{history.slice(0, 20).map((entry) => (
            <li key={`${entry.snapshotVersion}-${entry.subsystem}`}>
              <span><strong>{labels[entry.subsystem] ?? readable(entry.subsystem)}</strong><small>{readable(entry.lifecycle)}{entry.recoveryReason ? ` · ${readable(entry.recoveryReason)}` : ''}</small></span>
              <span><StatusBadge tone={tone(entry.health)}>{healthLabel(entry.health)}</StatusBadge><time>{formatRelative(entry.occurredAtUtc)}</time></span>
            </li>
          ))}</ol>
        )}
      </details>
    </section>
  )
}
