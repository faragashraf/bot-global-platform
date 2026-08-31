import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { DeviceDetails } from './DeviceDetails'
import type { DeviceMonitoringDetails } from '../models'

const details: DeviceMonitoringDetails = {
  device: {
    deviceId: 'device-1',
    friendlyName: 'Front Entrance',
    platform: 'Android',
    connectionState: 'connected',
    operationalState: 'monitoring',
    monitoring: true,
    motion: 'no_motion',
    recording: 'idle',
    batteryPercent: 82,
    availableStorageBytes: 48_318_382_080,
    lastHeartbeatAtUtc: new Date().toISOString(),
    lastSeenAtUtc: new Date().toISOString(),
    snapshotVersion: 12,
    snapshot: { motionEnabled: true, motionConfiguration: { enabled: true } },
    health: {
      overall: 'healthy',
      subsystems: [{
        subsystem: 'motion', lifecycle: 'running', health: 'healthy', recoveryReason: null,
        reconnectCount: 0, lastFailureAtUtc: null, lastRecoveryAtUtc: null,
        recoveryDurationMilliseconds: null, updatedAtUtc: new Date().toISOString(),
      }],
      reportedAtUtc: new Date().toISOString(),
      presenceState: 'connected',
      isCurrent: true,
      outageStartedAtUtc: null,
      outageCause: null,
    },
  },
  recentCommands: [],
}

describe('device details', () => {
  it('shows live state and only the V1 monitoring controls', () => {
    render(<DeviceDetails details={details} busy={null} onCommand={() => undefined} />)

    expect(screen.getByRole('heading', { name: 'Front Entrance' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Ping' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Stop monitoring' })).toBeVisible()
    expect(screen.queryByRole('button', { name: /record/i })).not.toBeInTheDocument()
  })

  it('uses the current Motion report to show an enabled detector with no activity as Armed', () => {
    render(<DeviceDetails details={details} busy={null} onCommand={() => undefined} />)

    const motionCard = within(screen.getByLabelText('Live device state')).getByText('Motion').closest('.metric-card')
    expect(motionCard).toHaveTextContent('Armed')
    expect(motionCard).toHaveTextContent('Detector running · no Motion detected.')
    expect(motionCard).not.toHaveTextContent('Unavailable')
  })

  it('routes a stop action without duplicating monitoring logic', () => {
    const actions: string[] = []
    render(<DeviceDetails details={details} busy={null} onCommand={(action) => actions.push(action)} />)

    fireEvent.click(screen.getByRole('button', { name: 'Stop monitoring' }))

    expect(actions).toEqual(['stop-monitoring'])
  })

  it('keeps a stopped monitoring device connected and exposes start ping and refresh', () => {
    render(
      <DeviceDetails
        details={{ ...details, device: { ...details.device, monitoring: false } }}
        busy={null}
        onCommand={() => undefined}
      />,
    )

    const hero = screen.getByRole('heading', { name: 'Front Entrance' }).parentElement
    expect(hero).toBeTruthy()
    expect(within(hero as HTMLElement).getByText('Healthy')).toBeVisible()
    expect(screen.getByText('Standby')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Start monitoring' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Ping' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Stop monitoring' })).not.toBeInTheDocument()
  })

  it.each(['light', 'dark'])('renders an offline API payload with semantic critical classes in %s mode', (theme) => {
    document.documentElement.dataset.theme = theme
    const { container } = render(
      <DeviceDetails
        details={{
          ...details,
          device: {
            ...details.device,
            connectionState: 'offline',
            health: {
              ...details.device.health!,
              overall: 'healthy',
              presenceState: 'offline',
              isCurrent: false,
              outageStartedAtUtc: new Date().toISOString(),
              subsystems: [{
                subsystem: 'recording', lifecycle: 'running', health: 'healthy', recoveryReason: null,
                reconnectCount: 0, lastFailureAtUtc: null, lastRecoveryAtUtc: null,
                recoveryDurationMilliseconds: null, updatedAtUtc: new Date().toISOString(),
              }],
            },
          },
        }}
        busy={null}
        onCommand={() => undefined}
      />,
    )

    expect(screen.getByRole('main')).toHaveClass('device-main--offline')
    expect(container.querySelector('.device-hero .badge--danger')).toHaveTextContent('Offline')
    expect(container.querySelector('.recovery-card .badge--neutral')).toHaveTextContent('Unavailable')
    expect(container.querySelector('.recovery-card .badge--positive')).not.toBeInTheDocument()
    expect(container.querySelector('.metric-card--positive')).not.toBeInTheDocument()
    delete document.documentElement.dataset.theme
  })

  it.each(['light', 'dark'])('renders transport loss as amber recovering with animation in %s mode', (theme) => {
    document.documentElement.dataset.theme = theme
    const { container } = render(
      <DeviceDetails
        details={{
          ...details,
          device: {
            ...details.device,
            connectionState: 'recovering',
            health: {
              ...details.device.health!,
              overall: 'recovering', presenceState: 'recovering', isCurrent: false,
              outageStartedAtUtc: new Date().toISOString(), outageCause: 'connection_lost',
            },
          },
        }}
        busy={null}
        onCommand={() => undefined}
      />,
    )

    expect(screen.getByRole('main')).toHaveClass('device-main--recovering')
    expect(screen.getByRole('main')).not.toHaveClass('device-main--offline')
    expect(container.querySelector('.device-hero .badge--warning')).toHaveTextContent('Recovering')
    expect(container.querySelector('.recovery-reconnecting-banner .spinner')).toBeInTheDocument()
    delete document.documentElement.dataset.theme
  })

  it('requires an explicit administrator confirmation and explains retention before removal', async () => {
    const removed: string[] = []
    render(
      <DeviceDetails
        details={details}
        busy={null}
        onCommand={() => undefined}
        loadRemovalImpact={async () => ({
          deviceId: 'device-1',
          deviceName: 'Front Entrance',
          manufacturer: 'Samsung',
          model: 'SM-A217F',
          lastConnectionAtUtc: new Date().toISOString(),
          canRemove: true,
          conflicts: [{ code: 'live_active', message: 'The active Live session will be stopped.', blocksRemoval: false }],
          retention: {
            uploadedRecordingsPreserved: true,
            recordingMetadataPreserved: true,
            auditHistoryPreserved: true,
            historicalEventsPreserved: true,
          },
        })}
        removeDevice={async (_deviceId, deviceName) => ({
          deviceId: 'device-1',
          removedAtUtc: new Date().toISOString(),
          credentialsRevoked: true,
          realtimeDisconnected: true,
          commandsResolved: 0,
          liveSessionReleased: true,
          uploadedRecordingsPreserved: true,
          auditId: 'audit-1',
        })}
        onDeviceRemoved={(result) => removed.push(result.deviceId)}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Remove device' }))
    expect(await screen.findByText('Samsung SM-A217F')).toBeVisible()
    expect(screen.getByText(/Uploaded recordings, recording metadata, audit history/)).toBeVisible()
    const confirm = screen.getByRole('button', { name: 'Remove Front Entrance' })
    expect(confirm).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox'))
    expect(confirm).toBeEnabled()
    fireEvent.click(confirm)

    await waitFor(() => expect(removed).toEqual(['device-1']))
  })
})
