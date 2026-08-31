import { describe, expect, it, vi } from 'vitest'
import type { LiveMediaSession } from './live-view'
import {
  LIVE_WALL_STORAGE_KEY,
  LiveWallOrchestrator,
  loadLiveWallLayout,
  saveLiveWallLayout,
  selectLiveWallQuality,
  type LiveWallConnection,
} from './live-wall'
import type {
  DeviceLiveState,
  LiveIceCandidate,
  LiveSession,
  LiveSessionDescription,
  LiveStatistics,
  LiveViewState,
} from './models'

describe('Live Wall orchestration', () => {
  it('owns independent concurrent sessions for different cameras', async () => {
    const hub = new FakeConnection()
    const media = new Map<string, FakeMediaSession>()
    const wall = new LiveWallOrchestrator(hub, (deviceId) => {
      const value = new FakeMediaSession()
      media.set(deviceId, value)
      return value
    })
    wall.updateDevices([device('device-1'), device('device-2')])

    const results = await wall.startMany(['device-1', 'device-2'])
    media.get('device-1')?.showStream()
    media.get('device-2')?.showStream()

    expect(results.every((result) => result.succeeded)).toBe(true)
    expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(2)
    expect(wall.getSnapshot().tiles['device-1'].state).toBe('streaming')
    expect(wall.getSnapshot().tiles['device-2'].state).toBe('streaming')
    expect(wall.getSnapshot().metrics.activePublishers).toBe(2)
    await wall.destroy()
  })

  it('suppresses parallel starts for the same tile', async () => {
    const hub = new FakeConnection()
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])

    await Promise.all([wall.start('device-1'), wall.start('device-1')])

    expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(1)
    await wall.destroy()
  })

  it('does not report Start Live success until Android acknowledges publisher startup', async () => {
    const hub = new FakeConnection()
    hub.autoAcknowledge = false
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])

    let settled = false
    const pending = wall.start('device-1').then((result) => { settled = true; return result })
    await vi.waitFor(() => expect(hub.invocations.some(([name]) => name === 'SendLiveOffer')).toBe(true))
    expect(settled).toBe(false)
    const sessionId = wall.getSnapshot().tiles['device-1'].sessionId!

    hub.emit('LiveSessionChanged', session('device-1', sessionId, 'connecting', true))

    await expect(pending).resolves.toMatchObject({ succeeded: true })
    expect(wall.getSnapshot().tiles['device-1'].deviceAcknowledged).toBe(true)
    await wall.destroy()
  })

  it('retains an Android acknowledgement that arrives before Create returns', async () => {
    const hub = new FakeConnection()
    hub.autoAcknowledge = false
    hub.acknowledgeDuringCreate = true
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])

    await expect(wall.start('device-1')).resolves.toMatchObject({ succeeded: true })
    expect(wall.getSnapshot().tiles['device-1'].deviceAcknowledged).toBe(true)
    await wall.destroy()
  })

  it('retains the Android publisher failure reason instead of reporting dispatch success', async () => {
    const hub = new FakeConnection()
    hub.autoAcknowledge = false
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])

    const pending = wall.start('device-1')
    await vi.waitFor(() => expect(wall.getSnapshot().tiles['device-1'].sessionId).not.toBeNull())
    const sessionId = wall.getSnapshot().tiles['device-1'].sessionId!
    hub.emit('LiveSessionChanged', {
      ...session('device-1', sessionId, 'failed', true),
      errorCode: 'camera_permission_missing',
    })

    await expect(pending).resolves.toMatchObject({ succeeded: false, code: 'camera_permission_missing' })
    expect(wall.getSnapshot().tiles['device-1'].state).toBe('failed')
    await wall.destroy()
  })

  it('ignores an old session event after a newer tile generation starts', async () => {
    const hub = new FakeConnection()
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])
    await wall.start('device-1')
    const stale = wall.getSnapshot().tiles['device-1'].sessionId!
    await wall.stop('device-1')
    await wall.start('device-1')
    const current = wall.getSnapshot().tiles['device-1'].sessionId!

    hub.emit('LiveSessionChanged', session('device-1', stale, 'failed'))

    expect(current).not.toBe(stale)
    expect(wall.getSnapshot().tiles['device-1'].sessionId).toBe(current)
    expect(wall.getSnapshot().tiles['device-1'].state).not.toBe('failed')
    await wall.destroy()
  })

  it('keeps another camera streaming when one media path fails', async () => {
    const hub = new FakeConnection()
    const media = new Map<string, FakeMediaSession>()
    const wall = new LiveWallOrchestrator(hub, (deviceId) => {
      const value = new FakeMediaSession()
      media.set(deviceId, value)
      return value
    })
    wall.updateDevices([device('device-1'), device('device-2')])
    await wall.startMany(['device-1', 'device-2'])
    media.get('device-1')?.showStream()
    media.get('device-2')?.showStream()

    media.get('device-1')?.changeState('disconnected')
    await Promise.resolve()

    expect(wall.getSnapshot().tiles['device-1'].state).toBe('reconnecting')
    expect(wall.getSnapshot().tiles['device-2'].state).toBe('streaming')
    expect(media.get('device-2')?.closed).toBe(false)
    await wall.destroy()
  })

  it('retains volatile viewer intent while an offline camera recovers', async () => {
    const hub = new FakeConnection()
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1', 'offline')])

    const waiting = await wall.start('device-1')
    expect(waiting.code).toBe('device_recovering')
    expect(wall.getSnapshot().tiles['device-1'].viewerIntent).toBe(true)
    expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(0)

    wall.updateDevices([device('device-1')])
    await vi.waitFor(() => {
      expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(1)
    })
    await wall.destroy()
  })

  it('stops hidden-tab media without persisting active intent across reloads', async () => {
    const hub = new FakeConnection()
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])
    await wall.start('device-1')

    wall.setPageVisible(false)
    await vi.waitFor(() => expect(wall.getSnapshot().tiles['device-1'].sessionId).toBeNull())
    expect(wall.getSnapshot().tiles['device-1'].viewerIntent).toBe(true)
    wall.setPageVisible(true)
    await vi.waitFor(() => {
      expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(2)
    })
    await wall.destroy()
  })

  it('creates clean sessions after a Hub interruption without losing viewer intent', async () => {
    const hub = new FakeConnection()
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])
    await wall.start('device-1')

    wall.setHubAvailable(false)
    expect(wall.getSnapshot().tiles['device-1'].sessionId).toBeNull()
    expect(wall.getSnapshot().tiles['device-1'].state).toBe('reconnecting')
    wall.setHubAvailable(true)

    await vi.waitFor(() => {
      expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(2)
    })
    expect(wall.getSnapshot().tiles['device-1'].viewerIntent).toBe(true)
    await wall.destroy()
  })

  it('cancels an in-flight stale start and waits for cleanup before reconnecting once', async () => {
    const hub = new FakeConnection()
    const pending = deferred<LiveSession>()
    hub.nextCreate = pending.promise
    const wall = new LiveWallOrchestrator(hub, () => new FakeMediaSession())
    wall.updateDevices([device('device-1')])

    const firstStart = wall.start('device-1')
    await vi.waitFor(() => expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(1))
    wall.setHubAvailable(false)
    wall.setHubAvailable(true)
    pending.resolve(session('device-1', 'stale-session', 'connecting'))
    await firstStart

    await vi.waitFor(() => {
      expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(2)
    })
    expect(hub.invocations.filter(([name, sessionId]) => name === 'CloseLiveSession' && sessionId === 'stale-session')).toHaveLength(1)
    expect(wall.getSnapshot().tiles['device-1'].sessionId).not.toBe('stale-session')
    expect(wall.getSnapshot().tiles['device-1'].viewerIntent).toBe(true)
    await wall.destroy()
  })

  it('releases every peer and session across five repeated start-stop cycles', async () => {
    const hub = new FakeConnection()
    const media: FakeMediaSession[] = []
    const wall = new LiveWallOrchestrator(hub, () => {
      const value = new FakeMediaSession()
      media.push(value)
      return value
    })
    wall.updateDevices([device('device-1')])

    for (let cycle = 0; cycle < 5; cycle += 1) {
      await wall.start('device-1')
      await wall.stop('device-1')
    }

    expect(media).toHaveLength(5)
    expect(media.every((value) => value.closed)).toBe(true)
    expect(hub.invocations.filter(([name]) => name === 'CreateLiveSession')).toHaveLength(5)
    expect(hub.invocations.filter(([name]) => name === 'CloseLiveSession')).toHaveLength(5)
    expect(wall.getSnapshot().metrics.activeTileSessions).toBe(0)
    expect(wall.getSnapshot().metrics.activePublishers).toBe(0)
    await wall.destroy()
  })
})

describe('Live Wall layout and profile policy', () => {
  it('uses only profiles reported available and avoids unsupported claims', () => {
    const mediumOnly = [{ id: 'medium' as const, label: 'Medium', available: true, width: 1280, height: 720, framesPerSecond: 30 }]
    expect(selectLiveWallQuality(mediumOnly, 16, false)).toEqual({
      quality: 'medium',
      note: 'Medium is the highest supported profile for this camera',
    })
    expect(selectLiveWallQuality([
      ...mediumOnly,
      { id: 'low', label: 'Low', available: true, width: 640, height: 360, framesPerSecond: 24 } as const,
    ], 6, false)?.quality).toBe('low')
  })

  it('persists layout separately from active Live intent', () => {
    const values = new Map<string, string>()
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => { values.set(key, value) },
    }
    saveLiveWallLayout({
      version: 1,
      size: 4,
      deviceIds: ['device-2', 'device-1', null, null],
      selectedDeviceIds: ['device-1'],
      lastActiveDeviceId: 'device-2',
    }, storage)

    const restored = loadLiveWallLayout(storage)

    expect(values.has(LIVE_WALL_STORAGE_KEY)).toBe(true)
    expect(restored.deviceIds).toEqual(['device-2', 'device-1', null, null])
    expect(JSON.stringify(restored)).not.toContain('viewerIntent')
    expect(JSON.stringify(restored)).not.toContain('sessionId')
  })
})

class FakeConnection implements LiveWallConnection {
  invocations: [string, ...unknown[]][] = []
  handlers = new Map<string, Array<(value: never) => void>>()
  private sequence = 0
  private sessionDevices = new Map<string, string>()
  autoAcknowledge = true
  acknowledgeDuringCreate = false
  nextCreate: Promise<LiveSession> | null = null

  async invoke<T>(methodName: string, ...args: unknown[]): Promise<T> {
    this.invocations.push([methodName, ...args])
    if (methodName === 'GetLiveAvailability') {
      return {
        deviceId: args[0], available: true, unavailableReason: null, capabilities: null,
        qualityOptions: [{ id: 'medium', label: 'Medium', available: true, width: 1280, height: 720, framesPerSecond: 30 }],
        readiness: 'ready', readinessReportedAtUtc: new Date().toISOString(),
      } as T
    }
    if (methodName === 'CreateLiveSession') {
      const request = args[0] as { deviceId: string }
      if (this.nextCreate) {
        const pending = this.nextCreate
        this.nextCreate = null
        return pending as T
      }
      const sessionId = `session-${++this.sequence}`
      this.sessionDevices.set(sessionId, request.deviceId)
      const created = session(request.deviceId, sessionId, 'connecting')
      if (this.acknowledgeDuringCreate) {
        this.emit('LiveSessionChanged', { ...created, deviceAcknowledged: true })
      }
      return created as T
    }
    if (methodName === 'SendLiveOffer' && this.autoAcknowledge) {
      const offer = args[0] as LiveSessionDescription
      const deviceId = this.sessionDevices.get(offer.sessionId) ?? 'device-1'
      queueMicrotask(() => this.emit(
        'LiveSessionChanged',
        session(deviceId, offer.sessionId, 'connecting', true),
      ))
    }
    return undefined as T
  }

  on(methodName: string, handler: (value: never) => void) {
    this.handlers.set(methodName, [...this.handlers.get(methodName) ?? [], handler])
  }

  off(methodName: string, handler: (value: never) => void) {
    this.handlers.set(methodName, (this.handlers.get(methodName) ?? []).filter((value) => value !== handler))
  }

  emit(methodName: string, value: unknown) {
    this.handlers.get(methodName)?.forEach((handler) => handler(value as never))
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

class FakeMediaSession implements LiveMediaSession {
  closed = false
  private state: ((state: LiveViewState) => void) | null = null
  private stream: ((stream: MediaStream) => void) | null = null

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

  showStream() { this.stream?.({} as MediaStream) }
  changeState(state: LiveViewState) { this.state?.(state) }
  async acceptAnswer(_answer: LiveSessionDescription) { return undefined }
  async addIceCandidate(_candidate: LiveIceCandidate) { return undefined }
  async statistics(): Promise<LiveStatistics | null> { return null }
  close() { this.closed = true }
}

function session(deviceId: string, sessionId: string, state: LiveSession['state'], deviceAcknowledged = false): LiveSession {
  const now = new Date().toISOString()
  return {
    sessionId, deviceId, state, quality: 'medium', createdAtUtc: now,
    lastActivityAtUtc: now, expiresAtUtc: now, errorCode: null, deviceAcknowledged,
  }
}

function device(deviceId: string, state: DeviceLiveState['connectionState'] = 'connected'): DeviceLiveState {
  const healthy = state === 'connected'
  return {
    deviceId,
    friendlyName: deviceId,
    platform: 'Android',
    connectionState: state,
    operationalState: healthy ? 'active' : state,
    monitoring: true,
    motion: 'armed',
    recording: 'idle',
    batteryPercent: 80,
    availableStorageBytes: 1_000,
    lastHeartbeatAtUtc: new Date().toISOString(),
    lastSeenAtUtc: new Date().toISOString(),
    snapshotVersion: 1,
    snapshot: null,
    health: {
      overall: healthy ? 'healthy' : state,
      subsystems: [],
      reportedAtUtc: new Date().toISOString(),
      presenceState: healthy ? 'connected' : state,
      isCurrent: healthy,
      outageStartedAtUtc: healthy ? null : new Date().toISOString(),
      outageCause: healthy ? null : 'network',
    },
  }
}
