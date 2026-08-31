import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { useState } from 'react'
import { CameraControlCenter } from './CameraControlCenter'
import type { MonitoringApi } from '../api'
import type { CameraControlCenterView, DeviceLiveState } from '../models'

describe('Camera Control Center', () => {
  it('renders dynamic state and disables controls the device reports read-only', async () => {
    const api = apiWith(centerView())
    render(<CameraControlCenter api={api} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    expect(await screen.findByRole('heading', { name: 'Camera Control Center' })).toBeVisible()
    expect(await screen.findByText('80% · charging')).toBeVisible()
    expect(screen.getByRole('combobox', { name: 'Camera' })).toBeDisabled()
    expect(screen.getByText(/CameraX does not expose camera switching/i)).toBeVisible()
  })

  it('sends typed commands only through the shared API client', async () => {
    const api = apiWith(centerView())
    render(<CameraControlCenter api={api} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)
    await screen.findByText('Optics')

    fireEvent.click(screen.getByRole('switch', { name: 'Torch' }))

    await waitFor(() => expect(api.sendCameraControl).toHaveBeenCalledWith(
      device().deviceId,
      expect.objectContaining({ control: 'torch', value: { boolean: true, number: null, text: null } }),
    ))
  })

  it('submits one authoritative Date and Time Overlay configuration', async () => {
    const api = apiWith(centerView())
    render(<CameraControlCenter api={api} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    const overlaySwitch = await screen.findByRole('switch', { name: 'Date and time overlay' })
    fireEvent.click(overlaySwitch)
    await waitFor(() => expect(overlaySwitch).toBeChecked())
    fireEvent.change(screen.getByLabelText('Overlay time format'), { target: { value: '12' } })
    fireEvent.change(screen.getByLabelText('Overlay position'), { target: { value: 'topRight' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply Overlay' }))

    await waitFor(() => expect(api.sendCameraControl).toHaveBeenCalledWith(
      device().deviceId,
      expect.objectContaining({
        control: 'dateTimeOverlay',
        value: {
          boolean: null, number: null, text: null,
          dateTimeOverlay: { enabled: true, dateEnabled: true, timeEnabled: true, use24HourTime: false, position: 'topRight' },
        },
      }),
    ))
  })

  it('supports explicit light and dark modes on the new screen', async () => {
    const api = apiWith(centerView())
    const { container } = render(<CameraControlCenter api={api} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)
    await screen.findByText('Optics')
    const page = container.querySelector('.camera-control-page')!
    const initial = page.getAttribute('data-theme')

    fireEvent.click(screen.getByRole('button', { name: `Use ${initial === 'dark' ? 'light' : 'dark'} mode` }))

    expect(page.getAttribute('data-theme')).not.toBe(initial)
  })

  it('starts recording through the unified Camera Control command API', async () => {
    const api = apiWith(centerView())
    render(<CameraControlCenter api={api} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    fireEvent.click(await screen.findByRole('button', { name: 'Start Recording' }))

    await waitFor(() => expect(api.sendCameraControl).toHaveBeenCalledWith(
      device().deviceId,
      expect.objectContaining({ control: 'recording', value: { boolean: null, number: null, text: 'start' } }),
    ))
  })

  it('renders every Android Motion setting and submits the reported concurrency version', async () => {
    const api = apiWith(centerView())
    render(<CameraControlCenter api={api} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    expect(await screen.findByRole('heading', { name: 'Motion Detection' })).toBeVisible()
    expect(screen.getByRole('switch', { name: 'Motion detection' })).toBeChecked()
    expect(screen.getByText('Analyzer cadence')).toBeVisible()
    expect(screen.getByText('Warmup')).toBeVisible()

    fireEvent.change(screen.getByLabelText('Sensitivity'), { target: { value: 'high' } })

    await waitFor(() => expect(api.sendCameraControl).toHaveBeenCalledWith(
      device().deviceId,
      expect.objectContaining({
        control: 'motion.sensitivity',
        value: { boolean: null, number: null, text: 'high' },
        expectedVersion: 7,
      }),
    ))
  })

  it('hides retained Advanced sensitivity under presets and shows the effective preset parameters', async () => {
    render(<CameraControlCenter api={apiWith(centerView())} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    expect(await screen.findByLabelText('Effective Motion detector configuration')).toBeVisible()
    expect(screen.queryByLabelText('Advanced sensitivity')).not.toBeInTheDocument()
    expect(screen.getByText('Preset')).toBeVisible()
    expect(screen.getByText('0.075')).toBeVisible()
  })

  it('shows and applies retained Advanced sensitivity only in Advanced mode', async () => {
    const view = centerView()
    view.deviceState!.motionSettings!.settings.sensitivity = 'advanced'
    view.deviceState!.motionSettings!.settings.advancedSensitivity = 82
    view.deviceState!.motionSettings!.effectiveConfiguration = {
      selectedMode: 'advanced', source: 'custom', threshold: 0.0498, requiredPositiveFrames: 2,
      noiseTolerance: 0.00942, changedAreaThreshold: 0.0303, brightnessChangeTolerance: 0.2748,
      confirmationBehavior: 'fast',
    }
    const api = apiWith(view)
    render(<CameraControlCenter api={api} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    const slider = await screen.findByLabelText('Advanced sensitivity')
    expect(slider).toBeVisible()
    expect(slider).toHaveValue('82')
    expect(screen.getByText('Custom')).toBeVisible()
    fireEvent.change(slider, { target: { value: '84' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))

    await waitFor(() => expect(api.sendCameraControl).toHaveBeenCalledWith(
      device().deviceId,
      expect.objectContaining({
        control: 'motion.advancedSensitivity',
        value: { boolean: null, number: 84, text: null },
        expectedVersion: 7,
      }),
    ))
  })

  it('shows structured Motion apply state and disables engine-managed settings', async () => {
    const view = centerView()
    view.recentCommands = [{
      commandId: 'motion-command', deviceId: view.deviceId, control: 'motion.cooldownMillis',
      value: { boolean: null, number: 10_000, text: null }, state: 'Executing', attempts: 1,
      cancelable: true, retriable: true, correlationId: 'motion-one', actorId: 'operator', resultCode: null,
      createdAtUtc: new Date().toISOString(), completedAtUtc: null, expectedVersion: 7,
    }]
    render(<CameraControlCenter api={apiWith(view)} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    expect(await screen.findByText('Applying')).toBeVisible()
    expect(screen.getByText(/apply live without restarting the camera/i)).toBeVisible()
    expect(view.deviceState!.motionSettings!.capabilities.find((item) => item.id === 'motion.frameIntervalMillis')?.writable).toBe(false)
  })

  it('shows recording and upload lifecycle and enables only a remotely owned stop', async () => {
    const view = centerView()
    view.deviceState!.telemetry.recording = true
    view.deviceState!.telemetry.recordingState = 'recording'
    view.deviceState!.telemetry.recordingOrigin = 'remote'
    view.deviceState!.telemetry.latestRecordingUpload = {
      clientRecordingId: 'local-1', state: 'uploaded', progressPercent: 100, lastErrorCode: null,
      serverRecordingId: 'server-1', recordedAtUtc: new Date().toISOString(), uploadedAtUtc: new Date().toISOString(),
    }
    render(<CameraControlCenter api={apiWith(view)} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    expect(await screen.findByText('Uploaded')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Start Recording' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop Recording' })).toBeEnabled()
  })

  it('explains motion ownership without disabling motion mode', async () => {
    const view = centerView()
    view.deviceState!.telemetry.recording = true
    view.deviceState!.telemetry.recordingState = 'recording'
    view.deviceState!.telemetry.recordingOrigin = 'motion'
    render(<CameraControlCenter api={apiWith(view)} devices={[device()]} initialDeviceId={device().deviceId} onSelectDevice={vi.fn()} refreshKey={0} onFailure={vi.fn()}/>)

    expect(await screen.findByText(/Motion recording currently owns the recorder/i)).toBeVisible()
    expect(screen.getByRole('button', { name: 'Start Recording' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Stop Recording' })).toBeDisabled()
    expect(screen.getByText(/Motion mode remains armed/i)).toBeVisible()
  })

  it('uses responsive shared styles and has no viewport-specific camera logic', () => {
    const styles = readFileSync('src/styles.css', 'utf8')
    const component = readFileSync('src/components/CameraControlCenter.tsx', 'utf8')

    expect(styles).toContain('@media (max-width: 760px)')
    expect(styles).toContain('.camera-control-layout')
    expect(component).not.toMatch(/innerWidth|screen\.orientation|DashboardCommand/)
  })

  it('publishes camera selection so realtime refreshes and remounts preserve the target device', async () => {
    const first = device()
    const second = { ...device(), deviceId: 'second-device', friendlyName: 'Back Door' }
    const firstView = centerView()
    const secondView = { ...centerView(), deviceId: second.deviceId }
    const api = apiWith(firstView)
    api.getCameraControl = vi.fn().mockImplementation((deviceId) => Promise.resolve(deviceId === second.deviceId ? secondView : firstView))

    function Harness() {
      const [selectedDeviceId, setSelectedDeviceId] = useState(first.deviceId)
      const [refreshKey, setRefreshKey] = useState(0)
      return <>
        <CameraControlCenter api={api} devices={[first, second]} initialDeviceId={selectedDeviceId} onSelectDevice={setSelectedDeviceId} refreshKey={refreshKey} onFailure={vi.fn()}/>
        <button onClick={() => setRefreshKey((value) => value + 1)}>Realtime refresh</button>
      </>
    }

    render(<Harness/>)
    fireEvent.change(await screen.findByRole('combobox', { name: 'Camera device' }), { target: { value: second.deviceId } })
    fireEvent.click(screen.getByRole('button', { name: 'Realtime refresh' }))

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Camera device' })).toHaveValue(second.deviceId))
    expect(api.getCameraControl).toHaveBeenLastCalledWith(second.deviceId, expect.any(AbortSignal))
  })
})

function apiWith(view: CameraControlCenterView) {
  return {
    getCameraControl: vi.fn().mockResolvedValue(view),
    sendCameraControl: vi.fn().mockImplementation((_deviceId, request) => Promise.resolve({
      commandId: 'command-1', deviceId: view.deviceId, control: request.control, value: request.value,
      state: 'Queued', attempts: 0, cancelable: true, retriable: true, correlationId: request.correlationId,
      actorId: 'operator', resultCode: null, createdAtUtc: new Date().toISOString(), completedAtUtc: null,
      expectedVersion: request.expectedVersion ?? null,
    })),
    cancelCameraControl: vi.fn().mockResolvedValue(undefined),
  } as unknown as MonitoringApi
}

function device(): DeviceLiveState {
  return {
    deviceId: '8cc58e6b-9519-40cb-b3b8-ca73c7a4a3af', friendlyName: 'Front Door', platform: 'Android',
    connectionState: 'connected', operationalState: 'Ready', monitoring: true, motion: 'armed', recording: 'idle',
    batteryPercent: 80, availableStorageBytes: 10_000, lastHeartbeatAtUtc: new Date().toISOString(), lastSeenAtUtc: new Date().toISOString(),
    snapshotVersion: 1, snapshot: {},
  }
}

function centerView(): CameraControlCenterView {
  const settings = { lens: 'back', zoom: 1, torch: false, exposureCompensation: 0, preview: 'visible', framesPerSecond: 30, resolution: '1280x720', bitrate: 2_500_000, quality: 'medium', nightProfile: 'auto', dateTimeOverlay: { enabled: false, dateEnabled: true, timeEnabled: true, use24HourTime: true, position: 'bottomLeft' as const } }
  return {
    deviceId: device().deviceId, online: true, desiredSettings: settings, updatedAtUtc: new Date().toISOString(), recentCommands: [],
    deviceState: {
      deviceId: device().deviceId, settings, reportedAtUtc: new Date().toISOString(),
      telemetry: { cameraOnline: true, streaming: true, recording: false, recordingState: 'idle', recordingOrigin: 'none', motionArmed: true, batteryPercent: 80, temperatureCelsius: 32.1, availableStorageBytes: 10_000, charging: true, connectionQuality: 'good', previewAvailable: true, audioAvailable: true, latestRecordingUpload: null },
      motionSettings: {
        version: 7,
        settings: { enabled: true, sensitivity: 'medium', advancedSensitivity: 50, triggerDelayMillis: 1_000, stopDelayMillis: 10_000, cooldownMillis: 5_000 },
        effectiveConfiguration: {
          selectedMode: 'medium', source: 'preset', threshold: 0.075, requiredPositiveFrames: 3,
          noiseTolerance: 0.014, changedAreaThreshold: 0.05, brightnessChangeTolerance: 0.22,
          confirmationBehavior: 'balanced',
        },
        capabilities: [
          motionDescriptor('motion.enabled', { boolean: true, number: null, text: null }),
          motionDescriptor('motion.sensitivity', { boolean: null, number: null, text: 'medium' }, { allowedValues: ['low', 'medium', 'high', 'advanced'] }),
          motionDescriptor('motion.advancedSensitivity', { boolean: null, number: 50, text: null }, { minimum: 0, maximum: 100, step: 1, unit: 'percent' }),
          motionDescriptor('motion.triggerDelayMillis', { boolean: null, number: 1_000, text: null }, { allowedValues: ['0', '1000', '2000'], unit: 'milliseconds' }),
          motionDescriptor('motion.stopDelayMillis', { boolean: null, number: 10_000, text: null }, { allowedValues: ['5000', '10000', '20000', '30000'], unit: 'milliseconds' }),
          motionDescriptor('motion.cooldownMillis', { boolean: null, number: 5_000, text: null }, { allowedValues: ['3000', '5000', '10000'], unit: 'milliseconds' }),
          motionDescriptor('motion.frameIntervalMillis', { boolean: null, number: 150, text: null }, { writable: false, unit: 'milliseconds', reason: 'Managed by the Motion engine.' }),
          motionDescriptor('motion.warmupFrameCount', { boolean: null, number: 6, text: null }, { writable: false, unit: 'frames', reason: 'Managed by the Motion engine.' }),
        ],
      },
      capabilities: [
        { id: 'recording', supported: true, writable: true, currentValue: { boolean: null, number: null, text: 'idle' }, minimum: null, maximum: null, step: null, allowedValues: ['start', 'stop'], unit: null, reason: null },
        { id: 'lens', supported: true, writable: false, currentValue: { boolean: null, number: null, text: 'back' }, minimum: null, maximum: null, step: null, allowedValues: ['back'], unit: null, reason: 'CameraX does not expose camera switching on this device.' },
        { id: 'zoom', supported: true, writable: true, currentValue: { boolean: null, number: 1, text: null }, minimum: 1, maximum: 4, step: .1, allowedValues: null, unit: 'ratio', reason: null },
        { id: 'torch', supported: true, writable: true, currentValue: { boolean: false, number: null, text: null }, minimum: null, maximum: null, step: null, allowedValues: null, unit: null, reason: null },
        { id: 'exposureCompensation', supported: true, writable: true, currentValue: { boolean: null, number: 0, text: null }, minimum: -3, maximum: 3, step: 1, allowedValues: null, unit: 'index', reason: null },
        { id: 'preview', supported: true, writable: true, currentValue: { boolean: null, number: null, text: 'visible' }, minimum: null, maximum: null, step: null, allowedValues: ['visible', 'hidden', 'dimmed'], unit: null, reason: null },
        { id: 'framesPerSecond', supported: true, writable: true, currentValue: { boolean: null, number: 30, text: null }, minimum: 15, maximum: 30, step: 1, allowedValues: ['15', '30'], unit: 'fps', reason: null },
        { id: 'resolution', supported: true, writable: true, currentValue: { boolean: null, number: null, text: '1280x720' }, minimum: null, maximum: null, step: null, allowedValues: ['640x480', '1280x720'], unit: null, reason: null },
        { id: 'bitrate', supported: true, writable: true, currentValue: { boolean: null, number: 2500000, text: null }, minimum: 250000, maximum: 12000000, step: 250000, allowedValues: null, unit: 'bps', reason: null },
        { id: 'quality', supported: true, writable: true, currentValue: { boolean: null, number: null, text: 'medium' }, minimum: null, maximum: null, step: null, allowedValues: ['auto', 'low', 'medium', 'high'], unit: null, reason: null },
        { id: 'nightProfile', supported: true, writable: true, currentValue: { boolean: null, number: null, text: 'auto' }, minimum: null, maximum: null, step: null, allowedValues: ['auto', 'day', 'night', 'indoor', 'outdoor'], unit: null, reason: null },
        { id: 'dateTimeOverlay', supported: true, writable: true, currentValue: { boolean: null, number: null, text: null, dateTimeOverlay: settings.dateTimeOverlay }, minimum: null, maximum: null, step: null, allowedValues: null, unit: null, reason: null },
      ],
    },
  }
}

function motionDescriptor(
  id: string,
  currentValue: { boolean: boolean | null; number: number | null; text: string | null },
  overrides: Partial<NonNullable<NonNullable<CameraControlCenterView['deviceState']>['motionSettings']>['capabilities'][number]> = {},
) {
  return {
    id, supported: true, writable: true, currentValue, minimum: null, maximum: null, step: null,
    allowedValues: null, unit: null, requiresCameraRestart: false, reason: null, ...overrides,
  }
}
