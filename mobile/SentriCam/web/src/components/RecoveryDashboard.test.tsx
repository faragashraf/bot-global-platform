import { render, screen } from '@testing-library/react'
import { RecoveryDashboard } from './RecoveryDashboard'

test('renders independent subsystem health and recovery evidence', () => {
  const now = new Date(Date.now() - 1_000).toISOString()
  const stale = new Date(Date.now() - 10_000).toISOString()
  render(<RecoveryDashboard
    connectionState="connected"
    lastHeartbeatAtUtc={stale}
    history={[{
      subsystem: 'realtime', lifecycle: 'running', health: 'healthy', recoveryReason: null,
      occurredAtUtc: stale, snapshotVersion: 7,
    }]}
    health={{
      overall: 'recovering', reportedAtUtc: now,
      presenceState: 'connected', isCurrent: true, outageStartedAtUtc: null, outageCause: null,
      subsystems: [
        {
          subsystem: 'realtime', lifecycle: 'recovering', health: 'recovering',
          recoveryReason: 'network_offline', reconnectCount: 3, lastFailureAtUtc: stale, lastRecoveryAtUtc: null,
          recoveryDurationMilliseconds: null, updatedAtUtc: now,
        },
        {
          subsystem: 'recording', lifecycle: 'running', health: 'healthy',
          recoveryReason: null, reconnectCount: 0, lastFailureAtUtc: null, lastRecoveryAtUtc: null,
          recoveryDurationMilliseconds: null, updatedAtUtc: now,
        },
      ],
    }}
  />)

  expect(screen.getByText('Operations')).toBeInTheDocument()
  expect(screen.getByText('Device recovering')).toBeInTheDocument()
  expect(screen.getAllByText('Realtime')).toHaveLength(2)
  expect(screen.getByText('Recording')).toBeInTheDocument()
  expect(screen.getByText('Network Offline')).toBeInTheDocument()
  expect(screen.getByText('Total reconnects').nextElementSibling).toHaveTextContent('3')
})

test('shows offline banner with cause and duration', () => {
  const at = new Date('2026-08-02T10:00:00Z').toISOString()
  render(<RecoveryDashboard
    connectionState="offline"
    lastHeartbeatAtUtc="2026-08-02T09:00:00Z"
    history={[]}
    health={{
      overall: 'offline', reportedAtUtc: at, presenceState: 'offline', isCurrent: false, outageStartedAtUtc: '2026-08-02T09:05:00Z',
      outageCause: 'heartbeat_expired',
      subsystems: [{
        subsystem: 'realtime', lifecycle: 'unavailable', health: 'unknown',
        recoveryReason: 'heartbeat_expired', reconnectCount: 0, lastFailureAtUtc: '2026-08-02T09:05:00Z', lastRecoveryAtUtc: null,
        recoveryDurationMilliseconds: null, updatedAtUtc: null,
      }],
    }}
  />)

  expect(screen.getByText('Device offline')).toBeInTheDocument()
  expect(screen.getByText('Heartbeat expired')).toBeInTheDocument()
})

test('shows an animated reconnect state immediately after transport loss', () => {
  const now = new Date().toISOString()
  const { container } = render(<RecoveryDashboard
    connectionState="recovering"
    lastHeartbeatAtUtc={now}
    history={[]}
    health={{
      overall: 'recovering', reportedAtUtc: now, presenceState: 'recovering', isCurrent: false,
      outageStartedAtUtc: now, outageCause: 'connection_lost',
      subsystems: [{
        subsystem: 'realtime', lifecycle: 'recovering', health: 'recovering',
        recoveryReason: 'connection_lost', reconnectCount: 1, lastFailureAtUtc: now,
        lastRecoveryAtUtc: null, recoveryDurationMilliseconds: null, updatedAtUtc: now,
      }],
    }}
  />)

  expect(screen.getByText('Connection lost')).toBeInTheDocument()
  expect(screen.getByText('Reconnecting…')).toBeInTheDocument()
  expect(screen.getByText(/Current reconnect attempt/)).toBeInTheDocument()
  expect(container.querySelector('.recovery-reconnecting-banner .spinner')).toBeInTheDocument()
  expect(screen.queryByText('Offline')).not.toBeInTheDocument()
})

test('uses actionable product wording for device intervention', () => {
  const now = new Date().toISOString()
  render(<RecoveryDashboard
    connectionState="connected"
    lastHeartbeatAtUtc={null}
    history={[]}
    health={{
      overall: 'action_required', reportedAtUtc: now,
      presenceState: 'connected', isCurrent: true, outageStartedAtUtc: null, outageCause: null,
      subsystems: [{
        subsystem: 'camera', lifecycle: 'action_required', health: 'action_required',
        recoveryReason: 'camera_permission_missing', reconnectCount: 0,
        lastFailureAtUtc: now, lastRecoveryAtUtc: null,
        recoveryDurationMilliseconds: null, updatedAtUtc: now,
      }],
    }}
  />)

  expect(screen.getByText('Action required on device')).toBeInTheDocument()
  expect(screen.getByText('Camera Permission Missing')).toBeInTheDocument()
})

test('downgrades healthy-looking subsystems while offline', () => {
  const now = new Date().toISOString()
  render(<RecoveryDashboard
    connectionState="offline"
    lastHeartbeatAtUtc={now}
    history={[]}
    health={{
      overall: 'healthy', reportedAtUtc: now, presenceState: 'offline',
      isCurrent: false,
      outageStartedAtUtc: '2026-08-02T10:00:00Z',
      outageCause: 'heartbeat_expired',
      subsystems: [{
        subsystem: 'recording',
        lifecycle: 'running',
        health: 'healthy',
        recoveryReason: null,
        reconnectCount: 0,
        lastFailureAtUtc: null,
        lastRecoveryAtUtc: null,
        recoveryDurationMilliseconds: null,
        updatedAtUtc: now,
      }],
    }}
  />)

  expect(screen.getAllByText('Unavailable').find((element) => element.classList.contains('badge'))).toHaveClass('badge--neutral')
  expect(screen.queryByText('Healthy')).not.toBeInTheDocument()
})
