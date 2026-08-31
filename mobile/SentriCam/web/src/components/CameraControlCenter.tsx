import { useCallback, useEffect, useMemo, useState } from 'react'
import type { MonitoringApi } from '../api'
import { cameraControlIds, canWrite, capability, commandRequest, commandStateLabel, recordingActions, type CameraControlId } from '../camera-control'
import { formatStorage, formatRelative, readable } from '../format'
import type { CameraControlCenterView, CameraControlSettings, DateTimeOverlayConfiguration, DeviceLiveState } from '../models'
import type { MotionSettingsDeviceReport } from '../models'
import { isAdvancedSensitivityMode, isMotionSetting, motionCapability, motionDurationLabel, motionSettingIds, type MotionSettingId } from '../motion-settings'
import { Icon } from './Icon'
import { StatusBadge } from './StatusBadge'
import { getEffectiveConnectionState } from '../monitoringHealth'

type Props = {
  api: MonitoringApi
  devices: DeviceLiveState[]
  initialDeviceId: string | null
  onSelectDevice: (deviceId: string) => void
  refreshKey: number
  onFailure: (failure: unknown) => void
}

export function CameraControlCenter({ api, devices, initialDeviceId, onSelectDevice, refreshKey, onFailure }: Props) {
  const [deviceId, setDeviceId] = useState(initialDeviceId ?? devices[0]?.deviceId ?? '')
  const [view, setView] = useState<CameraControlCenterView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<CameraControlId | MotionSettingId | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [motionError, setMotionError] = useState<string | null>(null)
  const [theme, setTheme] = useState<'light' | 'dark'>(() =>
    window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark',
  )

  useEffect(() => {
    if (initialDeviceId && initialDeviceId !== deviceId && devices.some((device) => device.deviceId === initialDeviceId)) {
      setDeviceId(initialDeviceId)
      return
    }
    if (!devices.some((device) => device.deviceId === deviceId)) {
      const fallback = initialDeviceId ?? devices[0]?.deviceId ?? ''
      setDeviceId(fallback)
      if (fallback) onSelectDevice(fallback)
    }
  }, [deviceId, devices, initialDeviceId, onSelectDevice])

  const selectDevice = (nextDeviceId: string) => {
    setDeviceId(nextDeviceId)
    onSelectDevice(nextDeviceId)
  }

  const load = useCallback((signal?: AbortSignal) => {
    if (!deviceId) { setView(null); setLoading(false); return Promise.resolve() }
    setLoading(true)
    return api.getCameraControl(deviceId, signal)
      .then(setView)
      .catch(onFailure)
      .finally(() => setLoading(false))
  }, [api, deviceId, onFailure])

  useEffect(() => {
    const abort = new AbortController()
    void load(abort.signal)
    return () => abort.abort()
  }, [load, refreshKey])

  const send = async (
    control: CameraControlId | MotionSettingId,
    value: boolean | number | string | DateTimeOverlayConfiguration,
    expectedVersion?: number,
  ) => {
    if (!deviceId || busy) return
    setBusy(control)
    setNotice(null)
    if (isMotionSetting(control)) setMotionError(null)
    try {
      const command = await api.sendCameraControl(deviceId, commandRequest(control, value, expectedVersion))
      setNotice(`${readable(control)} ${commandStateLabel(command.state).toLowerCase()}.`)
      await load()
    } catch (failure) {
      if (isMotionSetting(control)) {
        setMotionError(failure instanceof Error ? failure.message : 'The Motion setting could not be applied.')
      }
      onFailure(failure)
    } finally {
      setBusy(null)
    }
  }

  const selected = devices.find((device) => device.deviceId === deviceId)
  const state = view?.deviceState
  const settings = state?.settings ?? view?.desiredSettings
  const capabilities = state?.capabilities ?? []
  const recordingState = state?.telemetry.recordingState ?? (state?.telemetry.recording ? 'recording' : 'idle')
  const connection = selected == null ? null : getEffectiveConnectionState(selected)

  return <main className={`camera-control-page camera-control-page--${theme}`} data-theme={theme}>
    <header className="camera-control-heading">
      <div>
        <span className="camera-control-eyebrow">Zero-touch camera</span>
        <h1>Camera Control Center</h1>
        <p>Manage the installed camera remotely without touching the phone.</p>
      </div>
      <div className="camera-control-heading__actions">
        <label className="camera-control-device-picker">Camera
          <select aria-label="Camera device" value={deviceId} onChange={(event) => selectDevice(event.target.value)}>
            {devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.friendlyName}</option>)}
          </select>
        </label>
        <button className="camera-control-theme" onClick={() => setTheme((value) => value === 'dark' ? 'light' : 'dark')} aria-label={`Use ${theme === 'dark' ? 'light' : 'dark'} mode`}>
          <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={18}/>
        </button>
      </div>
    </header>

    {!deviceId ? <EmptyControlState title="No cameras yet" detail="Pair a camera to manage it remotely."/> : loading && !view ? <div className="camera-control-loading"><div className="spinner"/>Reading camera capabilities…</div> : <>
      <section className="camera-control-summary" aria-label="Camera status">
        <StatusTile label="Camera" value={view?.online ? 'Online' : 'Offline'} good={view?.online === true}/>
        <StatusTile label="Live" value={state?.telemetry.streaming ? 'Streaming' : 'Idle'} good={state?.telemetry.streaming === true}/>
        <StatusTile label="Recording" value={readable(recordingState)} good={recordingState === 'recording'}/>
        <StatusTile label="Motion" value={state?.telemetry.motionArmed ? 'Armed' : 'Off'} good={state?.telemetry.motionArmed === true}/>
        <StatusTile label="Battery" value={state?.telemetry.batteryPercent == null ? 'Unknown' : `${state.telemetry.batteryPercent}%${state.telemetry.charging ? ' · charging' : ''}`}/>
        <StatusTile label="Temperature" value={state?.telemetry.temperatureCelsius == null ? 'Unavailable' : `${state.telemetry.temperatureCelsius.toFixed(1)} °C`}/>
        <StatusTile label="Storage" value={formatStorage(state?.telemetry.availableStorageBytes ?? null)}/>
        <StatusTile label="Connection" value={connection?.label ?? readable(state?.telemetry.connectionQuality ?? 'unknown')} good={connection?.isConnected ?? false}/>
      </section>

      {!state ? <EmptyControlState title="Waiting for capability report" detail="Controls become available when this camera reconnects. Saved settings remain on the Hub."/> : settings && <section className="camera-control-layout">
        <div className="camera-control-stack">
          <RecordingControl
            descriptor={capability(capabilities, cameraControlIds.recording)}
            state={recordingState}
            origin={state.telemetry.recordingOrigin ?? 'none'}
            upload={state.telemetry.latestRecordingUpload}
            online={view.online}
            busy={busy === cameraControlIds.recording}
            onStart={() => send(cameraControlIds.recording, recordingActions.start)}
            onStop={() => send(cameraControlIds.recording, recordingActions.stop)}
          />
          <MotionSettingsPanel
            report={state.motionSettings}
            commands={view.recentCommands}
            online={view.online}
            busy={busy}
            error={motionError}
            onChange={(control, value, version) => send(control, value, version)}
          />
          <ControlPanel title="Optics" detail="Lens, zoom, torch, and exposure use the active CameraX pipeline.">
            <ChoiceControl label="Camera" descriptor={capability(capabilities, cameraControlIds.lens)} value={settings.lens} busy={busy === cameraControlIds.lens} onChange={(value) => send(cameraControlIds.lens, value)}/>
            <RangeControl label="Zoom" descriptor={capability(capabilities, cameraControlIds.zoom)} value={settings.zoom} busy={busy === cameraControlIds.zoom} onChange={(value) => send(cameraControlIds.zoom, value)}/>
            <ToggleControl label="Torch" descriptor={capability(capabilities, cameraControlIds.torch)} value={settings.torch} busy={busy === cameraControlIds.torch} onChange={(value) => send(cameraControlIds.torch, value)}/>
            <RangeControl label="Exposure compensation" descriptor={capability(capabilities, cameraControlIds.exposure)} value={settings.exposureCompensation} busy={busy === cameraControlIds.exposure} onChange={(value) => send(cameraControlIds.exposure, value)}/>
          </ControlPanel>

          <ControlPanel title="Video" detail="Resolution, frame rate, bitrate, and quality persist across reconnects and restarts.">
            <ChoiceControl label="Resolution" descriptor={capability(capabilities, cameraControlIds.resolution)} value={settings.resolution} busy={busy === cameraControlIds.resolution} onChange={(value) => send(cameraControlIds.resolution, value)}/>
            <ChoiceControl label="Frames per second" descriptor={capability(capabilities, cameraControlIds.fps)} value={String(settings.framesPerSecond)} numeric busy={busy === cameraControlIds.fps} onChange={(value) => send(cameraControlIds.fps, Number(value))}/>
            <RangeControl label="Bitrate" descriptor={capability(capabilities, cameraControlIds.bitrate)} value={settings.bitrate} busy={busy === cameraControlIds.bitrate} onChange={(value) => send(cameraControlIds.bitrate, value)}/>
            <ChoiceControl label="Quality" descriptor={capability(capabilities, cameraControlIds.quality)} value={settings.quality} busy={busy === cameraControlIds.quality} onChange={(value) => send(cameraControlIds.quality, value)}/>
          </ControlPanel>
          <DateTimeOverlayControl
            descriptor={capability(capabilities, cameraControlIds.dateTimeOverlay)}
            value={settings.dateTimeOverlay ?? DEFAULT_DATE_TIME_OVERLAY}
            busy={busy === cameraControlIds.dateTimeOverlay}
            onApply={(value) => send(cameraControlIds.dateTimeOverlay, value)}
          />
        </div>

        <aside className="camera-control-stack">
          <ControlPanel title="Installed phone" detail="Preview visibility changes only the phone screen; remote video continues.">
            <ChoiceControl label="Phone Preview" descriptor={capability(capabilities, cameraControlIds.preview)} value={settings.preview} busy={busy === cameraControlIds.preview} onChange={(value) => send(cameraControlIds.preview, value)}/>
            <ChoiceControl label="Night profile" descriptor={capability(capabilities, cameraControlIds.nightProfile)} value={settings.nightProfile} busy={busy === cameraControlIds.nightProfile} onChange={(value) => send(cameraControlIds.nightProfile, value)}/>
            <CapabilityInventory capabilities={capabilities}/>
          </ControlPanel>
          <CommandActivity view={view} api={api} onFailure={onFailure} onChanged={() => load()}/>
        </aside>
      </section>}
      {notice && <div className="camera-control-notice" role="status">{notice}</div>}
      <p className="camera-control-freshness">Device state {state ? formatRelative(state.reportedAtUtc) : 'not reported yet'}</p>
    </>}
  </main>
}

function StatusTile({ label, value, good }: { label: string; value: string; good?: boolean }) {
  return <article className="camera-status-tile"><span>{label}</span><strong className={good ? 'camera-status-tile__good' : undefined}>{value}</strong></article>
}

function ControlPanel({ title, detail, children }: { title: string; detail: string; children: React.ReactNode }) {
  return <section className="camera-control-panel"><header><h2>{title}</h2><p>{detail}</p></header><div className="camera-control-fields">{children}</div></section>
}

const DEFAULT_DATE_TIME_OVERLAY: DateTimeOverlayConfiguration = {
  enabled: false,
  dateEnabled: true,
  timeEnabled: true,
  use24HourTime: true,
  position: 'bottomLeft',
}

function DateTimeOverlayControl({ descriptor, value, busy, onApply }: {
  descriptor: Descriptor
  value: DateTimeOverlayConfiguration
  busy: boolean
  onApply: (value: DateTimeOverlayConfiguration) => void
}) {
  const [draft, setDraft] = useState(value)
  useEffect(() => setDraft(value), [value])
  const enabled = canWrite(descriptor) && !busy
  const valid = !draft.enabled || draft.dateEnabled || draft.timeEnabled
  const changed = JSON.stringify(draft) !== JSON.stringify(value)
  const toggle = (field: 'enabled' | 'dateEnabled' | 'timeEnabled' | 'use24HourTime') =>
    setDraft((current) => ({ ...current, [field]: !current[field] }))
  return <ControlPanel title="Date / Time Overlay" detail="One Hub-managed setting is burned into Live video and new recordings, then restored after reconnects.">
    <div className="camera-control-field">
      <span>Overlay<CapabilityMark descriptor={descriptor}/></span>
      <button className={`camera-toggle${draft.enabled ? ' camera-toggle--on' : ''}`} role="switch" aria-label="Date and time overlay" aria-checked={draft.enabled} disabled={!enabled} onClick={() => toggle('enabled')}><i/><b>{draft.enabled ? 'On' : 'Off'}</b></button>
    </div>
    <div className="camera-control-field">
      <span>Content</span>
      <label><input type="checkbox" checked={draft.dateEnabled} disabled={!enabled} onChange={() => toggle('dateEnabled')}/> Date</label>
      <label><input type="checkbox" checked={draft.timeEnabled} disabled={!enabled} onChange={() => toggle('timeEnabled')}/> Time</label>
      {!valid && <small role="alert">Select Date, Time, or disable the overlay.</small>}
    </div>
    <label className="camera-control-field"><span>Time format</span>
      <select aria-label="Overlay time format" value={draft.use24HourTime ? '24' : '12'} disabled={!enabled || !draft.timeEnabled} onChange={(event) => setDraft((current) => ({ ...current, use24HourTime: event.target.value === '24' }))}>
        <option value="24">24-hour</option><option value="12">12-hour</option>
      </select>
    </label>
    <label className="camera-control-field"><span>Position</span>
      <select aria-label="Overlay position" value={draft.position} disabled={!enabled} onChange={(event) => setDraft((current) => ({ ...current, position: event.target.value as DateTimeOverlayConfiguration['position'] }))}>
        <option value="topLeft">Top left</option><option value="topRight">Top right</option><option value="bottomLeft">Bottom left</option><option value="bottomRight">Bottom right</option>
      </select>
    </label>
    <div className="camera-recording-actions">
      <button disabled={!enabled || !valid || !changed} onClick={() => onApply(draft)}>{busy ? 'Saving…' : 'Apply Overlay'}</button>
      <small>{descriptor?.reason ?? 'Changes apply to the installed camera immediately.'}</small>
    </div>
  </ControlPanel>
}

function MotionSettingsPanel({ report, commands, online, busy, error, onChange }: {
  report: MotionSettingsDeviceReport | null
  commands: CameraControlCenterView['recentCommands']
  online: boolean
  busy: CameraControlId | MotionSettingId | null
  error: string | null
  onChange: (control: MotionSettingId, value: boolean | number | string, version: number) => void
}) {
  if (!report) {
    return <ControlPanel title="Motion Detection" detail="Configure the same Motion settings available on the installed phone.">
      <p className="motion-settings-unavailable">Motion settings become available after this camera reconnects and reports its capabilities.</p>
    </ControlPanel>
  }
  const { settings, capabilities, version } = report
  const latest = commands.find((command) => isMotionSetting(command.control))
  const commandApplying = latest != null && ['Queued', 'Executing', 'Retrying', 1, 2, 3].includes(latest.state)
  const localMotionBusy = busy != null && isMotionSetting(busy)
  const motionBusy = localMotionBusy || commandApplying
  const status = localMotionBusy ? 'Saving' : latest ? motionCommandStatus(latest.state) : 'Applied'
  const enabled = motionCapability(capabilities, motionSettingIds.enabled)
  const sensitivity = motionCapability(capabilities, motionSettingIds.sensitivity)
  const advanced = motionCapability(capabilities, motionSettingIds.advancedSensitivity)
  const trigger = motionCapability(capabilities, motionSettingIds.triggerDelay)
  const stop = motionCapability(capabilities, motionSettingIds.stopDelay)
  const cooldown = motionCapability(capabilities, motionSettingIds.cooldown)
  const cadence = motionCapability(capabilities, motionSettingIds.frameInterval)
  const warmup = motionCapability(capabilities, motionSettingIds.warmupFrames)
  const advancedMode = isAdvancedSensitivityMode(settings.sensitivity)
  return <section className="camera-control-panel motion-settings-panel" aria-labelledby="motion-settings-heading">
    <header className="motion-settings-heading">
      <div><h2 id="motion-settings-heading">Motion Detection</h2><p>These values are shared with the Android Motion settings screen and apply live without restarting the camera.</p></div>
      <span className={`motion-apply-state motion-apply-state--${status.toLowerCase()}`}>{status}</span>
    </header>
    {!online && <p className="motion-settings-offline">Camera offline · changes will queue and retry when it reconnects.</p>}
    {error && <p className="motion-settings-error" role="alert">{error}</p>}
    <div className="camera-control-fields">
      <ToggleControl label="Motion detection" descriptor={enabled} value={settings.enabled} busy={motionBusy} onChange={(value) => onChange(motionSettingIds.enabled, value, version)}/>
      <ChoiceControl label="Sensitivity" descriptor={sensitivity} value={settings.sensitivity} busy={motionBusy} onChange={(value) => onChange(motionSettingIds.sensitivity, value, version)}/>
      {advancedMode && <MotionRangeControl label="Advanced sensitivity" descriptor={advanced} value={settings.advancedSensitivity} busy={motionBusy} onApply={(value) => onChange(motionSettingIds.advancedSensitivity, value, version)}/>}
      <ChoiceControl label="Confirm motion after" descriptor={trigger} value={String(settings.triggerDelayMillis)} numeric formatOption={motionDurationLabel} busy={motionBusy} onChange={(value) => onChange(motionSettingIds.triggerDelay, Number(value), version)}/>
      <ChoiceControl label="Continue after last motion" descriptor={stop} value={String(settings.stopDelayMillis)} numeric formatOption={motionDurationLabel} busy={motionBusy} onChange={(value) => onChange(motionSettingIds.stopDelay, Number(value), version)}/>
      <ChoiceControl label="Cooldown before a new event" descriptor={cooldown} value={String(settings.cooldownMillis)} numeric formatOption={motionDurationLabel} busy={motionBusy} onChange={(value) => onChange(motionSettingIds.cooldown, Number(value), version)}/>
    </div>
    <EffectiveMotionConfiguration report={report}/>
    <div className="motion-readonly" aria-label="Motion analyzer information">
      <span>Analyzer cadence <strong>{cadence?.currentValue?.number ?? '—'} ms</strong></span>
      <span>Warmup <strong>{warmup?.currentValue?.number ?? '—'} frames</strong></span>
      <span>Settings version <strong>{version}</strong></span>
    </div>
    <p className="motion-safety-note">Changing Motion settings never silently stops an active recording. Disabling Motion stops new detection while an existing Motion recording finalizes normally.</p>
  </section>
}

function EffectiveMotionConfiguration({ report }: { report: MotionSettingsDeviceReport }) {
  const effective = report.effectiveConfiguration
  if (!effective) return null
  return <div className="motion-effective" aria-label="Effective Motion detector configuration">
    <span>Selected mode <strong>{readable(effective.selectedMode)}</strong></span>
    <span>Configuration <strong>{effective.source === 'custom' ? 'Custom' : 'Preset'}</strong></span>
    <span>Threshold <strong>{effective.threshold.toFixed(3)}</strong></span>
    <span>Changed area <strong>{(effective.changedAreaThreshold * 100).toFixed(1)}%</strong></span>
    <span>Noise tolerance <strong>{effective.noiseTolerance.toFixed(3)}</strong></span>
    <span>Brightness tolerance <strong>{effective.brightnessChangeTolerance.toFixed(3)}</strong></span>
    <span>Confirmation <strong>{effective.requiredPositiveFrames} frames · {readable(effective.confirmationBehavior)}</strong></span>
  </div>
}

function motionCommandStatus(state: CameraControlCenterView['recentCommands'][number]['state']) {
  const label = commandStateLabel(state)
  if (label === 'Queued' || label === 'Executing' || label === 'Retrying') return 'Applying'
  if (label === 'Succeeded') return 'Applied'
  if (label === 'Failed' || label === 'Canceled') return 'Failed'
  return 'Applied'
}

function RecordingControl({ descriptor, state, origin, upload, online, busy, onStart, onStop }: {
  descriptor: Descriptor
  state: string
  origin: string
  upload: NonNullable<CameraControlCenterView['deviceState']>['telemetry']['latestRecordingUpload']
  online: boolean
  busy: boolean
  onStart: () => void
  onStop: () => void
}) {
  const supported = canWrite(descriptor) && online
  const transition = state === 'starting' || state === 'stopping'
  const active = state === 'recording' || state === 'starting'
  const remotelyOwned = origin === 'remote'
  const conflict = active && !remotelyOwned
  const startDisabled = !supported || busy || transition || active
  const stopDisabled = !supported || busy || transition || !active || !remotelyOwned
  const explanation = !supported
    ? descriptor?.reason ?? 'Recording controls become available when the shared camera engine is ready.'
    : conflict
      ? `${readable(origin)} recording currently owns the recorder. Motion mode remains armed.`
      : transition
        ? `Recording is ${state}. Wait for the phone to finish this transition.`
        : active
          ? 'This remote recording is active. Live View remains available on supported devices.'
          : state === 'failed'
            ? 'The previous recording action failed. Starting again is safe.'
            : 'Ready for a remote recording. Motion mode will not be disabled.'
  return <ControlPanel title="Remote recording" detail="Start and safely finalize recordings through the shared Android recording engine.">
    <div className={`camera-recording-state camera-recording-state--${state}`}>
      <span>Recording state</span><strong>{readable(state)}</strong><small>Owner · {readable(origin)}</small>
    </div>
    <div className="camera-recording-actions">
      <button className="camera-recording-button camera-recording-button--start" disabled={startDisabled} onClick={onStart}><Icon name="recording" size={17}/>Start Recording</button>
      <button className="camera-recording-button camera-recording-button--stop" disabled={stopDisabled} onClick={onStop}>Stop Recording</button>
      <small>{explanation}</small>
    </div>
    <div className="camera-upload-state">
      <span>Latest recording</span>
      <strong>{uploadStateLabel(upload?.state)}</strong>
      <small>{upload ? uploadDetail(upload) : 'No finalized recording has reported upload state yet.'}</small>
    </div>
  </ControlPanel>
}

type Descriptor = ReturnType<typeof capability>

function ChoiceControl({ label, descriptor, value, numeric = false, formatOption = readable, busy, onChange }: { label: string; descriptor: Descriptor; value: string; numeric?: boolean; formatOption?: (value: string) => string; busy: boolean; onChange: (value: string) => void }) {
  const enabled = canWrite(descriptor) && !busy
  return <label className="camera-control-field"><span>{label}<CapabilityMark descriptor={descriptor}/></span>
    <select aria-label={label} value={value} disabled={!enabled} onChange={(event) => onChange(numeric ? String(Number(event.target.value)) : event.target.value)}>
      {(descriptor?.allowedValues ?? [value]).map((option) => <option key={option} value={option}>{formatOption(option)}</option>)}
    </select>
    {!enabled && descriptor?.reason && <small>{descriptor.reason}</small>}
  </label>
}

function MotionRangeControl({ label, descriptor, value, busy, onApply }: { label: string; descriptor: Descriptor; value: number; busy: boolean; onApply: (value: number) => void }) {
  const [draft, setDraft] = useState(value)
  useEffect(() => setDraft(value), [value])
  const enabled = canWrite(descriptor) && !busy
  return <div className="camera-control-field motion-range-control"><span>{label}<output>{draft}%</output></span>
    <input aria-label={label} type="range" value={draft} min={descriptor?.minimum ?? 0} max={descriptor?.maximum ?? 100} step={descriptor?.step ?? 1} disabled={!enabled} onChange={(event) => setDraft(Number(event.target.value))}/>
    <button disabled={!enabled || draft === value} onClick={() => onApply(draft)}>{busy ? 'Saving…' : 'Apply'}</button>
    {!enabled && descriptor?.reason && <small>{descriptor.reason}</small>}
  </div>
}

function RangeControl({ label, descriptor, value, busy, onChange }: { label: string; descriptor: Descriptor; value: number; busy: boolean; onChange: (value: number) => void }) {
  const enabled = canWrite(descriptor) && !busy
  return <label className="camera-control-field"><span>{label}<output>{formatControlNumber(value, descriptor?.unit)}</output></span>
    <input type="range" value={value} min={descriptor?.minimum ?? value} max={descriptor?.maximum ?? value} step={descriptor?.step ?? 1} disabled={!enabled} onChange={(event) => onChange(Number(event.target.value))}/>
    {!enabled && descriptor?.reason && <small>{descriptor.reason}</small>}
  </label>
}

function ToggleControl({ label, descriptor, value, busy, onChange }: { label: string; descriptor: Descriptor; value: boolean; busy: boolean; onChange: (value: boolean) => void }) {
  const enabled = canWrite(descriptor) && !busy
  return <div className="camera-control-field"><span>{label}<CapabilityMark descriptor={descriptor}/></span><button className={`camera-toggle${value ? ' camera-toggle--on' : ''}`} role="switch" aria-label={label} aria-checked={value} disabled={!enabled} onClick={() => onChange(!value)}><i/><b>{value ? 'On' : 'Off'}</b></button>{!enabled && descriptor?.reason && <small>{descriptor.reason}</small>}</div>
}

function CapabilityMark({ descriptor }: { descriptor: Descriptor }) {
  return <em>{descriptor?.supported ? descriptor.writable ? 'Remote' : 'Read only' : 'Unsupported'}</em>
}

function CapabilityInventory({ capabilities }: { capabilities: NonNullable<CameraControlCenterView['deviceState']>['capabilities'] }) {
  const reported = useMemo(() => capabilities.filter((item) => !Object.values(cameraControlIds).includes(item.id as CameraControlId)), [capabilities])
  return <div className="camera-capabilities"><h3>Reported capabilities</h3><div>{reported.map((item) => <span key={item.id} className={item.supported ? 'camera-capability camera-capability--supported' : 'camera-capability'}>{readable(item.id)} · {item.supported ? item.writable ? 'remote' : 'read only' : 'unsupported'}</span>)}</div></div>
}

function CommandActivity({ view, api, onFailure, onChanged }: { view: CameraControlCenterView; api: MonitoringApi; onFailure: (failure: unknown) => void; onChanged: () => void }) {
  const active = view.recentCommands.filter((item) => [1, 2, 3, 'Queued', 'Executing', 'Retrying'].includes(item.state))
  return <section className="camera-control-panel camera-command-activity"><header><h2>Command activity</h2><p>One setting command executes at a time for this camera.</p></header>{active.length === 0 ? <p className="camera-command-empty">No commands waiting.</p> : <div>{active.map((command) => <article key={command.commandId}><span><strong>{readable(command.control)}</strong><small>{commandStateLabel(command.state)} · attempt {command.attempts}</small></span>{command.cancelable && <button onClick={() => api.cancelCameraControl(view.deviceId, command.commandId).then(onChanged).catch(onFailure)}>Cancel</button>}</article>)}</div>}</section>
}

function EmptyControlState({ title, detail }: { title: string; detail: string }) {
  return <div className="camera-control-empty"><Icon name="controls" size={34}/><h2>{title}</h2><p>{detail}</p></div>
}

function formatControlNumber(value: number, unit?: string | null) {
  if (unit === 'bps') return `${(value / 1_000_000).toFixed(2)} Mbps`
  if (unit === 'ratio') return `${value.toFixed(1)}×`
  return `${value}${unit ? ` ${unit}` : ''}`
}

function uploadStateLabel(state?: string) {
  if (!state) return 'Local only'
  if (state === 'retrying') return 'Queued'
  return readable(state)
}

function uploadDetail(upload: NonNullable<NonNullable<CameraControlCenterView['deviceState']>['telemetry']['latestRecordingUpload']>) {
  if (upload.state === 'uploading') return `${upload.progressPercent}% uploaded to the Hub.`
  if (upload.state === 'failed') return `Upload failed${upload.lastErrorCode ? ` · ${readable(upload.lastErrorCode)}` : ''}. The local file is preserved.`
  if (upload.state === 'uploaded') return 'Stored by the Hub and available in the Recordings library.'
  return 'The local file is preserved and upload will resume when the Hub is reachable.'
}
