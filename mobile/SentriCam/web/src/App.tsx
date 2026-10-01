import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { HubConnection } from '@microsoft/signalr'
import { HubApi, MonitoringApi, MonitoringApiError, requestHubOperatorSession } from './api'
import { DeviceDetails } from './components/DeviceDetails'
import { DeviceList } from './components/DeviceList'
import { Icon } from './components/Icon'
import { HubOverview } from './components/HubOverview'
import { RecordingsPage } from './components/RecordingsPage'
import { LiveViewPage } from './components/LiveViewPage'
import { SetupWizard } from './components/SetupWizard'
import { CameraControlCenter } from './components/CameraControlCenter'
import { DashboardSkeleton } from './components/Skeleton'
import { StatusBadge } from './components/StatusBadge'
import type { CommandAction, DeviceLiveState, DeviceMonitoringDetails, LiveViewAvailability } from './models'
import type { HubSetupState } from './models'
import { createMonitoringConnection, OPERATIONAL_FALLBACK_POLL_MILLIS, type RealtimeState } from './realtime'
import { selectMoreCriticalDeviceState } from './monitoringHealth'
import { monitoringTrace } from './monitoringDiagnostics'

const OPERATOR_TOKEN_STORAGE_KEY = 'sentricam-operator-token'

export default function App() {
  const [setup, setSetup] = useState<HubSetupState | null>(null)
  const [startupError, setStartupError] = useState<string | null>(null)
  const [token, setToken] = useState(() => {
    try {
      return sessionStorage.getItem(OPERATOR_TOKEN_STORAGE_KEY) ?? ''
    } catch {
      return ''
    }
  })

  const loadSetup = useCallback(() => {
    const abort = new AbortController()
    setStartupError(null)
    new HubApi().getSetup(abort.signal)
      .then(setSetup)
      .catch((failure) => {
        if (failure instanceof DOMException && failure.name === 'AbortError') return
        setStartupError('SentriCam Hub is not responding. Make sure it is running, then try again.')
      })
    return () => abort.abort()
  }, [])

  useEffect(loadSetup, [loadSetup])

  const handleTokenRefresh = useCallback((next: string) => {
    sessionStorage.setItem(OPERATOR_TOKEN_STORAGE_KEY, next)
    setToken(next)
  }, [])

  const handleSignOut = useCallback(() => {
    sessionStorage.removeItem(OPERATOR_TOKEN_STORAGE_KEY)
    setToken('')
  }, [])

  if (startupError) return <StartupState error={startupError} onRetry={loadSetup}/>
  if (!setup) return <StartupState/>
  const setupCompleted = setup.state === 'Completed'
    && setup.isConfigured
    && setup.configuredAtUtc !== null
  if (!setupCompleted) {
    const wizardSetup: HubSetupState = setup.state === 'Completed'
      ? {
          ...setup,
          state: 'NeedsRepair',
          draft: { ...setup.draft, currentStep: 'summary' },
          failure: setup.failure ?? {
            code: 'incomplete_completion',
            message: 'The saved Hub setup is incomplete. Review the saved choices and run setup again.',
            failedAtUtc: new Date(0).toISOString(),
            canRetry: true,
          },
        }
      : setup
    return <SetupWizard initial={wizardSetup} onReady={() => {
      new HubApi().getSetup()
        .then(setSetup)
        .catch(() => setStartupError('SentriCam could not verify that setup completed. Try again.'))
    }}/>
  }
  if (!token) return <SessionBootstrap onConnect={handleTokenRefresh}/>
  return <Dashboard token={token} onSignOut={handleSignOut} />
}

function StartupState({ error, onRetry }: { error?: string; onRetry?: () => void }) {
  return <div className="startup-shell"><span className="brand-mark"><Icon name="camera" size={28}/></span>{error ? <><h1>Hub unavailable</h1><p>{error}</p><button className="button button--primary" onClick={onRetry}>Try again</button></> : <><div className="spinner"/><p>Starting SentriCam Hub…</p></>}</div>
}

function SessionBootstrap({ onConnect }: { onConnect: (token: string) => void }) {
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true
    setError(null)
    requestHubOperatorSession()
      .then((session) => { if (active) onConnect(session.accessToken) })
      .catch(() => { if (active) setError('SentriCam could not open the local dashboard session.') })
    return () => { active = false }
  }, [attempt, onConnect])
  return <div className="startup-shell"><span className="brand-mark"><Icon name="camera" size={28}/></span>{error ? <><h1>Dashboard unavailable</h1><p>{error}</p><button className="button button--primary" onClick={() => setAttempt((value) => value + 1)}>Try again</button></> : <><div className="spinner"/><p>Opening your dashboard…</p></>}</div>
}

function Dashboard({ token, onSignOut }: { token: string; onSignOut: () => void }) {
  const tokenRef = useRef(token)
  tokenRef.current = token
  const api = useMemo(() => new MonitoringApi(() => tokenRef.current), [])
  const hubApi = useMemo(() => new HubApi(() => tokenRef.current), [])
  const [devices, setDevices] = useState<DeviceLiveState[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [details, setDetails] = useState<DeviceMonitoringDetails | null>(null)
  const [realtime, setRealtime] = useState<RealtimeState>('connecting')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<CommandAction | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)
  const [section, setSection] = useState<'hub' | 'devices' | 'live' | 'controls' | 'recordings'>(() => {
    const requested = new URLSearchParams(window.location.search).get('view')
    return requested === 'recordings' ? 'recordings' : requested === 'controls' ? 'controls' : requested === 'live' ? 'live' : requested === 'devices' ? 'devices' : 'hub'
  })
  const [recordingScopeId, setRecordingScopeId] = useState<string | null>(() => new URLSearchParams(window.location.search).get('device'))
  const realtimeConnection = useRef<HubConnection | null>(null)
  const [liveConnection, setLiveConnection] = useState<HubConnection | null>(null)
  const [selectedLiveAvailability, setSelectedLiveAvailability] = useState<LiveViewAvailability | null>(null)

  const handleFailure = useCallback((failure: unknown) => {
    if (failure instanceof DOMException && failure.name === 'AbortError') return
    if (failure instanceof MonitoringApiError && failure.code === 'unauthorized') {
      onSignOut()
      return
    }
    setError(failure instanceof MonitoringApiError ? failure.message : 'The Local Hub is unavailable. Check the server connection and try again.')
  }, [onSignOut])

  useEffect(() => {
    const abort = new AbortController()
    setLoading(true)
    api.listDevices(abort.signal)
      .then((items) => {
        monitoringTrace('device_list_received', {
          devices: items.map((item) => ({
            deviceId: item.deviceId,
            connectionState: item.connectionState,
            presenceState: item.health?.presenceState,
            overall: item.health?.overall,
            isCurrent: item.health?.isCurrent,
          })),
        })
        setDevices(items)
        setSelectedId((current) => current && items.some((item) => item.deviceId === current) ? current : items[0]?.deviceId ?? null)
        setError(null)
      })
      .catch(handleFailure)
      .finally(() => setLoading(false))
    return () => abort.abort()
  }, [api, handleFailure, refreshKey])

  useEffect(() => {
    if (!selectedId) { setDetails(null); return }
    const abort = new AbortController()
    api.getDevice(selectedId, abort.signal).then((next) => {
      monitoringTrace('device_detail_received', {
        deviceId: next.device.deviceId,
        connectionState: next.device.connectionState,
        presenceState: next.device.health?.presenceState,
        overall: next.device.health?.overall,
        isCurrent: next.device.health?.isCurrent,
      })
      setDetails(next)
    }).catch(handleFailure)
    return () => abort.abort()
  }, [api, handleFailure, refreshKey, selectedId])

  useEffect(() => {
    let active = true
    if (!selectedId || !liveConnection || realtime !== 'live') {
      setSelectedLiveAvailability(null)
      return () => { active = false }
    }
    liveConnection.invoke<LiveViewAvailability>('GetLiveAvailability', selectedId)
      .then((availability) => { if (active) setSelectedLiveAvailability(availability) })
      .catch(() => { if (active) setSelectedLiveAvailability(null) })
    return () => { active = false }
  }, [liveConnection, realtime, refreshKey, selectedId])

  useEffect(() => {
    let refreshTimer: number | undefined
    const scheduleRefresh = (deviceId: string, serverUtcNow?: string) => {
      window.clearTimeout(refreshTimer)
      monitoringTrace('event_refresh_scheduled', { deviceId, serverUtcNow, delayMilliseconds: 120 })
      refreshTimer = window.setTimeout(() => setRefreshKey((key) => key + 1), 120)
    }
    const connection = createMonitoringConnection(() => tokenRef.current, setRealtime, scheduleRefresh)
    realtimeConnection.current = connection
    setLiveConnection(connection)
    connection.start().then(() => setRealtime('live')).catch(() => setRealtime('offline'))
    return () => {
      window.clearTimeout(refreshTimer)
      realtimeConnection.current = null
      setLiveConnection(null)
      void connection.stop()
    }
  }, [])

  const selectedFleetDevice = devices.find((device) => device.deviceId === selectedId)
  useEffect(() => {
    monitoringTrace('device_store_updated', {
      devices: devices.map((device) => ({
        deviceId: device.deviceId,
        connectionState: device.connectionState,
        presenceState: device.health?.presenceState,
        overall: device.health?.overall,
        isCurrent: device.health?.isCurrent,
      })),
    })
  }, [devices])

  const visibleDetails = useMemo(() => {
    if (!details || details.device.deviceId !== selectedId) return null
    return {
      ...details,
      device: selectMoreCriticalDeviceState(details.device, selectedFleetDevice),
    }
  }, [details, selectedFleetDevice, selectedId])

  useEffect(() => {
    const fallback = window.setInterval(
      () => setRefreshKey((key) => key + 1),
      OPERATIONAL_FALLBACK_POLL_MILLIS,
    )
    return () => window.clearInterval(fallback)
  }, [])

  const sendCommand = async (action: CommandAction) => {
    if (!selectedId || busy) return
    setBusy(action)
    setError(null)
    try {
      await api.sendCommand(selectedId, action)
      setRefreshKey((key) => key + 1)
    } catch (failure) {
      handleFailure(failure)
    } finally {
      setBusy(null)
    }
  }

  const handleDeviceRemoved = () => {
    setSelectedId(null)
    setDetails(null)
    setRefreshKey((key) => key + 1)
  }

  const showDevices = () => {
    setSection('devices')
    setRecordingScopeId(null)
    const url = new URL(window.location.href)
    url.search = ''
    window.history.pushState(null, '', url)
  }

  const showDeviceDetails = (deviceId: string) => {
    setSelectedId(deviceId)
    setSection('devices')
    setRecordingScopeId(null)
    const url = new URL(window.location.href)
    url.search = ''
    url.searchParams.set('view', 'devices')
    url.searchParams.set('device', deviceId)
    window.history.pushState(null, '', url)
  }

  const showHub = () => {
    setSection('hub')
    setRecordingScopeId(null)
    const url = new URL(window.location.href)
    url.search = ''
    window.history.pushState(null, '', url)
  }

  const showRecordings = (deviceId?: string) => {
    setSection('recordings')
    setRecordingScopeId(deviceId ?? null)
    const url = new URL(window.location.href)
    url.search = ''
    url.searchParams.set('view', 'recordings')
    if (deviceId) url.searchParams.append('device', deviceId)
    window.history.pushState(null, '', url)
  }

  const showLive = (deviceId?: string) => {
    setSection('live')
    if (deviceId) setSelectedId(deviceId)
    const url = new URL(window.location.href)
    url.search = ''
    url.searchParams.set('view', 'live')
    if (deviceId) url.searchParams.append('device', deviceId)
    window.history.pushState(null, '', url)
  }

  const showControls = (deviceId?: string) => {
    setSection('controls')
    if (deviceId) setSelectedId(deviceId)
    const url = new URL(window.location.href)
    url.search = ''
    url.searchParams.set('view', 'controls')
    if (deviceId) url.searchParams.append('device', deviceId)
    window.history.pushState(null, '', url)
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><span className="brand-mark brand-mark--small"><Icon name="camera" size={20}/></span><div><strong>SentriCam</strong><span>Local Hub</span></div></div>
        <nav className="topbar__nav" aria-label="Dashboard sections">
          <button className={section === 'hub' ? 'topbar__nav-button topbar__nav-button--active' : 'topbar__nav-button'} onClick={showHub}><Icon name="home" size={17}/>Overview</button>
          <button className={section === 'devices' ? 'topbar__nav-button topbar__nav-button--active' : 'topbar__nav-button'} onClick={showDevices}><Icon name="devices" size={17}/>Devices</button>
          <button className={section === 'live' ? 'topbar__nav-button topbar__nav-button--active' : 'topbar__nav-button'} onClick={() => showLive(selectedId ?? undefined)}><Icon name="grid" size={17}/>Live Wall</button>
          <button className={section === 'controls' ? 'topbar__nav-button topbar__nav-button--active' : 'topbar__nav-button'} onClick={() => showControls(selectedId ?? undefined)}><Icon name="controls" size={17}/>Controls</button>
          <button className={section === 'recordings' ? 'topbar__nav-button topbar__nav-button--active' : 'topbar__nav-button'} onClick={() => showRecordings()}><Icon name="recording" size={17}/>Recordings</button>
        </nav>
        <div className="topbar__status">
          <StatusBadge tone={realtime === 'live' ? 'positive' : realtime === 'offline' ? 'danger' : 'warning'} pulse={realtime === 'live'}>{realtime === 'live' ? 'Realtime connected' : realtime}</StatusBadge>
          <button className="text-button" onClick={onSignOut}>Sign out</button>
        </div>
      </header>
      {error && <div className="error-banner" role="alert"><span>{error}</span><button onClick={() => setRefreshKey((key) => key + 1)}>Try again</button></div>}
      {section === 'hub' ? <HubOverview api={hubApi} onShowDevices={showDevices}/> : section === 'live' ? <LiveViewPage connection={liveConnection} devices={devices} api={api} initialDeviceId={selectedId} realtimeState={realtime} onOpenControls={showControls} onOpenDetails={showDeviceDetails} /> : section === 'controls' ? <CameraControlCenter api={api} devices={devices} initialDeviceId={selectedId} onSelectDevice={setSelectedId} refreshKey={refreshKey} onFailure={handleFailure}/> : section === 'recordings' ? <RecordingsPage api={api} devices={devices} initialDeviceId={recordingScopeId} onUnauthorized={handleFailure} /> : loading && devices.length === 0 ? <DashboardSkeleton /> : devices.length === 0 ? (
        <div className="empty-dashboard"><Icon name="devices" size={34}/><h1>No devices yet</h1><p>Registered devices will appear here as soon as they connect to this Local Hub.</p></div>
      ) : (
        <div className="dashboard-layout">
          <DeviceList devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
          {visibleDetails ? <DeviceDetails details={visibleDetails} busy={busy} onCommand={sendCommand} onViewLive={() => showLive(visibleDetails.device.deviceId)} liveAvailability={selectedLiveAvailability} onViewRecordings={() => showRecordings(visibleDetails.device.deviceId)} loadRemovalImpact={(deviceId) => api.getDeviceRemovalImpact(deviceId)} removeDevice={(deviceId, deviceName) => api.removeDevice(deviceId, deviceName)} onDeviceRemoved={handleDeviceRemoved} /> : <div className="detail-loading"><div className="spinner"/>Loading device state…</div>}
        </div>
      )}
    </div>
  )
}
