import { getEffectiveConnectionState, getEffectiveTransportState, selectMoreCriticalDeviceState } from './monitoringHealth'
import type { DeviceLiveState } from './models'

describe('getEffectiveConnectionState', () => {
  const current = new Date().toISOString()
  const baseDevice: DeviceLiveState = {
    deviceId: 'device-1',
    friendlyName: 'Test camera',
    platform: 'Android',
    connectionState: 'connected',
    operationalState: 'monitoring',
    monitoring: true,
    motion: 'idle',
    recording: 'idle',
    batteryPercent: 84,
    availableStorageBytes: 512,
    lastHeartbeatAtUtc: current,
    lastSeenAtUtc: current,
    snapshotVersion: 1,
    snapshot: null,
    health: {
      overall: 'healthy',
      subsystems: [],
      reportedAtUtc: current,
      presenceState: 'connected',
      isCurrent: true,
      outageStartedAtUtc: null,
      outageCause: null,
    },
  }

  it('maps connected fresh health to a healthy badge', () => {
    const state = getEffectiveConnectionState(baseDevice)
    expect(state).toMatchObject({ label: 'Healthy', tone: 'positive', isOffline: false })
  })

  it('keeps transport connected while an operational subsystem is recovering', () => {
    const value = {
      ...baseDevice,
      health: { ...baseDevice.health!, overall: 'recovering', presenceState: 'connected', isCurrent: true },
    }

    expect(getEffectiveConnectionState(value).connectionState).toBe('recovering')
    expect(getEffectiveTransportState(value).connectionState).toBe('connected')
  })

  it('maps transport loss to animated recovering before presence expires', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      connectionState: 'recovering',
      health: {
        ...baseDevice.health!,
        overall: 'recovering',
        presenceState: 'recovering',
        isCurrent: false,
        outageStartedAtUtc: current,
        outageCause: 'connection_lost',
      },
    })

    expect(state).toMatchObject({
      label: 'Recovering', tone: 'warning', isRecovering: true, isOffline: false, pulse: true,
    })
  })

  it('maps disconnected presence to offline', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      connectionState: 'offline',
      health: { ...baseDevice.health!, presenceState: 'offline', isCurrent: false, outageStartedAtUtc: '2026-08-03T00:01:00Z', outageCause: 'connection_lost', overall: 'offline', subsystems: [], reportedAtUtc: '2026-08-03T00:00:00Z' },
    })

    expect(state.label).toBe('Offline')
    expect(state.isOffline).toBe(true)
    expect(state.tone).toBe('danger')
  })

  it('maps offline connection transport to offline even if presence is still connected', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      connectionState: 'offline',
      health: { ...baseDevice.health!, isCurrent: true, presenceState: 'connected', overall: 'healthy', reportedAtUtc: '2026-08-03T00:00:00Z', subsystems: [] },
    })

    expect(state.label).toBe('Offline')
    expect(state.isOffline).toBe(true)
    expect(state.tone).toBe('danger')
  })

  it('maps stale health to recovering when connection is present', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      health: { ...baseDevice.health!, isCurrent: false, presenceState: 'connected', overall: 'recovering', reportedAtUtc: '2026-08-03T00:00:00Z', subsystems: [] },
    })

    expect(state.label).toBe('Recovering')
    expect(state.isRecovering).toBe(true)
    expect(state.isOffline).toBe(false)
  })

  it('treats missing freshness as not-current', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      health: {
        ...baseDevice.health!,
        // isCurrent intentionally omitted / missing in payload; this must not be treated as true.
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        isCurrent: undefined as any,
        presenceState: 'connected',
        overall: 'healthy',
        reportedAtUtc: '2026-08-03T00:00:00Z',
        subsystems: [],
      },
    })

    expect(state.label).toBe('Recovering')
    expect(state.isRecovering).toBe(true)
    expect(state.isOffline).toBe(false)
  })

  it('still relies on fallback health freshness when presence is not explicitly connected', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      health: {
        ...baseDevice.health!,
        presenceState: null as unknown as string,
        isCurrent: true,
        overall: 'healthy',
        reportedAtUtc: '2026-08-03T00:00:00Z',
        subsystems: [],
      },
    })

    expect(state.label).toBe('Healthy')
    expect(state.isOffline).toBe(false)
  })

  it('keeps healthy-looking card from being forced offline by unknown presence if heartbeat is current', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      health: {
        ...baseDevice.health!,
        presenceState: null as unknown as string,
        isCurrent: true,
        overall: 'healthy',
        reportedAtUtc: '2026-08-03T00:00:00Z',
        subsystems: [],
      },
    })

    expect(state.label).toBe('Healthy')
    expect(state.isOffline).toBe(false)
  })

  it('maps expired heartbeat to offline even if presence is still connected', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      lastHeartbeatAtUtc: '2026-08-03T00:00:00Z',
      health: { ...baseDevice.health!, isCurrent: false, presenceState: 'connected', overall: 'recovering', reportedAtUtc: '2026-08-03T00:00:00Z', subsystems: [] },
    }, new Date('2026-08-03T00:00:50Z').getTime())

    expect(state.label).toBe('Offline')
    expect(state.isOffline).toBe(true)
    expect(state.tone).toBe('danger')
  })

  it('keeps stale-but-recent heartbeat as recovering when health is stale', () => {
    const state = getEffectiveConnectionState({
      ...baseDevice,
      lastHeartbeatAtUtc: '2026-08-03T00:00:00Z',
      health: { ...baseDevice.health!, isCurrent: false, presenceState: 'connected', overall: 'recovering', reportedAtUtc: '2026-08-03T00:00:00Z', subsystems: [] },
    }, new Date('2026-08-03T00:00:20Z').getTime())

    expect(state.label).toBe('Recovering')
    expect(state.isOffline).toBe(false)
  })

  it('selects an offline fleet update over stale historical healthy detail state for the same deviceId', () => {
    const offlineUpdate: DeviceLiveState = {
      ...baseDevice,
      connectionState: 'offline',
      health: {
        ...baseDevice.health!,
        overall: 'offline',
        presenceState: 'offline',
        isCurrent: false,
      },
    }

    const selected = selectMoreCriticalDeviceState(baseDevice, offlineUpdate)

    expect(selected).toBe(offlineUpdate)
    expect(getEffectiveConnectionState(selected)).toMatchObject({ label: 'Offline', tone: 'danger' })
  })

  it('does not let a stale healthy fleet snapshot override offline detail state', () => {
    const offlineDetail: DeviceLiveState = {
      ...baseDevice,
      connectionState: 'offline',
      health: {
        ...baseDevice.health!,
        overall: 'offline',
        presenceState: 'offline',
        isCurrent: false,
      },
    }

    expect(selectMoreCriticalDeviceState(offlineDetail, baseDevice)).toBe(offlineDetail)
  })
})
