import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { HubConnection } from '@microsoft/signalr'
import { beforeEach, vi } from 'vitest'
import type { LiveMediaSession } from '../live-view'
import { LIVE_WALL_STORAGE_KEY } from '../live-wall'
import type { CameraControlCenterView, CameraControlCommand, CameraControlGroupCommandResult, DeviceLiveState, LiveIceCandidate, LiveSession, LiveSessionDescription, LiveStatistics, LiveViewState } from '../models'
import { LiveViewPage } from './LiveViewPage'

beforeEach(() => localStorage.clear())

describe('Multi-camera Live Wall', () => {
  it('streams two cameras independently and stopping one leaves the other active', async () => {
    const hub = new FakeHubConnection()
    const media = new Map<string, FakeMediaSession>()
    const view = render(<LiveViewPage
      connection={hub as unknown as HubConnection}
      devices={[device('device-1', 'Samsung'), device('device-2', 'Huawei')]}
      createMediaSession={(deviceId) => {
        const session = new FakeMediaSession()
        media.set(deviceId, session)
        return session
      }}
    />)

    const startButtons = await screen.findAllByRole('button', { name: 'Start Live' })
    fireEvent.click(startButtons[0])
    fireEvent.click(startButtons[1])
    await waitFor(() => expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(2))
    media.get('device-1')?.showStream()
    media.get('device-2')?.showStream()
    expect(await screen.findByText('2 streaming · 2 active')).toBeVisible()

    const samsung = screen.getByLabelText('Samsung camera tile')
    fireEvent.click(within(samsung).getByRole('button', { name: 'Stop Live' }))

    await waitFor(() => expect(hub.invocations.filter(([name]) => name === 'CloseLiveSession')).toHaveLength(1))
    expect(media.get('device-1')?.closed).toBe(true)
    expect(media.get('device-2')?.closed).toBe(false)
    expect(within(screen.getByLabelText('Huawei camera tile')).getAllByText('Streaming').length).toBeGreaterThan(0)
    view.unmount()
  })

  it('renders Recovering and Offline from the v0.5 health model without affecting another tile', async () => {
    const hub = new FakeHubConnection()
    const healthySamsung = device('device-1', 'Samsung')
    const healthyHuawei = device('device-2', 'Huawei')
    const view = render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[healthySamsung, healthyHuawei]}/>)

    view.rerender(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung', 'recovering'), healthyHuawei]}/>)
    const samsungRecovering = await screen.findByLabelText('Samsung camera tile')
    expect(samsungRecovering).toHaveClass('live-tile--reconnecting')
    expect(within(samsungRecovering).getAllByText('Reconnecting').length).toBeGreaterThan(0)
    expect(within(screen.getByLabelText('Huawei camera tile')).getAllByText('Healthy').length).toBeGreaterThan(0)

    view.rerender(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung', 'offline'), healthyHuawei]}/>)
    const samsungOffline = await screen.findByLabelText('Samsung camera tile')
    expect(samsungOffline).toHaveClass('live-tile--offline')
    expect(within(samsungOffline).getAllByText('Offline').length).toBeGreaterThan(0)
    expect(within(samsungOffline).getByText('Camera offline')).toBeVisible()
    expect(within(screen.getByLabelText('Huawei camera tile')).queryByText('Camera offline')).not.toBeInTheDocument()
  })

  it('does not mistake Live startup health for a lost control transport', async () => {
    const hub = new FakeHubConnection()
    const media = new FakeMediaSession()
    const createMediaSession = () => media
    const healthy = device('device-1', 'Huawei')
    const view = render(<LiveViewPage
      connection={hub as unknown as HubConnection}
      devices={[healthy]}
      createMediaSession={createMediaSession}
    />)
    fireEvent.click(await screen.findByRole('button', { name: 'Start Live' }))
    await waitFor(() => expect(hub.invocations.some(([name]) => name === 'CreateLiveSession')).toBe(true))
    act(() => media.showStream())

    view.rerender(<LiveViewPage
      connection={hub as unknown as HubConnection}
      devices={[{ ...healthy, health: { ...healthy.health!, overall: 'recovering', presenceState: 'connected', isCurrent: true } }]}
      createMediaSession={createMediaSession}
    />)

    const tile = await screen.findByLabelText('Huawei camera tile')
    expect(tile).toHaveClass('live-tile--streaming')
    expect(within(tile).getByText('Healthy', { selector: '.live-tile-metric strong' })).toBeVisible()
    expect(media.closed).toBe(false)
  })

  it('shows connection-scoped readiness and disables Start Live until ready', async () => {
    const hub = new FakeHubConnection()
    hub.availabilityByDevice.set('device-1', availability('device-1', 'initializing', 'live_capabilities_stale'))
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Huawei')]}/>)

    const tile = await screen.findByLabelText('Huawei camera tile')
    await waitFor(() => expect(within(tile).getByRole('button', { name: 'Start Live' })).toBeDisabled())
    expect(within(tile).getAllByText('Initializing').length).toBeGreaterThan(0)
    expect(within(tile).getByText('Waiting for this connection to confirm camera readiness.')).toBeVisible()
    expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(0)
  })

  it('uses the same current Motion report as Device Details instead of treating NO_MOTION as unavailable', async () => {
    const hub = new FakeHubConnection()
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Huawei')]}/>)

    const tile = await screen.findByLabelText('Huawei camera tile')
    const motion = within(tile).getByText('Motion').closest('.live-tile-metric')
    expect(motion).toHaveTextContent('Armed')
    expect(motion).toHaveAttribute('title', 'Detector running · no Motion detected.')
    expect(motion).not.toHaveTextContent('Unavailable')
  })

  it('enables only contextually valid Live toolbar actions and Stop all', async () => {
    const hub = new FakeHubConnection()
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung')]}/>)
    fireEvent.click(await screen.findByLabelText('Select Samsung'))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Start selected' })).toBeEnabled())
    expect(screen.getByRole('button', { name: 'Stop selected' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop all' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Start selected' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Stop selected' })).toBeEnabled())
    expect(screen.getByRole('button', { name: 'Start selected' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop all' })).toBeEnabled()
  })

  it('shows mixed-selection counts and dispatches Start only to eligible cameras', async () => {
    const hub = new FakeHubConnection()
    render(<LiveViewPage
      connection={hub as unknown as HubConnection}
      devices={[device('device-1', 'Samsung'), device('device-2', 'Huawei')]}
    />)
    const samsung = await screen.findByLabelText('Samsung camera tile')
    await waitFor(() => expect(within(samsung).getByRole('button', { name: 'Start Live' })).toBeEnabled())
    fireEvent.click(within(samsung).getByRole('button', { name: 'Start Live' }))
    await waitFor(() => expect(within(samsung).getByRole('button', { name: 'Stop Live' })).toBeEnabled())
    fireEvent.click(screen.getByLabelText('Select Samsung'))
    fireEvent.click(screen.getByLabelText('Select Huawei'))

    expect(await screen.findByText(/1 of 2 selected can start/)).toBeVisible()
    expect(screen.getByRole('button', { name: 'Start selected' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Stop selected' })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Start selected' }))

    await waitFor(() => expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(2))
  })

  it('filters unsupported recording cameras, reports the skip, and refreshes acknowledged state', async () => {
    const hub = new FakeHubConnection()
    let recordingStarted = false
    const getCameraControl = vi.fn(async (deviceId: string) => cameraControl(
      deviceId,
      deviceId === 'device-1' && recordingStarted ? 'recording' : 'idle',
      deviceId === 'device-1',
    ))
    const sendCameraControlGroup = vi.fn(async (request: { deviceIds: string[] }): Promise<CameraControlGroupCommandResult> => {
      recordingStarted = true
      return {
        correlationId: 'group-1', succeeded: 1, failed: 0,
        items: [{
          deviceId: request.deviceIds[0], succeeded: true,
          command: recordingCommand(request.deviceIds[0], 'Succeeded'), errorCode: null, message: null,
        }],
      }
    })
    render(<LiveViewPage
      connection={hub as unknown as HubConnection}
      devices={[device('device-1', 'Samsung'), device('device-2', 'Huawei')]}
      api={{ getCameraControl, sendCameraControl: vi.fn(), sendCameraControlGroup }}
    />)
    fireEvent.click(await screen.findByLabelText('Select Samsung'))
    fireEvent.click(screen.getByLabelText('Select Huawei'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Record selected' })).toBeEnabled())
    expect(await screen.findByText(/1 of 2 selected can record/)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Record selected' }))

    await waitFor(() => expect(sendCameraControlGroup).toHaveBeenCalledOnce())
    expect(sendCameraControlGroup.mock.calls[0][0].deviceIds).toEqual(['device-1'])
    expect(await screen.findByRole('status')).toHaveTextContent('1 succeeded · 0 failed · 1 skipped')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Stop recording' })).toBeEnabled())
    expect(screen.getByRole('button', { name: 'Record selected' })).toBeDisabled()
  })

  it('locks a pending Start immediately and suppresses duplicate clicks until device acknowledgement', async () => {
    const hub = new FakeHubConnection()
    hub.autoAcknowledge = false
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung')]}/>)
    fireEvent.click(await screen.findByLabelText('Select Samsung'))
    const start = screen.getByRole('button', { name: 'Start selected' })
    await waitFor(() => expect(start).toBeEnabled())

    fireEvent.click(start)
    fireEvent.click(start)

    await waitFor(() => expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(1))
    expect(start).toBeDisabled()
    const [sessionId, deviceId] = [...hub.sessionDevices.entries()][0]
    act(() => hub.emit('LiveSessionChanged', liveSession(deviceId, sessionId, true)))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Stop selected' })).toBeEnabled())
  })

  it.each([1, 2, 4, 6, 9, 16] as const)('supports a %i-camera capacity with placeholders', async (layout) => {
    const hub = new FakeHubConnection()
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung')]}/>)

    fireEvent.click(within(screen.getByRole('group', { name: 'Grid capacity' })).getByRole('button', { name: String(layout) }))

    const grid = await screen.findByLabelText(`${layout}-camera Live Wall`)
    expect(grid).toHaveAttribute('data-layout', String(layout))
    expect(within(grid).getAllByRole('article')).toHaveLength(layout)
  })

  it('restores camera order but never restarts saved streams', async () => {
    localStorage.setItem(LIVE_WALL_STORAGE_KEY, JSON.stringify({
      version: 1,
      size: 4,
      deviceIds: ['device-2', 'device-1', null, null],
      selectedDeviceIds: ['device-2'],
      lastActiveDeviceId: 'device-2',
    }))
    const hub = new FakeHubConnection()
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung'), device('device-2', 'Huawei')]} createMediaSession={() => new FakeMediaSession()}/>)

    const tiles = within(await screen.findByLabelText('4-camera Live Wall')).getAllByRole('article')
    expect(tiles[0]).toHaveAccessibleName('Huawei camera tile')
    expect(tiles[1]).toHaveAccessibleName('Samsung camera tile')
    expect(screen.getByLabelText('Select Huawei')).toBeChecked()
    expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(0)
  })

  it('enters fullscreen and exposes an explicit accessible exit action', async () => {
    const requestFullscreen = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, value: requestFullscreen })
    const hub = new FakeHubConnection()
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung')]}/>)

    fireEvent.click(await screen.findByRole('button', { name: 'Fullscreen Samsung' }))

    expect(requestFullscreen).toHaveBeenCalledOnce()
    expect(screen.getByLabelText('Samsung camera tile')).toHaveClass('live-tile--fullscreen')
    expect(screen.getByRole('button', { name: 'Exit fullscreen' })).toBeVisible()
  })

  it('reports partial group success without rolling back successful cameras', async () => {
    const hub = new FakeHubConnection(new Set(['device-2']))
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung'), device('device-2', 'Huawei')]} createMediaSession={() => new FakeMediaSession()}/>)
    fireEvent.click(await screen.findByLabelText('Select Samsung'))
    fireEvent.click(screen.getByLabelText('Select Huawei'))

    fireEvent.click(screen.getByRole('button', { name: 'Start selected' }))

    const notice = (await screen.findByText('1 succeeded · 1 failed · 0 skipped')).closest('.live-wall-notice')!
    expect(notice).toHaveTextContent('1 succeeded · 1 failed · 0 skipped')
    expect(notice).toHaveTextContent('Huawei')
    expect(hub.invocations.filter(([name]) => name === 'CloseLiveSession')).toHaveLength(0)
  })

  it('uses the existing camera command contract for partial recording actions', async () => {
    const hub = new FakeHubConnection()
    const sendCameraControl = vi.fn(async (deviceId: string, _request: unknown) => {
      if (deviceId === 'device-2') throw new Error('recording_owner_conflict')
      return recordingCommand(deviceId, 'Succeeded')
    })
    render(<LiveViewPage
      connection={hub as unknown as HubConnection}
      devices={[device('device-1', 'Samsung'), device('device-2', 'Huawei')]}
      api={{ sendCameraControl, getCameraControl: async (deviceId) => cameraControl(deviceId) }}
    />)
    fireEvent.click(await screen.findByLabelText('Select Samsung'))
    fireEvent.click(screen.getByLabelText('Select Huawei'))

    fireEvent.click(screen.getByRole('button', { name: 'Record selected' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('1 succeeded · 1 failed · 0 skipped')
    expect(sendCameraControl).toHaveBeenCalledTimes(2)
    expect(sendCameraControl.mock.calls[0][1]).toMatchObject({ control: 'recording', value: { text: 'start' } })
  })

  it('supports keyboard selection and explicit Light/Dark mode labels', async () => {
    const hub = new FakeHubConnection()
    render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung')]}/>)
    const tile = await screen.findByLabelText('Samsung camera tile')

    fireEvent.keyDown(tile, { key: 'Enter' })
    expect(screen.getByLabelText('Select Samsung')).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Use light mode' }))
    expect(screen.getByRole('button', { name: 'Use dark mode' })).toBeVisible()
    expect(screen.getByRole('main')).toHaveAttribute('data-theme', 'light')
  })

  it('closes every active Hub session when the operator leaves the wall', async () => {
    const hub = new FakeHubConnection()
    const view = render(<LiveViewPage connection={hub as unknown as HubConnection} devices={[device('device-1', 'Samsung'), device('device-2', 'Huawei')]} createMediaSession={() => new FakeMediaSession()}/>)
    const starts = await screen.findAllByRole('button', { name: 'Start Live' })
    fireEvent.click(starts[0])
    fireEvent.click(starts[1])
    await waitFor(() => expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(2))

    view.unmount()

    await waitFor(() => expect(hub.invocations.filter(([name]) => name === 'CloseLiveSession')).toHaveLength(2))
  })
})

class FakeHubConnection {
  invocations: [string, ...unknown[]][] = []
  handlers = new Map<string, ((value: never) => void)[]>()
  sessionDevices = new Map<string, string>()
  private sequence = 0
  availabilityByDevice = new Map<string, ReturnType<typeof availability>>()
  autoAcknowledge = true

  constructor(private readonly createFailures = new Set<string>()) {}

  async invoke<T>(name: string, ...args: unknown[]): Promise<T> {
    this.invocations.push([name, ...args])
    if (name === 'GetLiveAvailability') {
      const deviceId = String(args[0])
      return (this.availabilityByDevice.get(deviceId) ?? availability(deviceId)) as T
    }
    if (name === 'CreateLiveSession') {
      const request = args[0] as { deviceId: string }
      if (this.createFailures.has(request.deviceId)) throw new Error('session_conflict:Camera already in use')
      const sessionId = `session-${++this.sequence}`
      this.sessionDevices.set(sessionId, request.deviceId)
      return liveSession(request.deviceId, sessionId) as T
    }
    if (name === 'SendLiveOffer' && this.autoAcknowledge) {
      const offer = args[0] as LiveSessionDescription
      const deviceId = this.sessionDevices.get(offer.sessionId) ?? 'device-1'
      queueMicrotask(() => this.emit('LiveSessionChanged', liveSession(deviceId, offer.sessionId, true)))
    }
    return undefined as T
  }

  on(name: string, handler: (value: never) => void) { this.handlers.set(name, [...this.handlers.get(name) ?? [], handler]) }
  off(name: string, handler: (value: never) => void) { this.handlers.set(name, (this.handlers.get(name) ?? []).filter((value) => value !== handler)) }
  emit(name: string, value: unknown) { this.handlers.get(name)?.forEach((handler) => handler(value as never)) }
}

class FakeMediaSession implements LiveMediaSession {
  closed = false
  private stream: ((stream: MediaStream) => void) | null = null
  private state: ((state: LiveViewState) => void) | null = null

  async start(
    sessionId: string,
    onOffer: (offer: LiveSessionDescription) => Promise<void>,
    _onCandidate: (candidate: LiveIceCandidate) => Promise<void>,
    onState: (state: LiveViewState) => void,
    onStream: (stream: MediaStream) => void,
  ) {
    this.state = onState
    this.stream = onStream
    await onOffer({ sessionId, type: 'offer', sdp: 'v=0 browser' })
  }

  showStream() { this.stream?.({} as MediaStream); this.state?.('connected') }
  async acceptAnswer(_answer: LiveSessionDescription) { return undefined }
  async addIceCandidate(_candidate: LiveIceCandidate) { return undefined }
  async statistics(): Promise<LiveStatistics | null> { return null }
  close() { this.closed = true }
}

function liveSession(deviceId: string, sessionId: string, deviceAcknowledged = false): LiveSession {
  const now = new Date().toISOString()
  return {
    sessionId, deviceId, state: 'connecting', quality: 'medium', createdAtUtc: now,
    lastActivityAtUtc: now, expiresAtUtc: now, errorCode: null, deviceAcknowledged,
  }
}

function cameraControl(deviceId: string, recordingState = 'idle', supported = true): CameraControlCenterView {
  const now = new Date().toISOString()
  return {
    deviceId,
    online: true,
    desiredSettings: {
      lens: 'back', zoom: 1, torch: false, exposureCompensation: 0, preview: 'visible',
      framesPerSecond: 30, resolution: '1280x720', bitrate: 2_500_000, quality: 'medium', nightProfile: 'auto',
    },
    deviceState: {
      deviceId,
      settings: {
        lens: 'back', zoom: 1, torch: false, exposureCompensation: 0, preview: 'visible',
        framesPerSecond: 30, resolution: '1280x720', bitrate: 2_500_000, quality: 'medium', nightProfile: 'auto',
      },
      capabilities: [{
        id: 'recording', supported, writable: supported,
        currentValue: { boolean: null, number: null, text: recordingState },
        minimum: null, maximum: null, step: null, allowedValues: ['start', 'stop'], unit: null,
        reason: supported ? null : 'Recording is unsupported.',
      }],
      telemetry: {
        cameraOnline: true, streaming: false, recording: recordingState === 'recording', recordingState,
        recordingOrigin: 'remote', motionArmed: true, batteryPercent: 80, temperatureCelsius: 31,
        availableStorageBytes: 1024, charging: false, connectionQuality: 'good', previewAvailable: true,
        audioAvailable: false, latestRecordingUpload: null,
      },
      reportedAtUtc: now,
      motionSettings: null,
    },
    recentCommands: [],
    updatedAtUtc: now,
  }
}

function recordingCommand(deviceId: string, state: CameraControlCommand['state']): CameraControlCommand {
  const now = new Date().toISOString()
  return {
    commandId: `command-${deviceId}`, deviceId, control: 'recording',
    value: { boolean: null, number: null, text: 'start' }, state, attempts: 1,
    cancelable: false, retriable: false, correlationId: `recording-${deviceId}`, actorId: 'operator',
    resultCode: state === 'Succeeded' ? 'recording_started' : null, createdAtUtc: now,
    completedAtUtc: state === 'Succeeded' ? now : null, expectedVersion: null,
  }
}

function availability(
  deviceId: string,
  readiness: 'unavailable' | 'initializing' | 'ready' | 'starting' | 'live' | 'recovering' | 'failed' = 'ready',
  reason: string | null = null,
) {
  return {
    deviceId, available: readiness === 'ready', unavailableReason: reason, capabilities: null,
    qualityOptions: [{ id: 'medium', label: 'Medium', available: true, width: 1280, height: 720, framesPerSecond: 30 }],
    readiness,
    readinessReportedAtUtc: new Date().toISOString(),
  }
}

function device(deviceId: string, name: string, state: DeviceLiveState['connectionState'] = 'connected'): DeviceLiveState {
  const current = state === 'connected'
  return {
    deviceId, friendlyName: name, platform: 'Android', connectionState: state,
    operationalState: current ? 'active' : state, monitoring: true, motion: 'NO_MOTION', recording: 'idle', batteryPercent: 80,
    availableStorageBytes: 1024, lastHeartbeatAtUtc: new Date().toISOString(), lastSeenAtUtc: new Date().toISOString(),
    snapshotVersion: 1, snapshot: { motionEnabled: true, motionConfiguration: { enabled: true } },
    health: {
      overall: current ? 'healthy' : state, subsystems: [{
        subsystem: 'motion', lifecycle: 'running', health: 'healthy', recoveryReason: null,
        reconnectCount: 0, lastFailureAtUtc: null, lastRecoveryAtUtc: null,
        recoveryDurationMilliseconds: null, updatedAtUtc: new Date().toISOString(),
      }], reportedAtUtc: new Date().toISOString(),
      presenceState: current ? 'connected' : state, isCurrent: current,
      outageStartedAtUtc: current ? null : new Date().toISOString(), outageCause: current ? null : 'network',
    },
  }
}
