import type {
  DeviceLiveState,
  LiveIceCandidate,
  LiveQualityId,
  LiveQualityOption,
  LiveSession,
  LiveSessionDescription,
  LiveStatistics,
  LiveViewAvailability,
  LiveReadinessState,
  LiveViewState,
} from './models'
import type { LiveMediaSession } from './live-view'
import { getEffectiveConnectionState, getEffectiveTransportState } from './monitoringHealth'

export const LIVE_WALL_LAYOUTS = [1, 2, 4, 6, 9, 16] as const
export type LiveWallLayoutSize = typeof LIVE_WALL_LAYOUTS[number]
export type LiveTileState = 'idle' | 'connecting' | 'streaming' | 'reconnecting' | 'failed'
export type LiveDeviceState = 'healthy' | 'recovering' | 'offline' | 'action-required' | 'degraded' | 'unknown'

export type LiveTileSnapshot = {
  deviceId: string
  generation: number
  sessionId: string | null
  viewerIntent: boolean
  state: LiveTileState
  deviceState: LiveDeviceState
  stream: MediaStream | null
  statistics: LiveStatistics | null
  quality: LiveQualityId | null
  qualityNote: string | null
  errorCode: string | null
  reconnectCount: number
  startedAt: number | null
  firstFrameAt: number | null
  estimatedBandwidthBitsPerSecond: number | null
  availability: LiveViewAvailability | null
  deviceAcknowledged: boolean
}

export type LiveWallMetrics = {
  activeViewerCount: number
  activeTileSessions: number
  activePublishers: number
  reconnectCount: number
  aggregateEstimatedBandwidthBitsPerSecond: number | null
}

export type LiveWallSnapshot = {
  tiles: Readonly<Record<string, LiveTileSnapshot>>
  metrics: LiveWallMetrics
}

export type LiveWallActionResult = {
  deviceId: string
  succeeded: boolean
  code: string | null
  skipped?: boolean
}

export type LiveWallConnection = {
  invoke<T = unknown>(methodName: string, ...args: unknown[]): Promise<T>
  on(methodName: string, handler: (value: never) => void): void
  off(methodName: string, handler: (value: never) => void): void
}

type InternalTile = LiveTileSnapshot & {
  wallSubscriptionId: string
  transportState: 'connected' | 'recovering' | 'offline'
  media: LiveMediaSession | null
  startTask: Promise<LiveWallActionResult> | null
  stopTask: Promise<void> | null
  retryTimer: number | null
  statsTimer: number | null
  previousBytesReceived: number | null
  previousBytesAt: number | null
  availabilityTask: Promise<LiveViewAvailability> | null
  acknowledgement: DeviceAcknowledgement | null
}

type DeviceAcknowledgement = {
  generation: number
  sessionId: string
  timer: number
  resolve: () => void
  reject: (failure: Error) => void
  promise: Promise<void>
}

type MediaFactory = (deviceId: string) => LiveMediaSession

/**
 * Owns all volatile Live intent and media resources for one browser wall.
 * The Hub session id is the server generation token; the monotonic tile
 * generation additionally suppresses stale promises and browser peer events.
 */
export class LiveWallOrchestrator {
  private readonly tiles = new Map<string, InternalTile>()
  private readonly devices = new Map<string, DeviceLiveState>()
  private readonly listeners = new Set<(snapshot: LiveWallSnapshot) => void>()
  private snapshot: LiveWallSnapshot = emptySnapshot()
  private pageVisible = true
  private hubAvailable = true
  private destroyed = false
  private readonly wallSubscriptionId = createWallSubscriptionId()

  private readonly sessionChanged = (value: never) => this.onSessionChanged(value as LiveSession)
  private readonly answerReceived = (value: never) => this.onAnswer(value as LiveSessionDescription)
  private readonly candidateReceived = (value: never) => this.onCandidate(value as LiveIceCandidate)
  private readonly statisticsUpdated = (value: never) => this.onStatistics(value as LiveStatistics)

  constructor(
    private readonly connection: LiveWallConnection,
    private readonly createMedia: MediaFactory,
  ) {
    connection.on('LiveSessionChanged', this.sessionChanged)
    connection.on('ReceiveLiveAnswer', this.answerReceived)
    connection.on('ReceiveLiveIceCandidate', this.candidateReceived)
    connection.on('LiveStatisticsUpdated', this.statisticsUpdated)
  }

  getSnapshot = () => this.snapshot

  subscribe(listener: (snapshot: LiveWallSnapshot) => void) {
    this.listeners.add(listener)
    listener(this.snapshot)
    return () => this.listeners.delete(listener)
  }

  updateDevices(devices: DeviceLiveState[]) {
    this.devices.clear()
    devices.forEach((device) => this.devices.set(device.deviceId, device))
    for (const [deviceId, tile] of this.tiles) {
      const device = this.devices.get(deviceId)
      if (!device) {
        void this.stop(deviceId, false, 'device_removed')
        continue
      }
      const previousTransport = tile.transportState
      tile.deviceState = effectiveDeviceState(device)
      tile.transportState = effectiveTransportState(device)
      if (tile.transportState === 'offline' || tile.transportState === 'recovering') {
        setTileReadiness(
          tile,
          tile.transportState === 'offline' ? 'unavailable' : 'recovering',
          tile.transportState === 'offline' ? 'device_offline' : 'device_recovering',
        )
        if (tile.viewerIntent) {
          if (!isUnavailableTransport(previousTransport)) {
            this.interruptTile(tile, 'device_unavailable', false)
          }
          tile.state = 'reconnecting'
          if (previousTransport !== tile.transportState) tile.reconnectCount += 1
        }
      } else if (
        tile.viewerIntent
        && !tile.sessionId
        && !tile.startTask
        && this.pageVisible
        && this.hubAvailable
      ) {
        void this.start(deviceId)
      } else {
        void this.loadAvailability(tile)
      }
    }
    this.publish()
  }

  setVisibleDevices(deviceIds: string[]) {
    const visible = new Set(deviceIds.filter(Boolean))
    for (const deviceId of visible) {
      const tile = this.ensureTile(deviceId)
      void this.loadAvailability(tile)
    }
    for (const [deviceId, tile] of this.tiles) {
      if (!visible.has(deviceId) && (tile.viewerIntent || tile.sessionId || tile.media)) {
        void this.stop(deviceId, false, 'tile_hidden')
      }
    }
    this.publish()
  }

  setHubAvailable(available: boolean) {
    if (this.hubAvailable === available) return
    this.hubAvailable = available
    if (!available) {
      for (const tile of this.tiles.values()) {
        if (tile.viewerIntent) {
          this.interruptTile(tile, 'hub_unavailable', true)
          setTileReadiness(tile, 'recovering', 'hub_unavailable')
          tile.state = 'reconnecting'
          tile.reconnectCount += 1
        }
      }
      this.publish()
      return
    }
    for (const tile of this.tiles.values()) {
      if (tile.viewerIntent && !tile.sessionId && canStart(this.devices.get(tile.deviceId)) && this.pageVisible) {
        void this.start(tile.deviceId)
      }
    }
  }

  setPageVisible(visible: boolean) {
    if (this.pageVisible === visible) return
    this.pageVisible = visible
    if (!visible) {
      for (const tile of this.tiles.values()) {
        if (tile.viewerIntent) void this.stop(tile.deviceId, true, 'browser_hidden')
      }
      return
    }
    for (const tile of this.tiles.values()) {
      if (tile.viewerIntent && canStart(this.devices.get(tile.deviceId)) && this.hubAvailable) {
        void this.start(tile.deviceId)
      }
    }
  }

  async start(deviceId: string, fullscreen = false): Promise<LiveWallActionResult> {
    const tile = this.ensureTile(deviceId)
    tile.viewerIntent = true
    if (tile.stopTask) {
      const cleanup = tile.stopTask
      diagnostic('start_waiting_for_cleanup', tile)
      await cleanup.catch(() => undefined)
      if (tile.stopTask === cleanup) tile.stopTask = null
      if (!tile.viewerIntent) return { deviceId, succeeded: false, code: 'viewer_intent_cleared' }
      return this.start(deviceId, fullscreen)
    }
    if (tile.startTask) {
      diagnostic('duplicate_start_suppressed', tile)
      return tile.startTask
    }
    if (tile.sessionId && tile.media) {
      diagnostic('duplicate_start_suppressed', tile)
      return { deviceId, succeeded: true, code: null }
    }
    if (!this.pageVisible || !this.hubAvailable || !canStart(this.devices.get(deviceId))) {
      tile.state = 'reconnecting'
      tile.errorCode = null
      this.publish()
      return { deviceId, succeeded: false, code: 'device_recovering' }
    }

    const generation = ++tile.generation
    tile.state = 'connecting'
    setTileReadiness(tile, 'starting', null)
    tile.errorCode = null
    tile.statistics = null
    tile.startedAt = Date.now()
    tile.firstFrameAt = null
    this.clearRetry(tile)
    this.publish()
    diagnostic('session_start_requested', tile)

    const task = this.startCore(tile, generation, fullscreen)
    tile.startTask = task
    return task.finally(() => {
      if (tile.startTask === task) tile.startTask = null
    })
  }

  private async startCore(
    tile: InternalTile,
    generation: number,
    fullscreen: boolean,
  ): Promise<LiveWallActionResult> {
    try {
      const availability = await this.loadAvailability(tile)
      if (!this.isCurrent(tile, generation) || !tile.viewerIntent) return staleResult(tile.deviceId)
      if (!availability.available) throw codedError(availability.unavailableReason ?? 'device_offline')
      const activeCount = [...this.tiles.values()].filter((value) => value.viewerIntent).length
      const profile = selectLiveWallQuality(availability.qualityOptions, activeCount, fullscreen)
      if (!profile) throw codedError('quality_not_available')
      tile.quality = profile.quality
      tile.qualityNote = profile.note
      setTileReadiness(tile, 'starting', null)
      this.publish()

      const created = await this.connection.invoke<LiveSession>('CreateLiveSession', {
        deviceId: tile.deviceId,
        quality: profile.quality,
      })
      if (!this.isCurrent(tile, generation) || !tile.viewerIntent) {
        await this.connection.invoke('CloseLiveSession', created.sessionId).catch(() => undefined)
        return staleResult(tile.deviceId)
      }
      const earlyAcknowledgement = tile.sessionId === created.sessionId && tile.deviceAcknowledged
      tile.sessionId = created.sessionId
      tile.deviceAcknowledged = earlyAcknowledgement || created.deviceAcknowledged
      setTileReadiness(tile, 'starting', null)
      this.publish()
      const acknowledgement = this.beginDeviceAcknowledgement(tile, generation, created.sessionId)
      await Promise.all([
        this.startMedia(tile, generation, created.sessionId),
        acknowledgement,
      ])
      diagnostic('device_publisher_acknowledged', tile)
      return { deviceId: tile.deviceId, succeeded: true, code: null }
    } catch (failure) {
      if (!this.isCurrent(tile, generation)) return staleResult(tile.deviceId)
      const code = errorCode(failure)
      this.rejectDeviceAcknowledgement(tile, code)
      const failedSessionId = tile.sessionId
      tile.sessionId = null
      tile.media?.close()
      tile.media = null
      tile.stream = null
      this.clearStatistics(tile)
      if (failedSessionId) {
        await this.connection.invoke('CloseLiveSession', failedSessionId).catch(() => undefined)
      }
      tile.startTask = null
      tile.errorCode = code
      tile.state = retryable(code) && tile.viewerIntent ? 'reconnecting' : 'failed'
      setTileReadiness(tile, tile.state === 'reconnecting' ? 'recovering' : 'failed', code)
      if (tile.state === 'failed') tile.viewerIntent = false
      diagnostic('session_start_failed', tile, { code })
      if (tile.viewerIntent && retryable(code)) this.scheduleRetry(tile)
      this.publish()
      return { deviceId: tile.deviceId, succeeded: false, code }
    }
  }

  async stop(deviceId: string, preserveIntent = false, reason = 'operator_stop'): Promise<LiveWallActionResult> {
    const tile = this.ensureTile(deviceId)
    if (!preserveIntent) tile.viewerIntent = false
    if (tile.stopTask) {
      diagnostic('duplicate_stop_suppressed', tile)
      await tile.stopTask
      return { deviceId, succeeded: true, code: null }
    }
    const pendingStart = tile.startTask
    tile.startTask = null
    ++tile.generation
    this.rejectDeviceAcknowledgement(tile, reason)
    this.clearRetry(tile)
    this.clearStatistics(tile)
    tile.media?.close()
    tile.media = null
    tile.stream = null
    tile.statistics = null
    tile.estimatedBandwidthBitsPerSecond = null
    const sessionId = tile.sessionId
    tile.sessionId = null
    tile.deviceAcknowledged = false
    tile.state = preserveIntent ? 'reconnecting' : 'idle'
    tile.errorCode = null
    setTileReadiness(tile, preserveIntent ? 'recovering' : 'initializing', preserveIntent ? reason : null)
    this.publish()
    diagnostic('session_stop_requested', tile, { reason, sessionId })
    if (!sessionId && !pendingStart) return { deviceId, succeeded: true, code: null }
    const task = (async () => {
      await pendingStart?.catch(() => undefined)
      if (sessionId) await this.connection.invoke('CloseLiveSession', sessionId)
    })()
    tile.stopTask = task
    try {
      await task
      void this.loadAvailability(tile)
      return { deviceId, succeeded: true, code: null }
    } catch (failure) {
      const code = errorCode(failure)
      diagnostic('session_cleanup_failed', tile, { code, sessionId })
      return { deviceId, succeeded: false, code }
    } finally {
      if (tile.stopTask === task) tile.stopTask = null
    }
  }

  startMany(deviceIds: string[]) {
    return Promise.all(unique(deviceIds).map((deviceId) => this.start(deviceId)))
  }

  stopMany(deviceIds: string[]) {
    return Promise.all(unique(deviceIds).map((deviceId) => this.stop(deviceId)))
  }

  reportPlaybackFailure(deviceId: string) {
    const tile = this.tiles.get(deviceId)
    if (!tile?.sessionId) return
    void this.recycleFailedMedia(tile, tile.generation, 'browser_decode_failed')
  }

  async destroy() {
    if (this.destroyed) return
    this.destroyed = true
    this.connection.off('LiveSessionChanged', this.sessionChanged)
    this.connection.off('ReceiveLiveAnswer', this.answerReceived)
    this.connection.off('ReceiveLiveIceCandidate', this.candidateReceived)
    this.connection.off('LiveStatisticsUpdated', this.statisticsUpdated)
    await Promise.all([...this.tiles.keys()].map((deviceId) => this.stop(deviceId)))
    this.listeners.clear()
  }

  private async startMedia(tile: InternalTile, generation: number, sessionId: string) {
    tile.media?.close()
    tile.media = this.createMedia(tile.deviceId)
    tile.stream = null
    const media = tile.media
    await media.start(
      sessionId,
      (offer) => this.isCurrent(tile, generation, sessionId)
        ? this.connection.invoke('SendLiveOffer', offer)
        : Promise.resolve(),
      (candidate) => this.isCurrent(tile, generation, sessionId)
        ? this.connection.invoke('SendLiveIceCandidate', candidate)
        : Promise.resolve(),
      (state) => this.onMediaState(tile, generation, sessionId, state),
      (stream) => {
        if (!this.isCurrent(tile, generation, sessionId)) return
        tile.stream = stream
        tile.state = 'streaming'
        setTileReadiness(tile, 'live', null)
        tile.firstFrameAt ??= Date.now()
        diagnostic('first_frame_received', tile, {
          durationMilliseconds: tile.startedAt == null ? null : tile.firstFrameAt - tile.startedAt,
        })
        this.publish()
      },
    )
    if (!this.isCurrent(tile, generation, sessionId)) {
      media.close()
      return
    }
    this.startStatistics(tile, generation, sessionId)
  }

  private onMediaState(tile: InternalTile, generation: number, sessionId: string, state: LiveViewState) {
    if (!this.isCurrent(tile, generation, sessionId)) return
    if (state === 'buffering') tile.state = 'reconnecting'
    else if (state === 'failed' || state === 'disconnected') {
      void this.recycleFailedMedia(tile, generation, 'media_interrupted')
      return
    } else if (state === 'connecting' && !tile.stream) tile.state = 'connecting'
    else if (state === 'connected' && tile.stream) tile.state = 'streaming'
    this.publish()
  }

  private async recycleFailedMedia(tile: InternalTile, generation: number, code: string) {
    if (!this.isCurrent(tile, generation)) return
    tile.state = 'reconnecting'
    tile.errorCode = code
    setTileReadiness(tile, 'recovering', code)
    tile.reconnectCount += 1
    const sessionId = tile.sessionId
    this.rejectDeviceAcknowledgement(tile, code)
    tile.sessionId = null
    tile.deviceAcknowledged = false
    tile.media?.close()
    tile.media = null
    tile.stream = null
    this.clearStatistics(tile)
    this.publish()
    if (sessionId) await this.connection.invoke('CloseLiveSession', sessionId).catch(() => undefined)
    if (tile.viewerIntent) this.scheduleRetry(tile)
  }

  private onSessionChanged(update: LiveSession) {
    const tile = this.tiles.get(update.deviceId)
    if (!tile || !tile.viewerIntent) return
    if (tile.sessionId && tile.sessionId !== update.sessionId) {
      diagnostic('stale_session_event_ignored', tile, { staleSessionId: update.sessionId })
      return
    }
    if (!tile.sessionId && tile.startTask) tile.sessionId = update.sessionId
    if (tile.sessionId !== update.sessionId) return
    tile.deviceAcknowledged = update.deviceAcknowledged
    if (update.deviceAcknowledged
      && (update.state === 'connecting' || update.state === 'negotiating' || update.state === 'connected')) {
      this.resolveDeviceAcknowledgement(tile, update.sessionId)
    }

    if (update.state === 'buffering') {
      const alreadyRecovering = tile.state === 'reconnecting' && tile.errorCode === update.errorCode
      this.interruptTile(tile, 'device_transport_lost', false)
      tile.state = 'reconnecting'
      tile.errorCode = update.errorCode
      setTileReadiness(tile, 'recovering', update.errorCode ?? 'live_session_recovering')
      if (!alreadyRecovering) tile.reconnectCount += 1
    } else if (update.state === 'connecting' && update.errorCode === 'device_reconnected') {
      tile.state = 'reconnecting'
      tile.errorCode = null
      setTileReadiness(tile, 'starting', null)
      const generation = ++tile.generation
      tile.startedAt = Date.now()
      void this.startMedia(tile, generation, update.sessionId).catch((failure) => {
        if (this.isCurrent(tile, generation, update.sessionId)) {
          void this.recycleFailedMedia(tile, generation, errorCode(failure))
        }
      })
    } else if (update.state === 'connected') {
      tile.state = tile.stream ? 'streaming' : 'connecting'
      tile.errorCode = null
      setTileReadiness(tile, tile.stream ? 'live' : 'starting', null)
    } else if (update.state === 'connecting' || update.state === 'negotiating') {
      tile.state = 'connecting'
      setTileReadiness(tile, 'starting', null)
    } else if (update.state === 'failed' || update.state === 'disconnected' || update.state === 'closed') {
      const code = update.errorCode ?? update.state
      this.rejectDeviceAcknowledgement(tile, code)
      tile.media?.close()
      tile.media = null
      tile.stream = null
      tile.sessionId = null
      tile.deviceAcknowledged = false
      this.clearStatistics(tile)
      tile.errorCode = code
      tile.state = tile.viewerIntent && retryable(code) ? 'reconnecting' : update.state === 'closed' ? 'idle' : 'failed'
      setTileReadiness(
        tile,
        tile.state === 'reconnecting' ? 'recovering' : tile.state === 'idle' ? 'initializing' : 'failed',
        code,
      )
      if (tile.state === 'failed') tile.viewerIntent = false
      if (tile.viewerIntent && retryable(code)) this.scheduleRetry(tile)
      void this.loadAvailability(tile)
    }
    this.publish()
  }

  private onAnswer(answer: LiveSessionDescription) {
    const tile = this.findBySession(answer.sessionId)
    if (!tile?.media) return
    const generation = tile.generation
    void tile.media.acceptAnswer(answer).catch((failure) => {
      if (this.isCurrent(tile, generation, answer.sessionId)) {
        void this.recycleFailedMedia(tile, generation, errorCode(failure))
      }
    })
  }

  private onCandidate(candidate: LiveIceCandidate) {
    const tile = this.findBySession(candidate.sessionId)
    if (!tile?.media) return
    const generation = tile.generation
    void tile.media.addIceCandidate(candidate).catch((failure) => {
      if (this.isCurrent(tile, generation, candidate.sessionId)) {
        void this.recycleFailedMedia(tile, generation, errorCode(failure))
      }
    })
  }

  private onStatistics(statistics: LiveStatistics) {
    const tile = this.findBySession(statistics.sessionId)
    if (!tile) return
    this.recordStatistics(tile, statistics)
  }

  private startStatistics(tile: InternalTile, generation: number, sessionId: string) {
    this.clearStatistics(tile)
    tile.statsTimer = window.setInterval(() => {
      if (!this.isCurrent(tile, generation, sessionId) || !tile.media) return
      void tile.media.statistics().then((statistics) => {
        if (!statistics || !this.isCurrent(tile, generation, sessionId)) return
        this.recordStatistics(tile, statistics)
        return this.connection.invoke('ReportLiveStatistics', statistics)
      }).catch(() => undefined)
    }, 5_000)
  }

  private recordStatistics(tile: InternalTile, statistics: LiveStatistics) {
    const sampledAt = Date.parse(statistics.sampledAtUtc)
    if (
      statistics.bytesReceived != null
      && tile.previousBytesReceived != null
      && tile.previousBytesAt != null
      && sampledAt > tile.previousBytesAt
    ) {
      tile.estimatedBandwidthBitsPerSecond = Math.max(
        0,
        Math.round((statistics.bytesReceived - tile.previousBytesReceived) * 8_000 / (sampledAt - tile.previousBytesAt)),
      )
    }
    tile.previousBytesReceived = statistics.bytesReceived
    tile.previousBytesAt = Number.isNaN(sampledAt) ? Date.now() : sampledAt
    tile.statistics = statistics
    if ((statistics.framesPerSecond ?? 0) > 0 && tile.stream) tile.state = 'streaming'
    this.publish()
  }

  private scheduleRetry(tile: InternalTile) {
    if (tile.retryTimer != null || tile.startTask || !tile.viewerIntent || this.destroyed) return
    const delay = [1_000, 2_000, 5_000, 10_000][Math.min(tile.reconnectCount, 3)]
    diagnostic('reconnect_scheduled', tile, { delayMilliseconds: delay })
    tile.retryTimer = window.setTimeout(() => {
      tile.retryTimer = null
      if (
        tile.viewerIntent
        && !tile.sessionId
        && this.pageVisible
        && this.hubAvailable
        && canStart(this.devices.get(tile.deviceId))
      ) {
        void this.start(tile.deviceId)
      }
    }, delay)
  }

  private clearRetry(tile: InternalTile) {
    if (tile.retryTimer != null) window.clearTimeout(tile.retryTimer)
    tile.retryTimer = null
  }

  private clearStatistics(tile: InternalTile) {
    if (tile.statsTimer != null) window.clearInterval(tile.statsTimer)
    tile.statsTimer = null
    tile.previousBytesAt = null
    tile.previousBytesReceived = null
  }

  private beginDeviceAcknowledgement(tile: InternalTile, generation: number, sessionId: string) {
    if (tile.deviceAcknowledged) return Promise.resolve()
    this.rejectDeviceAcknowledgement(tile, 'stale_generation')
    let resolvePromise!: () => void
    let rejectPromise!: (failure: Error) => void
    const promise = new Promise<void>((resolve, reject) => {
      resolvePromise = resolve
      rejectPromise = reject
    })
    const acknowledgement: DeviceAcknowledgement = {
      generation,
      sessionId,
      promise,
      resolve: resolvePromise,
      reject: rejectPromise,
      timer: window.setTimeout(() => {
        if (tile.acknowledgement !== acknowledgement) return
        tile.acknowledgement = null
        rejectPromise(codedError('device_acknowledgement_timeout'))
      }, 15_000),
    }
    tile.acknowledgement = acknowledgement
    return promise
  }

  private resolveDeviceAcknowledgement(tile: InternalTile, sessionId: string) {
    const pending = tile.acknowledgement
    if (!pending || pending.sessionId !== sessionId || pending.generation !== tile.generation) return
    window.clearTimeout(pending.timer)
    tile.acknowledgement = null
    pending.resolve()
  }

  private rejectDeviceAcknowledgement(tile: InternalTile, code: string) {
    const pending = tile.acknowledgement
    if (!pending) return
    window.clearTimeout(pending.timer)
    tile.acknowledgement = null
    pending.reject(codedError(code))
  }

  private loadAvailability(tile: InternalTile): Promise<LiveViewAvailability> {
    if (tile.availabilityTask) return tile.availabilityTask
    if (tile.transportState !== 'connected') {
      const availability = tile.availability ?? unavailableAvailability(
        tile.deviceId,
        tile.transportState === 'offline' ? 'unavailable' : 'recovering',
        tile.transportState === 'offline' ? 'device_offline' : 'device_recovering',
      )
      return Promise.resolve(availability)
    }

    const task = this.connection.invoke<LiveViewAvailability>('GetLiveAvailability', tile.deviceId)
    tile.availabilityTask = task
    void task.then((availability) => {
      if (this.tiles.get(tile.deviceId) !== tile) return
      tile.availability = availability
      if (!tile.viewerIntent && tile.state === 'failed' && availability.readiness === 'ready') {
        tile.state = 'idle'
        tile.errorCode = null
      }
      diagnostic('live_readiness_updated', tile, {
        readiness: availability.readiness,
        reason: availability.unavailableReason,
      })
      this.publish()
    }).catch((failure) => {
      if (this.tiles.get(tile.deviceId) !== tile) return
      const code = errorCode(failure)
      setTileReadiness(tile, 'recovering', code)
      diagnostic('live_readiness_refresh_failed', tile, { code })
      this.publish()
    }).finally(() => {
      if (tile.availabilityTask === task) tile.availabilityTask = null
    })
    return task
  }

  private interruptTile(tile: InternalTile, reason: string, clearSession: boolean) {
    const pendingStart = tile.startTask
    tile.startTask = null
    ++tile.generation
    this.rejectDeviceAcknowledgement(tile, reason)
    this.clearRetry(tile)
    this.clearStatistics(tile)
    tile.media?.close()
    tile.media = null
    tile.stream = null
    tile.statistics = null
    tile.estimatedBandwidthBitsPerSecond = null
    if (clearSession) tile.sessionId = null
    diagnostic('session_interrupted', tile, { reason, clearSession })

    if (!pendingStart) return
    const previousCleanup = tile.stopTask
    const cleanup = (async () => {
      await previousCleanup?.catch(() => undefined)
      await pendingStart.catch(() => undefined)
    })()
    tile.stopTask = cleanup
    void cleanup.finally(() => {
      if (tile.stopTask === cleanup) tile.stopTask = null
    })
  }

  private ensureTile(deviceId: string) {
    const current = this.tiles.get(deviceId)
    if (current) return current
    const tile: InternalTile = {
      deviceId,
      wallSubscriptionId: this.wallSubscriptionId,
      transportState: effectiveTransportState(this.devices.get(deviceId)),
      generation: 0,
      sessionId: null,
      viewerIntent: false,
      state: 'idle',
      deviceState: effectiveDeviceState(this.devices.get(deviceId)),
      stream: null,
      statistics: null,
      quality: null,
      qualityNote: null,
      errorCode: null,
      reconnectCount: 0,
      startedAt: null,
      firstFrameAt: null,
      estimatedBandwidthBitsPerSecond: null,
      availability: null,
      deviceAcknowledged: false,
      media: null,
      startTask: null,
      stopTask: null,
      retryTimer: null,
      statsTimer: null,
      previousBytesReceived: null,
      previousBytesAt: null,
      availabilityTask: null,
      acknowledgement: null,
    }
    this.tiles.set(deviceId, tile)
    return tile
  }

  private findBySession(sessionId: string) {
    return [...this.tiles.values()].find((tile) => tile.sessionId === sessionId)
  }

  private isCurrent(tile: InternalTile, generation: number, sessionId?: string) {
    return !this.destroyed
      && tile.generation === generation
      && (sessionId === undefined || tile.sessionId === sessionId)
  }

  private publish() {
    const tiles = Object.fromEntries(
      [...this.tiles].map(([deviceId, tile]) => [deviceId, publicTile(tile)]),
    )
    const values = Object.values(tiles)
    const bandwidth = values
      .map((tile) => tile.estimatedBandwidthBitsPerSecond)
      .filter((value): value is number => value != null)
    this.snapshot = {
      tiles,
      metrics: {
        activeViewerCount: values.some((tile) => tile.viewerIntent) ? 1 : 0,
        activeTileSessions: values.filter((tile) => tile.sessionId != null).length,
        activePublishers: values.filter((tile) => tile.stream != null).length,
        reconnectCount: values.reduce((total, tile) => total + tile.reconnectCount, 0),
        aggregateEstimatedBandwidthBitsPerSecond: bandwidth.length
          ? bandwidth.reduce((total, value) => total + value, 0)
          : null,
      },
    }
    this.listeners.forEach((listener) => listener(this.snapshot))
  }
}

export function selectLiveWallQuality(
  options: LiveQualityOption[],
  activeStreams: number,
  fullscreen: boolean,
): { quality: LiveQualityId; note: string } | null {
  const available = options.filter((option) => option.available)
  if (!available.length) return null
  const desired: LiveQualityId = fullscreen ? 'high' : activeStreams >= 6 ? 'low' : 'medium'
  const selected = available.find((option) => option.id === desired)
    ?? available.find((option) => option.id === 'medium')
    ?? available[0]
  const note = selected.id === desired
    ? fullscreen ? 'Fullscreen profile' : activeStreams >= 6 ? 'Multi-camera preview profile' : 'Balanced wall profile'
    : `${selected.label} is the highest supported profile for this camera`
  return { quality: selected.id, note }
}

export type SavedLiveWallLayout = {
  version: 1
  size: LiveWallLayoutSize
  deviceIds: Array<string | null>
  selectedDeviceIds: string[]
  lastActiveDeviceId: string | null
}

export const LIVE_WALL_STORAGE_KEY = 'sentricam-live-wall-layout-v1'

export function loadLiveWallLayout(storage: Pick<Storage, 'getItem'> = localStorage): SavedLiveWallLayout {
  try {
    const parsed = JSON.parse(storage.getItem(LIVE_WALL_STORAGE_KEY) ?? '') as Partial<SavedLiveWallLayout>
    if (parsed.version !== 1 || !LIVE_WALL_LAYOUTS.includes(parsed.size as LiveWallLayoutSize)) return defaultLayout()
    const size = parsed.size as LiveWallLayoutSize
    return {
      version: 1,
      size,
      deviceIds: Array.from({ length: size }, (_, index) => parsed.deviceIds?.[index] ?? null),
      selectedDeviceIds: unique(parsed.selectedDeviceIds ?? []),
      lastActiveDeviceId: parsed.lastActiveDeviceId ?? null,
    }
  } catch {
    return defaultLayout()
  }
}

export function saveLiveWallLayout(
  layout: SavedLiveWallLayout,
  storage: Pick<Storage, 'setItem'> = localStorage,
) {
  storage.setItem(LIVE_WALL_STORAGE_KEY, JSON.stringify(layout))
}

function defaultLayout(): SavedLiveWallLayout {
  return { version: 1, size: 2, deviceIds: [null, null], selectedDeviceIds: [], lastActiveDeviceId: null }
}

function publicTile(tile: InternalTile): LiveTileSnapshot {
  return {
    deviceId: tile.deviceId,
    generation: tile.generation,
    sessionId: tile.sessionId,
    viewerIntent: tile.viewerIntent,
    state: tile.state,
    deviceState: tile.deviceState,
    stream: tile.stream,
    statistics: tile.statistics,
    quality: tile.quality,
    qualityNote: tile.qualityNote,
    errorCode: tile.errorCode,
    reconnectCount: tile.reconnectCount,
    startedAt: tile.startedAt,
    firstFrameAt: tile.firstFrameAt,
    estimatedBandwidthBitsPerSecond: tile.estimatedBandwidthBitsPerSecond,
    availability: tile.availability,
    deviceAcknowledged: tile.deviceAcknowledged,
  }
}

function emptySnapshot(): LiveWallSnapshot {
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

function effectiveDeviceState(device?: DeviceLiveState): LiveDeviceState {
  if (!device) return 'unknown'
  const effective = getEffectiveConnectionState(device)
  if (effective.connectionState === 'offline') return 'offline'
  if (effective.connectionState === 'recovering') return 'recovering'
  if (effective.connectionState === 'actionRequired') return 'action-required'
  if (effective.connectionState === 'degraded') return 'degraded'
  if (effective.isHealthy) return 'healthy'
  return 'unknown'
}

function canStart(device?: DeviceLiveState) {
  if (!device) return false
  return getEffectiveTransportState(device).isConnected
}

function effectiveTransportState(device?: DeviceLiveState): 'connected' | 'recovering' | 'offline' {
  if (!device) return 'offline'
  const state = getEffectiveTransportState(device).connectionState
  return state === 'recovering' ? 'recovering' : state === 'offline' ? 'offline' : 'connected'
}

function isUnavailableTransport(state: InternalTile['transportState']) {
  return state === 'offline' || state === 'recovering'
}

function createWallSubscriptionId() {
  return globalThis.crypto?.randomUUID?.()
    ?? `wall-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function unique(values: string[]) {
  return [...new Set(values.filter(Boolean))]
}

function setTileReadiness(tile: InternalTile, readiness: LiveReadinessState, reason: string | null) {
  tile.availability = {
    ...(tile.availability ?? unavailableAvailability(tile.deviceId, readiness, reason)),
    available: readiness === 'ready',
    unavailableReason: reason,
    readiness,
    readinessReportedAtUtc: new Date().toISOString(),
  }
}

function unavailableAvailability(
  deviceId: string,
  readiness: LiveReadinessState,
  reason: string | null,
): LiveViewAvailability {
  return {
    deviceId,
    available: readiness === 'ready',
    unavailableReason: reason,
    capabilities: null,
    qualityOptions: [],
    readiness,
    readinessReportedAtUtc: null,
  }
}

function retryable(code: string) {
  return ![
    'session_conflict',
    'quality_not_available',
    'unsupported_live_protocol',
    'operator_identity_missing',
    'session_not_owned',
    'camera_permission_missing',
    'foreground_camera_unavailable',
    'foreground_camera_start_failed',
    'camera_busy',
    'selected_camera_unavailable',
  ].includes(code)
}

function errorCode(failure: unknown) {
  const message = failure instanceof Error ? failure.message : String(failure ?? '')
  return [
    'device_offline',
    'device_recovering',
    'session_conflict',
    'session_timeout',
    'device_acknowledgement_timeout',
    'stale_device_connection',
    'live_capabilities_stale',
    'camera_permission_missing',
    'foreground_camera_unavailable',
    'foreground_camera_start_failed',
    'camera_busy',
    'camera_initializing',
    'camera_publisher_start_failed',
    'camera_publisher_initialization_failed',
    'publisher_start_failed',
    'webrtc_session_failed',
    'webrtc_session_creation_failed',
    'selected_camera_unavailable',
    'camera_unavailable',
    'quality_not_available',
    'operator_identity_missing',
    'session_not_owned',
    'media_interrupted',
    'live_recovery_failed',
  ].find((code) => message.includes(code)) ?? 'live_start_failed'
}

function codedError(code: string) {
  return new Error(code)
}

function staleResult(deviceId: string): LiveWallActionResult {
  return { deviceId, succeeded: false, code: 'stale_generation' }
}

function diagnostic(event: string, tile: InternalTile, detail: Record<string, unknown> = {}) {
  if (!import.meta.env.DEV) return
  const payload = {
    event,
    atUtc: new Date().toISOString(),
    wallSubscriptionId: tile.wallSubscriptionId,
    deviceId: tile.deviceId,
    sessionId: tile.sessionId,
    generation: tile.generation,
    state: tile.state,
    readiness: tile.availability?.readiness ?? 'initializing',
    ...detail,
  }
  console.info('[SentriCam Live Wall]', payload)
  window.dispatchEvent(new CustomEvent('sentricam-live-wall-diagnostic', { detail: payload }))
}
