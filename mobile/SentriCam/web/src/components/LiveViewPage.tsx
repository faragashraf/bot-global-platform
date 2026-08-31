import { useEffect, useMemo, useRef, useState } from 'react'
import type { HubConnection } from '@microsoft/signalr'
import type { MonitoringApi } from '../api'
import { cameraControlIds, commandRequest, recordingActions } from '../camera-control'
import { formatStorage, readable } from '../format'
import { BrowserLiveMediaSession, type LiveMediaSession } from '../live-view'
import {
  LIVE_WALL_LAYOUTS,
  LiveWallOrchestrator,
  loadLiveWallLayout,
  saveLiveWallLayout,
  type LiveTileSnapshot,
  type LiveWallActionResult,
  type LiveWallLayoutSize,
  type LiveWallSnapshot,
  type SavedLiveWallLayout,
} from '../live-wall'
import type { CameraControlCenterView, CameraControlCommand, CameraControlGroupCommandResult, DeviceLiveState } from '../models'
import type { RealtimeState } from '../realtime'
import { getEffectiveConnectionState, getEffectiveTransportState } from '../monitoringHealth'
import { friendlyLiveReason, resolveLiveReadiness } from '../liveReadiness'
import { resolveMotionStatus } from '../motionStatus'
import {
  resolveCameraActionEligibility,
  summarizeEligibility,
  type CameraActionEligibility,
  type WallAction,
} from '../liveWallEligibility'
import { Icon } from './Icon'
import { StatusBadge } from './StatusBadge'

type LiveWallApi = Pick<MonitoringApi, 'sendCameraControl' | 'getCameraControl'> & {
  sendCameraControlGroup?: MonitoringApi['sendCameraControlGroup']
}

type Props = {
  connection: HubConnection | null
  devices: DeviceLiveState[]
  api?: LiveWallApi
  initialDeviceId?: string | null
  realtimeState?: RealtimeState
  createMediaSession?: (deviceId: string) => LiveMediaSession
  onOpenControls?: (deviceId: string) => void
  onOpenDetails?: (deviceId: string) => void
}

type GroupNotice = {
  action: string
  results: LiveWallActionResult[]
}

const createBrowserMedia = () => new BrowserLiveMediaSession()

export function LiveViewPage({
  connection,
  devices,
  api,
  initialDeviceId,
  realtimeState = 'live',
  createMediaSession = createBrowserMedia,
  onOpenControls,
  onOpenDetails,
}: Props) {
  const saved = useRef<SavedLiveWallLayout | null>(null)
  if (!saved.current) saved.current = loadLiveWallLayout()
  const [layoutSize, setLayoutSize] = useState<LiveWallLayoutSize>(saved.current.size)
  const [slots, setSlots] = useState<Array<string | null>>(() => saved.current!.deviceIds)
  const [selected, setSelected] = useState<Set<string>>(() => new Set(saved.current!.selectedDeviceIds))
  const [lastActiveDeviceId, setLastActiveDeviceId] = useState<string | null>(
    initialDeviceId ?? saved.current.lastActiveDeviceId,
  )
  const [fullscreenDeviceId, setFullscreenDeviceId] = useState<string | null>(null)
  const [theme, setTheme] = useState<'light' | 'dark'>(() =>
    window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark',
  )
  const [notice, setNotice] = useState<GroupNotice | null>(null)
  const [liveBusy, setLiveBusy] = useState<Set<string>>(new Set())
  const [recordingBusy, setRecordingBusy] = useState<Set<string>>(new Set())
  const [cameraControls, setCameraControls] = useState<Record<string, CameraControlCenterView>>({})
  const liveBusyRef = useRef<Set<string>>(new Set())
  const recordingBusyRef = useRef<Set<string>>(new Set())
  const initialized = useRef(false)

  const orchestrator = useMemo(
    () => connection ? new LiveWallOrchestrator(connection, createMediaSession) : null,
    [connection, createMediaSession],
  )
  const [wall, setWall] = useState<LiveWallSnapshot>(() => orchestrator?.getSnapshot() ?? emptyWall())

  useEffect(() => {
    if (!orchestrator) { setWall(emptyWall()); return }
    const unsubscribe = orchestrator.subscribe(setWall)
    return () => {
      unsubscribe()
      void orchestrator.destroy()
    }
  }, [orchestrator])

  useEffect(() => {
    if (initialized.current || devices.length === 0) return
    initialized.current = true
    setSlots((current) => {
      const known = new Set(devices.map((device) => device.deviceId))
      const cleaned = Array.from({ length: layoutSize }, (_, index) => {
        const deviceId = current[index]
        return deviceId && known.has(deviceId) ? deviceId : null
      })
      const assigned = new Set(cleaned.filter((value): value is string => value != null))
      const candidates = [...new Set([initialDeviceId, ...devices.map((device) => device.deviceId)])]
        .filter((value): value is string => Boolean(value) && !assigned.has(value!))
      for (let index = 0; index < cleaned.length && candidates.length; index += 1) {
        if (cleaned[index] == null) {
          const next = candidates.shift()!
          cleaned[index] = next
          assigned.add(next)
        }
      }
      return cleaned
    })
  }, [devices, initialDeviceId, layoutSize])

  const assignedDeviceIds = slots.filter((value): value is string => value != null)

  useEffect(() => {
    orchestrator?.updateDevices(devices)
    orchestrator?.setVisibleDevices(assignedDeviceIds)
  }, [assignedDeviceIds.join('|'), devices, orchestrator])

  useEffect(() => {
    if (!api || assignedDeviceIds.length === 0) return
    const abort = new AbortController()
    void Promise.all(assignedDeviceIds.map(async (deviceId) => {
      try {
        return await api.getCameraControl(deviceId, abort.signal)
      } catch {
        return null
      }
    })).then((reports) => {
      if (abort.signal.aborted) return
      setCameraControls((current) => {
        const next = { ...current }
        reports.forEach((report) => { if (report) next[report.deviceId] = report })
        return next
      })
    })
    return () => abort.abort()
  }, [api, assignedDeviceIds.join('|'), devices])

  useEffect(() => {
    orchestrator?.setHubAvailable(realtimeState === 'live')
  }, [orchestrator, realtimeState])

  useEffect(() => {
    const changed = () => orchestrator?.setPageVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', changed)
    return () => document.removeEventListener('visibilitychange', changed)
  }, [orchestrator])

  useEffect(() => {
    saveLiveWallLayout({
      version: 1,
      size: layoutSize,
      deviceIds: slots,
      selectedDeviceIds: [...selected].filter((deviceId) => slots.includes(deviceId)),
      lastActiveDeviceId,
    })
  }, [lastActiveDeviceId, layoutSize, selected, slots])

  useEffect(() => {
    const changed = () => { if (!document.fullscreenElement) setFullscreenDeviceId(null) }
    document.addEventListener('fullscreenchange', changed)
    return () => document.removeEventListener('fullscreenchange', changed)
  }, [])

  const resizeLayout = (size: LiveWallLayoutSize) => {
    setLayoutSize(size)
    setSlots((current) => Array.from({ length: size }, (_, index) => current[index] ?? null))
  }

  const assignSlot = (index: number, deviceId: string | null) => {
    setSlots((current) => {
      const next = [...current]
      if (deviceId) {
        const previous = next.indexOf(deviceId)
        if (previous >= 0) next[previous] = null
      }
      next[index] = deviceId
      return next
    })
    if (deviceId) {
      setSelected((current) => new Set(current).add(deviceId))
      setLastActiveDeviceId(deviceId)
    }
  }

  const selectTile = (deviceId: string, additive = false) => {
    setSelected((current) => {
      if (!additive) return new Set([deviceId])
      const next = new Set(current)
      if (next.has(deviceId)) next.delete(deviceId)
      else next.add(deviceId)
      return next
    })
    setLastActiveDeviceId(deviceId)
  }

  const eligibilityFor = (deviceId: string, busyFromRefs = false) => {
    const device = devices.find((item) => item.deviceId === deviceId)
    if (!device) return null
    return resolveCameraActionEligibility(
      device,
      wall.tiles[deviceId],
      {
        live: busyFromRefs ? liveBusyRef.current.has(deviceId) : liveBusy.has(deviceId),
        recording: busyFromRefs ? recordingBusyRef.current.has(deviceId) : recordingBusy.has(deviceId),
      },
      cameraControls[deviceId],
    )
  }

  const runLiveAction = async (action: 'start' | 'stop', deviceIds: string[], fullscreenId?: string) => {
    if (!orchestrator || deviceIds.length === 0) return
    setNotice(null)
    const requested = [...new Set(deviceIds)]
    const key: WallAction = action === 'start' ? 'startLive' : 'stopLive'
    const eligible = requested.filter((deviceId) => eligibilityFor(deviceId, true)?.[key].eligible)
    const skipped = requested
      .filter((deviceId) => !eligible.includes(deviceId))
      .map((deviceId): LiveWallActionResult => ({
        deviceId,
        succeeded: false,
        skipped: true,
        code: eligibilityFor(deviceId, true)?.[key].reason ?? 'device_unavailable',
      }))
    if (eligible.length === 0) {
      setNotice({ action: action === 'start' ? 'Start Live' : 'Stop Live', results: skipped })
      return
    }
    eligible.forEach((deviceId) => liveBusyRef.current.add(deviceId))
    setLiveBusy(new Set(liveBusyRef.current))
    let completed: LiveWallActionResult[] = []
    try {
      completed = action === 'start'
        ? fullscreenId && eligible.length === 1
          ? [await orchestrator.start(eligible[0], eligible[0] === fullscreenId)]
          : await orchestrator.startMany(eligible)
        : await orchestrator.stopMany(eligible)
    } finally {
      eligible.forEach((deviceId) => liveBusyRef.current.delete(deviceId))
      setLiveBusy(new Set(liveBusyRef.current))
    }
    const results = [...completed, ...skipped]
    setNotice({ action: action === 'start' ? 'Start Live' : 'Stop Live', results })
  }

  const runRecordingAction = async (action: 'start' | 'stop', deviceIds: string[]) => {
    if (!api || deviceIds.length === 0) return
    setNotice(null)
    const requested = [...new Set(deviceIds)]
    const key: WallAction = action === 'start' ? 'startRecording' : 'stopRecording'
    const eligible = requested.filter((deviceId) => eligibilityFor(deviceId, true)?.[key].eligible)
    const skipped = requested
      .filter((deviceId) => !eligible.includes(deviceId))
      .map((deviceId): LiveWallActionResult => ({
        deviceId,
        succeeded: false,
        skipped: true,
        code: eligibilityFor(deviceId, true)?.[key].reason ?? 'recording_unavailable',
      }))
    if (eligible.length === 0) {
      setNotice({ action: action === 'start' ? 'Start Recording' : 'Stop Recording', results: skipped })
      return
    }
    eligible.forEach((deviceId) => recordingBusyRef.current.add(deviceId))
    setRecordingBusy(new Set(recordingBusyRef.current))
    let results: LiveWallActionResult[] = []
    if (api.sendCameraControlGroup) {
      try {
        const request = commandRequest(cameraControlIds.recording, recordingActions[action])
        const grouped: CameraControlGroupCommandResult = await api.sendCameraControlGroup({
          deviceIds: eligible,
          control: request.control,
          value: request.value,
          correlationId: request.correlationId,
        })
        results = await Promise.all(grouped.items.map(async (item): Promise<LiveWallActionResult> => {
          if (!item.succeeded || !item.command) {
            return { deviceId: item.deviceId, succeeded: false, code: item.errorCode ?? item.message }
          }
          return waitForRecordingAcknowledgement(api, item.deviceId, item.command, updateCameraControl)
        }))
      } catch (failure) {
        const code = failure instanceof Error ? failure.message : 'recording_command_failed'
        results = eligible.map((deviceId) => ({ deviceId, succeeded: false, code }))
      }
    } else {
      results = await Promise.all(eligible.map(async (deviceId): Promise<LiveWallActionResult> => {
        try {
          const command = await api.sendCameraControl(deviceId, commandRequest(cameraControlIds.recording, recordingActions[action]))
          return waitForRecordingAcknowledgement(api, deviceId, command, updateCameraControl)
        } catch (failure) {
          return {
            deviceId,
            succeeded: false,
            code: failure instanceof Error ? failure.message : 'recording_command_failed',
          }
        }
      }))
    }
    eligible.forEach((deviceId) => recordingBusyRef.current.delete(deviceId))
    setRecordingBusy(new Set(recordingBusyRef.current))
    setNotice({ action: action === 'start' ? 'Start Recording' : 'Stop Recording', results: [...results, ...skipped] })
  }

  const updateCameraControl = (view: CameraControlCenterView) => {
    setCameraControls((current) => ({ ...current, [view.deviceId]: view }))
  }

  const selectedVisible = [...selected].filter((deviceId) => assignedDeviceIds.includes(deviceId))
  const assignedDevices = assignedDeviceIds
    .map((deviceId) => devices.find((device) => device.deviceId === deviceId))
    .filter((device): device is DeviceLiveState => device != null)
  const selectedEligibility = selectedVisible
    .map((deviceId) => eligibilityFor(deviceId))
    .filter((value): value is CameraActionEligibility => value != null)
  const assignedEligibility = assignedDeviceIds
    .map((deviceId) => eligibilityFor(deviceId))
    .filter((value): value is CameraActionEligibility => value != null)
  const actionSummary = {
    startLive: summarizeEligibility('startLive', selectedEligibility, 'selected'),
    stopLive: summarizeEligibility('stopLive', selectedEligibility, 'selected'),
    stopAll: summarizeEligibility('stopLive', assignedEligibility, 'wall cameras'),
    startRecording: summarizeEligibility('startRecording', selectedEligibility, 'selected'),
    stopRecording: summarizeEligibility('stopRecording', selectedEligibility, 'selected'),
  }

  return <main className={`live-wall-page live-wall-page--${theme}`} data-theme={theme}>
    <header className="live-wall-heading">
      <div>
        <span className="eyebrow">Local camera operations</span>
        <h1>Live Wall</h1>
        <p>Watch and manage paired cameras together. Each tile recovers independently.</p>
      </div>
      <div className="live-wall-heading__summary" aria-label="Live Wall resource summary">
        <StatusBadge tone={realtimeState === 'live' ? 'positive' : realtimeState === 'offline' ? 'danger' : 'warning'}>
          {realtimeState === 'live' ? 'Hub connected' : 'Hub reconnecting'}
        </StatusBadge>
        <span>{wall.metrics.activePublishers} streaming · {wall.metrics.activeTileSessions} active</span>
        <button className="button button--quiet" onClick={() => setTheme((value) => value === 'dark' ? 'light' : 'dark')} aria-label={`Use ${theme === 'dark' ? 'light' : 'dark'} mode`}>
          <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={18}/>
        </button>
      </div>
    </header>

    <LiveWallToolbar
      layoutSize={layoutSize}
      selectedCount={selectedVisible.length}
      actionSummary={actionSummary}
      onLayout={resizeLayout}
      onStartSelected={() => runLiveAction('start', selectedVisible)}
      onStopSelected={() => runLiveAction('stop', selectedVisible)}
      onStopAll={() => runLiveAction('stop', assignedDeviceIds)}
      onStartRecording={() => runRecordingAction('start', selectedVisible)}
      onStopRecording={() => runRecordingAction('stop', selectedVisible)}
    />

    {notice && <GroupActionNotice notice={notice} devices={devices} onDismiss={() => setNotice(null)}/>}

    <section
      className={`live-wall-grid live-wall-grid--${layoutSize}${fullscreenDeviceId ? ' live-wall-grid--fullscreen' : ''}`}
      data-layout={layoutSize}
      aria-label={`${layoutSize}-camera Live Wall`}
    >
      {slots.map((deviceId, index) => {
        const device = devices.find((item) => item.deviceId === deviceId)
        if (!deviceId || !device) {
          return <EmptyLiveTile
            key={`slot-${index}`}
            index={index}
            devices={devices.filter((candidate) => !assignedDeviceIds.includes(candidate.deviceId))}
            onAssign={(next) => assignSlot(index, next)}
          />
        }
        return <LiveTile
          key={deviceId}
          device={device}
          tile={wall.tiles[deviceId]}
          selected={selected.has(deviceId)}
          fullscreen={fullscreenDeviceId === deviceId}
          eligibility={eligibilityFor(deviceId)!}
          onSelect={(additive) => selectTile(deviceId, additive)}
          onStart={() => runLiveAction('start', [deviceId], fullscreenDeviceId ?? undefined)}
          onStop={() => runLiveAction('stop', [deviceId])}
          onPlaybackFailure={() => orchestrator?.reportPlaybackFailure(deviceId)}
          onRecord={(action) => runRecordingAction(action, [deviceId])}
          onFullscreen={(element) => {
            setFullscreenDeviceId(deviceId)
            if (element.requestFullscreen) void element.requestFullscreen()
          }}
          onExitFullscreen={() => {
            setFullscreenDeviceId(null)
            if (document.fullscreenElement && document.exitFullscreen) void document.exitFullscreen()
          }}
          onOpenControls={() => onOpenControls?.(deviceId)}
          onOpenDetails={() => onOpenDetails?.(deviceId)}
          onRemove={() => assignSlot(index, null)}
        />
      })}
    </section>

    {assignedDevices.length === 0 && <p className="live-wall-hint">Assign a paired camera to a tile. Saved layouts never restart streams automatically.</p>}
  </main>
}

function LiveWallToolbar({
  layoutSize,
  selectedCount,
  actionSummary,
  onLayout,
  onStartSelected,
  onStopSelected,
  onStopAll,
  onStartRecording,
  onStopRecording,
}: {
  layoutSize: LiveWallLayoutSize
  selectedCount: number
  actionSummary: {
    startLive: ReturnType<typeof summarizeEligibility>
    stopLive: ReturnType<typeof summarizeEligibility>
    stopAll: ReturnType<typeof summarizeEligibility>
    startRecording: ReturnType<typeof summarizeEligibility>
    stopRecording: ReturnType<typeof summarizeEligibility>
  }
  onLayout: (size: LiveWallLayoutSize) => void
  onStartSelected: () => void
  onStopSelected: () => void
  onStopAll: () => void
  onStartRecording: () => void
  onStopRecording: () => void
}) {
  return <section className="live-wall-toolbar panel" aria-label="Live Wall controls">
    <div className="live-wall-layout-picker" role="group" aria-label="Grid capacity">
      <span>Layout</span>
      {LIVE_WALL_LAYOUTS.map((size) => <button
        key={size}
        className={layoutSize === size ? 'live-wall-layout-button live-wall-layout-button--active' : 'live-wall-layout-button'}
        aria-pressed={layoutSize === size}
        onClick={() => onLayout(size)}
      >{size}</button>)}
    </div>
    <div className="live-wall-group-actions">
      <span>{selectedCount} selected</span>
      <button className="button button--primary" disabled={actionSummary.startLive.eligible === 0} title={actionSummary.startLive.detail} onClick={onStartSelected}><Icon name="play"/>Start selected</button>
      <button className="button button--quiet" disabled={actionSummary.stopLive.eligible === 0} title={actionSummary.stopLive.detail} onClick={onStopSelected}><Icon name="stop"/>Stop selected</button>
      <button className="button button--danger" disabled={actionSummary.stopAll.eligible === 0} title={actionSummary.stopAll.detail} onClick={onStopAll}>Stop all</button>
      <button className="button button--quiet" disabled={actionSummary.startRecording.eligible === 0} title={actionSummary.startRecording.detail} onClick={onStartRecording}><Icon name="recording"/>Record selected</button>
      <button className="button button--quiet" disabled={actionSummary.stopRecording.eligible === 0} title={actionSummary.stopRecording.detail} onClick={onStopRecording}>Stop recording</button>
      <small className="live-wall-group-actions__summary" aria-live="polite">
        {actionSummary.startLive.label} · {actionSummary.stopLive.label} · {actionSummary.startRecording.label}
      </small>
    </div>
  </section>
}

function LiveTile({
  device,
  tile,
  selected,
  fullscreen,
  eligibility,
  onSelect,
  onStart,
  onStop,
  onPlaybackFailure,
  onRecord,
  onFullscreen,
  onExitFullscreen,
  onOpenControls,
  onOpenDetails,
  onRemove,
}: {
  device: DeviceLiveState
  tile?: LiveTileSnapshot
  selected: boolean
  fullscreen: boolean
  eligibility: CameraActionEligibility
  onSelect: (additive: boolean) => void
  onStart: () => void
  onStop: () => void
  onPlaybackFailure: () => void
  onRecord: (action: 'start' | 'stop') => void
  onFullscreen: (element: HTMLElement) => void
  onExitFullscreen: () => void
  onOpenControls: () => void
  onOpenDetails: () => void
  onRemove: () => void
}) {
  const articleRef = useRef<HTMLElement | null>(null)
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const transport = getEffectiveTransportState(device)
  const readiness = resolveLiveReadiness(tile?.availability, transport)
  const state = effectiveTileState(tile, transport.connectionState)
  const streaming = state === 'streaming' && tile?.stream != null
  const intent = tile?.viewerIntent === true
  const recording = eligibility.recordingState

  useEffect(() => {
    if (videoRef.current) videoRef.current.srcObject = tile?.stream ?? null
  }, [tile?.stream])

  return <article
    ref={articleRef}
    className={`live-tile live-tile--${state}${selected ? ' live-tile--selected' : ''}${fullscreen ? ' live-tile--fullscreen' : ''}`}
    aria-label={`${device.friendlyName} camera tile`}
    aria-selected={selected}
    tabIndex={0}
    onClick={(event) => onSelect(event.metaKey || event.ctrlKey || event.shiftKey)}
    onDoubleClick={() => articleRef.current && onFullscreen(articleRef.current)}
    onKeyDown={(event) => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onSelect(event.metaKey || event.ctrlKey || event.shiftKey) }
      if (event.key.toLowerCase() === 'f' && articleRef.current) onFullscreen(articleRef.current)
    }}
  >
    <header className="live-tile__header">
      <label className="live-tile__selection" onClick={(event) => event.stopPropagation()}>
        <input type="checkbox" checked={selected} onChange={() => onSelect(true)} aria-label={`Select ${device.friendlyName}`}/>
        <span><strong>{device.friendlyName}</strong><small>{device.platform}</small></span>
      </label>
      <StatusBadge tone={tileTone(state)} pulse={state === 'streaming' || state === 'reconnecting'}>{tileLabel(state)}</StatusBadge>
    </header>

    <div className="live-tile__video">
      <video ref={videoRef} autoPlay playsInline muted onError={onPlaybackFailure} aria-label={`${device.friendlyName} live video`}/>
      {!streaming && <LiveTilePlaceholder state={state} intent={intent} errorCode={tile?.errorCode ?? null} readiness={readiness}/>}
      {streaming && <div className="live-tile__live-mark"><i/>Live</div>}
      {(state === 'connecting' || state === 'reconnecting') && <span className="live-tile__reconnect-indicator" aria-label="Reconnecting"><i/><i/><i/></span>}
    </div>

    <TileStatusStrip device={device} tile={tile} state={state} eligibility={eligibility}/>

    <div className="live-tile__actions" onClick={(event) => event.stopPropagation()}>
      {!intent ? <button className="live-tile__action live-tile__action--primary" onClick={onStart} disabled={!eligibility.startLive.eligible} title={eligibility.startLive.detail}><Icon name="play" size={16}/>Start Live</button>
        : <button className="live-tile__action" onClick={onStop} disabled={!eligibility.stopLive.eligible} title={eligibility.stopLive.detail}><Icon name="stop" size={16}/>Stop Live</button>}
      {recording === 'recording' || recording === 'stopping'
        ? <button className="live-tile__action" disabled={!eligibility.stopRecording.eligible} title={eligibility.stopRecording.detail} onClick={() => onRecord('stop')}>Stop Recording</button>
        : <button className="live-tile__action" disabled={!eligibility.startRecording.eligible} title={eligibility.startRecording.detail} onClick={() => onRecord('start')}><Icon name="recording" size={16}/>Record</button>}
      <button className="live-tile__icon-action" onClick={() => articleRef.current && onFullscreen(articleRef.current)} aria-label={`Fullscreen ${device.friendlyName}`}><Icon name="fullscreen" size={17}/></button>
      {fullscreen && <button className="live-tile__icon-action" onClick={onExitFullscreen} aria-label="Exit fullscreen"><Icon name="close" size={17}/></button>}
      <button className="live-tile__icon-action" onClick={onOpenControls} aria-label={`Open controls for ${device.friendlyName}`}><Icon name="controls" size={17}/></button>
      <button className="live-tile__text-action" onClick={onOpenDetails}>Details</button>
      <button className="live-tile__icon-action" onClick={onRemove} aria-label={`Remove ${device.friendlyName} from wall`}><Icon name="close" size={17}/></button>
    </div>
  </article>
}

function TileStatusStrip({ device, tile, state, eligibility }: { device: DeviceLiveState; tile?: LiveTileSnapshot; state: ReturnType<typeof effectiveTileState>; eligibility: CameraActionEligibility }) {
  const connection = getEffectiveConnectionState(device)
  const transport = getEffectiveTransportState(device)
  const readiness = resolveLiveReadiness(tile?.availability, transport)
  const recording = eligibility.recordingState
  const motion = resolveMotionStatus(device)
  const health = connection.label
  return <div className="live-tile-status" aria-label={`${device.friendlyName} current status`}>
    <TileMetric label="Live" value={state === 'idle' ? readiness.label : tileLabel(state)} tone={state === 'idle' ? readiness.tone : tileTone(state)}/>
    <TileMetric label="Hub link" value={transport.label} tone={transport.tone}/>
    <TileMetric label="Recording" value={readable(recording)} tone={recording === 'recording' ? 'danger' : recording === 'failed' ? 'warning' : 'neutral'}/>
    <TileMetric label="Motion" value={motion.label} tone={motion.tone} detail={motion.detail}/>
    <TileMetric label="Health" value={health} tone={connection.tone}/>
    <TileMetric label="Battery" value={device.batteryPercent == null ? 'Unavailable' : transport.isConnected ? `${device.batteryPercent}%` : `${device.batteryPercent}% · stale`} tone="neutral"/>
    {tile?.quality && <span className="live-tile-status__profile" title={tile.qualityNote ?? undefined}>{readable(tile.quality)} profile</span>}
  </div>
}

function TileMetric({ label, value, tone, detail }: { label: string; value: string; tone: 'positive' | 'warning' | 'danger' | 'neutral'; detail?: string }) {
  return <span className={`live-tile-metric live-tile-metric--${tone}`} title={detail}><small>{label}</small><strong>{value}</strong></span>
}

function LiveTilePlaceholder({ state, intent, errorCode, readiness }: {
  state: ReturnType<typeof effectiveTileState>
  intent: boolean
  errorCode: string | null
  readiness: ReturnType<typeof resolveLiveReadiness>
}) {
  const copy = state === 'offline'
    ? ['Camera offline', 'Controls will return when this device reconnects.']
    : state === 'reconnecting'
      ? ['Connection interrupted', 'Reconnecting…']
      : state === 'connecting'
        ? ['Opening camera', 'Waiting for live video…']
        : state === 'failed'
          ? ['Camera unavailable', friendlyFailure(errorCode ?? readiness.reason)]
          : readiness.state === 'ready'
            ? ['Ready for Live', intent ? 'Waiting for the camera…' : 'Start this camera when you are ready.']
            : [readiness.label, readiness.detail]
  return <div className="live-tile-placeholder" role={state === 'failed' ? 'alert' : undefined}><span><Icon name={state === 'offline' ? 'wifi' : 'camera'} size={28}/></span><strong>{copy[0]}</strong><small>{copy[1]}</small></div>
}

function EmptyLiveTile({ index, devices, onAssign }: { index: number; devices: DeviceLiveState[]; onAssign: (deviceId: string | null) => void }) {
  return <article className="live-tile live-tile--empty" aria-label={`Empty camera tile ${index + 1}`}>
    <div><span><Icon name="camera" size={26}/></span><strong>Empty tile</strong><small>Assign a paired camera without starting Live.</small></div>
    <label>Camera
      <select aria-label={`Camera for tile ${index + 1}`} defaultValue="" onChange={(event) => onAssign(event.target.value || null)}>
        <option value="">Choose camera</option>
        {devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.friendlyName}</option>)}
      </select>
    </label>
  </article>
}

function GroupActionNotice({ notice, devices, onDismiss }: { notice: GroupNotice; devices: DeviceLiveState[]; onDismiss: () => void }) {
  const failures = notice.results.filter((result) => !result.succeeded && !result.skipped)
  const skipped = notice.results.filter((result) => result.skipped)
  const successes = notice.results.filter((result) => result.succeeded)
  const role = failures.length ? 'alert' : 'status'
  return <section className={`live-wall-notice${failures.length ? ' live-wall-notice--partial' : ''}`} role={role}>
    <div><strong>{notice.action}</strong><span>{successes.length} succeeded · {failures.length} failed · {skipped.length} skipped</span></div>
    {failures.length > 0 && <ul>{failures.map((result) => <li key={result.deviceId}><strong>{devices.find((device) => device.deviceId === result.deviceId)?.friendlyName ?? result.deviceId}</strong> — {friendlyFailure(result.code)}</li>)}</ul>}
    {skipped.length > 0 && <ul>{skipped.map((result) => <li key={result.deviceId}><strong>{devices.find((device) => device.deviceId === result.deviceId)?.friendlyName ?? result.deviceId}</strong> — Skipped: {friendlyFailure(result.code)}</li>)}</ul>}
    <button onClick={onDismiss}>Dismiss</button>
  </section>
}

function effectiveTileState(tile: LiveTileSnapshot | undefined, deviceState: ReturnType<typeof getEffectiveConnectionState>['connectionState']) {
  if (deviceState === 'offline') return 'offline' as const
  if (deviceState === 'recovering') return 'reconnecting' as const
  if (deviceState === 'actionRequired') return 'failed' as const
  return tile?.state ?? 'idle'
}

function tileLabel(state: ReturnType<typeof effectiveTileState>) {
  return ({
    idle: 'Idle',
    connecting: 'Connecting',
    streaming: 'Streaming',
    reconnecting: 'Reconnecting',
    failed: 'Failed',
    offline: 'Offline',
  } as const)[state]
}

function tileTone(state: ReturnType<typeof effectiveTileState>): 'positive' | 'warning' | 'danger' | 'neutral' {
  if (state === 'streaming') return 'positive'
  if (state === 'connecting' || state === 'reconnecting') return 'warning'
  if (state === 'failed' || state === 'offline') return 'danger'
  return 'neutral'
}

function friendlyFailure(code: string | null) {
  if (!code) return 'The action could not be completed.'
  if (code.includes('operation_in_progress')) return 'Another action is already in progress for this camera.'
  if (code.includes('live_session_active')) return 'Live is already active or recovering on this camera.'
  if (code.includes('no_active_live_session')) return 'There is no Live session to stop.'
  if (code.includes('recording_capabilities_pending')) return 'Waiting for this connection to confirm recording support.'
  if (code.includes('recording_state_stale')) return 'Waiting for a current recording status from this camera.'
  if (code.includes('recording_session_active')) return 'Recording is already active or changing state.'
  if (code.includes('no_active_recording')) return 'This camera is not recording.'
  if (code.includes('recording_acknowledgement_timeout')) return 'The camera did not confirm the recording action in time.'
  if (code.includes('quality_not_available')) return 'This camera does not support the requested stream profile.'
  if (code.includes('capability') || code.includes('unsupported')) return 'This camera does not support that action.'
  return friendlyLiveReason(code)
}

async function waitForRecordingAcknowledgement(
  api: Pick<MonitoringApi, 'getCameraControl'>,
  deviceId: string,
  initial: CameraControlCommand,
  onRefresh: (view: CameraControlCenterView) => void,
): Promise<LiveWallActionResult> {
  let command = initial
  for (let attempt = 0; attempt <= 32; attempt += 1) {
    const state = String(command.state).toLowerCase()
    if (state === '4' || state === 'succeeded') {
      await refreshCameraControl(api, deviceId, onRefresh)
      return { deviceId, succeeded: true, code: null }
    }
    if (state === '5' || state === 'failed' || state === '6' || state === 'canceled') {
      await refreshCameraControl(api, deviceId, onRefresh)
      return { deviceId, succeeded: false, code: command.resultCode ?? `recording_${state}` }
    }
    if (attempt === 32) break
    await new Promise((resolve) => window.setTimeout(resolve, 1_000))
    try {
      const current = await api.getCameraControl(deviceId)
      onRefresh(current)
      command = current.recentCommands.find((item) => item.commandId === initial.commandId) ?? command
    } catch {
      // The command remains locked while the authoritative result is retried.
    }
  }
  return { deviceId, succeeded: false, code: 'recording_acknowledgement_timeout' }
}

async function refreshCameraControl(
  api: Pick<MonitoringApi, 'getCameraControl'>,
  deviceId: string,
  onRefresh: (view: CameraControlCenterView) => void,
) {
  try {
    onRefresh(await api.getCameraControl(deviceId))
  } catch {
    // The next server event or fallback refresh will retry authoritative state.
  }
}

function emptyWall(): LiveWallSnapshot {
  return {
    tiles: {},
    metrics: {
      activeViewerCount: 0,
      activeTileSessions: 0,
      activePublishers: 0,
      reconnectCount: 0,
      aggregateEstimatedBandwidthBitsPerSecond: null,
    },
  }
}

export function liveWallStorageSummary(device: DeviceLiveState) {
  return `${device.batteryPercent == null ? 'Battery unavailable' : `${device.batteryPercent}% battery`} · ${formatStorage(device.availableStorageBytes)}`
}
