import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { DeviceLiveState, HubSetupState } from './models'

const realtimeHarness = vi.hoisted(() => ({
  onDeviceChanged: undefined as ((deviceId: string) => void) | undefined,
}))

vi.mock('./realtime', () => ({
  OPERATIONAL_FALLBACK_POLL_MILLIS: 5_000,
  createMonitoringConnection: vi.fn((_accessToken, _onState, onDeviceChanged) => {
    realtimeHarness.onDeviceChanged = onDeviceChanged
    return ({
    start: vi.fn(() => Promise.resolve()),
    stop: vi.fn(() => Promise.resolve()),
    invoke: vi.fn((_methodName: string, deviceId: string) => Promise.resolve({
      deviceId,
      available: true,
      unavailableReason: null,
      capabilities: null,
      qualityOptions: [{ id: 'medium', label: 'Medium', available: true, width: 1280, height: 720, framesPerSecond: 30 }],
      readiness: 'ready',
      readinessReportedAtUtc: new Date().toISOString(),
    })),
    on: vi.fn(),
    onreconnecting: vi.fn(),
    onreconnected: vi.fn(),
    onclose: vi.fn(),
    })
  }),
}))

import App from './App'

describe('Local Hub experience', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState(null, '', '/')
    realtimeHarness.onDeviceChanged = undefined
    vi.restoreAllMocks()
  })

  it('opens the first-run wizard and restores its saved step', async () => {
    setFetchMock((url) => {
      if (url.endsWith('/api/v1/hub/setup')) return json(setupState(false, 'recording-folder'))
      return new Response(null, { status: 404 })
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: /Choose where recordings live/i })).toBeVisible()
    expect(screen.queryByText('SQLite')).not.toBeInTheDocument()
    expect(screen.queryByText(/connection string/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/JWT/i)).not.toBeInTheDocument()
  })

  it('routes NotStarted to the welcome wizard even when a stale boolean says configured', async () => {
    setFetchMock((url) => {
      if (url.endsWith('/api/v1/hub/setup')) {
        return json(setupState(true, 'welcome', 'NotStarted', null))
      }
      return new Response(null, { status: 404 })
    })

    render(<App />)

    expect(await screen.findByRole('heading', { name: /Your home, watched over/i })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Hub Ready' })).not.toBeInTheDocument()
  })

  it('routes Failed setup to the saved repair flow', async () => {
    setFetchMock((url) => {
      if (url.endsWith('/api/v1/hub/setup')) {
        return json(setupState(false, 'configure', 'Failed'))
      }
      return new Response(null, { status: 404 })
    })

    render(<App />)

    expect(await screen.findByText('Setup did not finish')).toBeVisible()
    expect(screen.getByRole('heading', { name: /Everything looks good/i })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Hub Ready' })).not.toBeInTheDocument()
  })

  it('routes NeedsRepair setup to repair instead of Hub Ready', async () => {
    setFetchMock((url) => {
      if (url.endsWith('/api/v1/hub/setup')) {
        return json(setupState(false, 'summary', 'NeedsRepair'))
      }
      return new Response(null, { status: 404 })
    })

    render(<App />)

    expect(await screen.findByText('Hub setup needs repair')).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Hub Ready' })).not.toBeInTheDocument()
  })

  it('rejects an inconsistent Completed state without configuredAtUtc', async () => {
    setFetchMock((url) => {
      if (url.endsWith('/api/v1/hub/setup')) {
        return json(setupState(true, 'ready', 'Completed', null))
      }
      return new Response(null, { status: 404 })
    })

    render(<App />)

    expect(await screen.findByText('Hub setup needs repair')).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Hub Ready' })).not.toBeInTheDocument()
  })

  it('keeps technical database and network choices out of the Home journey', async () => {
    setFetchMock((url, init) => {
      if (url.endsWith('/api/v1/hub/setup') && !init?.method) return json(setupState(false, 'welcome'))
      if (url.endsWith('/api/v1/hub/setup/draft')) return json(setupState(false, 'mode'))
      return new Response(null, { status: 404 })
    })
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: /Get started/i }))
    expect(await screen.findByRole('button', { pressed: true })).toHaveTextContent('Home')
    fireEvent.click(screen.getByRole('button', { name: /Continue/i }))

    expect(await screen.findByRole('heading', { name: /Name your Hub/i })).toBeVisible()
    expect(screen.queryByText('SQLite')).not.toBeInTheDocument()
    expect(screen.queryByText(/Network port/i)).not.toBeInTheDocument()
  })

  it('creates a hidden local operator session and opens the Hub overview', async () => {
    setFetchMock(productFetch())
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Hub Ready' })).toBeVisible()
    expect(await screen.findByText('Scan to pair')).toBeVisible()
    expect(sessionStorage.getItem('sentricam-operator-token')).toBe('local-session-token')
    expect(screen.queryByText('local-session-token')).not.toBeInTheDocument()
    expect(screen.queryByText(/operator token/i)).not.toBeInTheDocument()
    expect(localStorage.length).toBe(0)
  })

  it('reuses a browser-tab session without requesting a replacement', async () => {
    sessionStorage.setItem('sentricam-operator-token', 'stored-token')
    const calls: string[] = []
    setFetchMock((url, init) => {
      calls.push(url)
      return productFetch()(url, init)
    })
    render(<App />)

    expect(await screen.findByRole('button', { name: 'Sign out' })).toBeVisible()
    expect(calls.some((url) => url.endsWith('/api/v1/hub/session'))).toBe(false)
    expect(screen.queryByText('stored-token')).not.toBeInTheDocument()
  })

  it('opens the recordings archive from shared Hub navigation', async () => {
    sessionStorage.setItem('sentricam-operator-token', 'stored-token')
    setFetchMock(productFetch())
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Recordings' }))

    expect(await screen.findByRole('heading', { name: 'Recordings Library' })).toBeVisible()
    expect(screen.getByText(/New completed uploads appear here automatically/i)).toBeVisible()
  })

  it('replaces stale green detail state when DeviceChanged refreshes the same deviceId offline', async () => {
    sessionStorage.setItem('sentricam-operator-token', 'stored-token')
    window.history.replaceState(null, '', '/?view=devices')
    let fleetDevice = healthyDevice()
    const staleHealthyDetail = healthyDevice()
    setFetchMock(monitoringFetch(
      () => [fleetDevice],
      () => ({ device: staleHealthyDetail, recentCommands: [], recoveryHistory: [] }),
    ))

    render(<App />)

    const heading = await screen.findByRole('heading', { name: 'HUAWEI' })
    expect(within(heading.parentElement as HTMLElement).getByText('Healthy')).toHaveClass('badge--positive')
    await waitFor(() => expect(realtimeHarness.onDeviceChanged).toBeTypeOf('function'))

    fleetDevice = offlineDevice()
    act(() => realtimeHarness.onDeviceChanged?.('huawei-device-id'))

    await waitFor(() => expect(screen.getByRole('main')).toHaveClass('device-main--offline'))
    expect(within(heading.parentElement as HTMLElement).getByText('Offline')).toHaveClass('badge--danger')
  })

  it('renders DeviceChanged transport loss as recovering and returns healthy without offline', async () => {
    sessionStorage.setItem('sentricam-operator-token', 'stored-token')
    window.history.replaceState(null, '', '/?view=devices')
    let fleetDevice = healthyDevice()
    setFetchMock(monitoringFetch(
      () => [fleetDevice],
      () => ({ device: fleetDevice, recentCommands: [], recoveryHistory: [] }),
    ))

    render(<App />)
    expect(await screen.findByText('Healthy')).toHaveClass('badge--positive')
    await waitFor(() => expect(realtimeHarness.onDeviceChanged).toBeTypeOf('function'))

    fleetDevice = recoveringDevice()
    act(() => realtimeHarness.onDeviceChanged?.('huawei-device-id'))

    await waitFor(() => expect(screen.getByRole('main')).toHaveClass('device-main--recovering'))
    expect(screen.getByText('Connection lost')).toBeVisible()
    expect(screen.queryByRole('main', { name: /offline/i })).not.toBeInTheDocument()

    fleetDevice = healthyDevice()
    act(() => realtimeHarness.onDeviceChanged?.('huawei-device-id'))

    await waitFor(() => expect(screen.getByRole('main')).not.toHaveClass('device-main--recovering'))
    expect(screen.getAllByText('Healthy').some((element) => element.classList.contains('badge--positive'))).toBe(true)
    expect(screen.queryByText('Device offline')).not.toBeInTheDocument()
  })

  it('updates the visible card when fallback polling receives an offline fleet snapshot', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    sessionStorage.setItem('sentricam-operator-token', 'stored-token')
    window.history.replaceState(null, '', '/?view=devices')
    let fleetDevice = healthyDevice()
    const staleHealthyDetail = healthyDevice()
    setFetchMock(monitoringFetch(
      () => [fleetDevice],
      () => ({ device: staleHealthyDetail, recentCommands: [], recoveryHistory: [] }),
    ))

    render(<App />)
    expect(await screen.findByText('Healthy')).toHaveClass('badge--positive')

    fleetDevice = offlineDevice()
    await act(async () => vi.advanceTimersByTimeAsync(5_000))

    await waitFor(() => expect(screen.getByRole('main')).toHaveClass('device-main--offline'))
    expect(screen.getAllByText('Offline').some((element) => element.classList.contains('badge--danger'))).toBe(true)
    vi.useRealTimers()
  })
})

function monitoringFetch(
  listDevices: () => DeviceLiveState[],
  getDetails: () => { device: DeviceLiveState; recentCommands: []; recoveryHistory: [] },
) {
  return (url: string, _init?: RequestInit) => {
    if (url.endsWith('/api/v1/hub/setup')) return json(setupState(true, 'ready'))
    if (url.endsWith('/api/v1/monitoring/devices/huawei-device-id')) return json(getDetails())
    if (url.endsWith('/api/v1/monitoring/devices')) return json(listDevices())
    return new Response(null, { status: 404 })
  }
}

function healthyDevice(): DeviceLiveState {
  const now = new Date().toISOString()
  return {
    deviceId: 'huawei-device-id', friendlyName: 'HUAWEI', platform: 'Android',
    connectionState: 'connected', operationalState: 'monitoring', monitoring: true,
    motion: 'idle', recording: 'idle', batteryPercent: 100, availableStorageBytes: 1_000,
    lastHeartbeatAtUtc: now, lastSeenAtUtc: now, snapshotVersion: 1, snapshot: null,
    health: {
      overall: 'healthy', subsystems: [], reportedAtUtc: now, presenceState: 'connected',
      isCurrent: true, outageStartedAtUtc: null, outageCause: null,
    },
  }
}

function offlineDevice(): DeviceLiveState {
  const staleHealthy = healthyDevice()
  return {
    ...staleHealthy,
    connectionState: 'offline',
    health: {
      ...staleHealthy.health!, overall: 'offline', presenceState: 'offline', isCurrent: false,
      outageStartedAtUtc: new Date().toISOString(), outageCause: 'connection_lost',
    },
  }
}

function recoveringDevice(): DeviceLiveState {
  const previous = healthyDevice()
  return {
    ...previous,
    connectionState: 'recovering',
    health: {
      ...previous.health!, overall: 'recovering', presenceState: 'recovering', isCurrent: false,
      outageStartedAtUtc: new Date().toISOString(), outageCause: 'connection_lost',
    },
  }
}

function setFetchMock(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input: URL | RequestInfo, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    return Promise.resolve(handler(url, init))
  })
}

function productFetch() {
  return (url: string, _init?: RequestInit) => {
    if (url.endsWith('/api/v1/hub/setup')) return json(setupState(true, 'ready'))
    if (url.endsWith('/api/v1/hub/session')) return json({
      accessToken: 'local-session-token',
      expiresAtUtc: new Date(Date.now() + 60_000).toISOString(),
      tokenType: 'Bearer',
      operatorDisplayName: 'Local Hub',
    })
    if (url.endsWith('/api/v1/hub/status')) return json({
      isReady: true,
      hubName: 'Smith Home',
      mode: 'home',
      storage: { status: 'ready', label: 'Healthy', detail: '200 GB free · Balanced policy' },
      database: { status: 'ready', label: 'Ready', detail: 'Private Hub data is healthy' },
      mediaEngine: { status: 'ready', label: 'Ready', detail: 'Hub-managed media engine' },
      connectedDevices: 0,
      deviceMessage: 'Waiting for devices',
      recordingFolder: '/Users/smith/Videos/SentriCam',
      storagePolicy: 'balanced',
      startAutomatically: true,
      configuredAtUtc: new Date().toISOString(),
    })
    if (url.endsWith('/api/v1/hub/pairing-sessions')) return json({
      payload: 'sentricam://pair?v=1&hub=https%3A%2F%2Fhub.local%2F&code=0123456789abcdef0123456789abcdef&fp=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      hubName: 'Smith Home',
      hubAddress: 'https://hub.local/',
      expiresAtUtc: new Date(Date.now() + 300_000).toISOString(),
      protocolVersion: '1',
      hubFingerprint: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    })
    if (url.includes('/api/v1/recordings/time')) return json([])
    if (url.includes('/api/v1/recordings')) return json({
      items: [], page: 1, pageSize: 24, totalCount: 0, totalPages: 0,
      hasPreviousPage: false, hasNextPage: false,
    })
    if (url.includes('/api/v1/monitoring/devices')) return json([])
    return new Response(null, { status: 404 })
  }
}

function setupState(
  isConfigured: boolean,
  currentStep: string,
  state: HubSetupState['state'] = isConfigured ? 'Completed' : currentStep === 'welcome' ? 'NotStarted' : 'InProgress',
  configuredAtUtc: string | null = isConfigured ? new Date().toISOString() : null,
): HubSetupState {
  return {
    isConfigured,
    state,
    defaultRecordingFolder: '/Users/smith/Videos/SentriCam',
    supportedDatabaseProviders: ['sqlite', 'sqlserver', 'postgresql'],
    version: '0.3.0',
    configuredAtUtc,
    failure: state === 'Failed' || state === 'NeedsRepair' ? {
      code: state === 'Failed' ? 'provisioning_failed' : 'startup_health_failed',
      message: state === 'Failed'
        ? 'SentriCam could not finish preparing this Hub.'
        : 'The Hub needs attention before the dashboard can open.',
      failedAtUtc: new Date().toISOString(),
      canRetry: true,
    } : null,
    draft: {
      mode: 'home',
      hubName: 'Smith Home',
      recordingFolder: '/Users/smith/Videos/SentriCam',
      storagePolicy: 'balanced',
      retentionDays: 30,
      minimumFreeSpaceGb: 10,
      startAutomatically: true,
      openDashboard: true,
      database: {
        provider: 'sqlite',
        host: null,
        port: null,
        databaseName: '/private/sentricam.db',
        username: null,
        password: null,
        encrypt: true,
        trustServerCertificate: false,
        useIntegratedSecurity: false,
        hasStoredPassword: false,
      },
      network: {
        httpsEnabled: false,
        port: 5173,
        certificatePath: null,
        generateCertificate: true,
      },
      media: { preferBundledEngine: true, customPath: null },
      currentStep,
    },
  }
}

function json(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}
