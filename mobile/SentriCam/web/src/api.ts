import type { CameraControlCenterView, CameraControlCommand, CameraControlCommandRequest, CameraControlGroupCommandRequest, CameraControlGroupCommandResult, CommandAction, DeviceLiveState, DeviceMonitoringDetails, DeviceRemovalImpact, DeviceRemovalResult, HubConfigurationResult, HubConnectionTestResult, HubDatabaseSettings, HubSetupDraft, HubSetupState, HubStatus, PagedRecordingResult, PairingSession, RecordingQuery, RecordingTimeNode, RecordingTimeQuery, RemoteCommandView } from './models'

const API_ROOT = '/api/v1/monitoring/devices'
const DEVELOPMENT_TOKEN_ENDPOINT = '/api/v1/development/operator-token'
const RECORDINGS_ROOT = '/api/v1/recordings'
const HUB_ROOT = '/api/v1/hub'
const CAMERA_CONTROL_ROOT = '/api/v1/camera-control/devices'
const CAMERA_CONTROL_GROUP_ROOT = '/api/v1/camera-control/group-actions'

export type OperatorTokenResponse = {
  accessToken: string
  expiresAtUtc: string
  tokenType: string
  operatorDisplayName: string
}

export class DevelopmentTokenError extends Error {
  constructor(message: string, readonly code: 'not_available' | 'request_failed') {
    super(message)
  }
}

export class MonitoringApi {
  constructor(private readonly accessToken: () => string) {}

  listDevices(signal?: AbortSignal): Promise<DeviceLiveState[]> {
    return this.request<DeviceLiveState[]>(API_ROOT, { signal })
  }

  getDevice(deviceId: string, signal?: AbortSignal): Promise<DeviceMonitoringDetails> {
    return this.request<DeviceMonitoringDetails>(`${API_ROOT}/${encodeURIComponent(deviceId)}`, { signal })
  }

  sendCommand(deviceId: string, action: CommandAction): Promise<RemoteCommandView> {
    return this.request<RemoteCommandView>(
      `${API_ROOT}/${encodeURIComponent(deviceId)}/commands/${action}`,
      { method: 'POST' },
    )
  }

  getDeviceRemovalImpact(deviceId: string): Promise<DeviceRemovalImpact> {
    return this.request<DeviceRemovalImpact>(
      `${API_ROOT}/${encodeURIComponent(deviceId)}/removal`,
      {},
    )
  }

  removeDevice(deviceId: string, deviceName: string): Promise<DeviceRemovalResult> {
    return this.request<DeviceRemovalResult>(
      `${API_ROOT}/${encodeURIComponent(deviceId)}`,
      jsonRequest('DELETE', { confirmRemoval: true, expectedDeviceName: deviceName }),
    )
  }

  getCameraControl(deviceId: string, signal?: AbortSignal): Promise<CameraControlCenterView> {
    return this.request<CameraControlCenterView>(`${CAMERA_CONTROL_ROOT}/${encodeURIComponent(deviceId)}`, { signal })
  }

  sendCameraControl(deviceId: string, request: CameraControlCommandRequest): Promise<CameraControlCommand> {
    return this.request<CameraControlCommand>(
      `${CAMERA_CONTROL_ROOT}/${encodeURIComponent(deviceId)}/commands`,
      jsonRequest('POST', request),
    )
  }

  sendCameraControlGroup(request: CameraControlGroupCommandRequest): Promise<CameraControlGroupCommandResult> {
    return this.request<CameraControlGroupCommandResult>(
      CAMERA_CONTROL_GROUP_ROOT,
      jsonRequest('POST', request),
    )
  }

  cancelCameraControl(deviceId: string, commandId: string): Promise<CameraControlCommand> {
    return this.request<CameraControlCommand>(
      `${CAMERA_CONTROL_ROOT}/${encodeURIComponent(deviceId)}/commands/${encodeURIComponent(commandId)}`,
      { method: 'DELETE' },
    )
  }

  listRecordings(
    query: RecordingQuery,
    signal?: AbortSignal,
  ): Promise<PagedRecordingResult> {
    const parameters = recordingParameters(query)
    const suffix = parameters.size ? `?${parameters.toString()}` : ''
    return this.request<PagedRecordingResult>(`${RECORDINGS_ROOT}${suffix}`, { signal })
  }

  listRecordingTime(query: RecordingTimeQuery, signal?: AbortSignal): Promise<RecordingTimeNode[]> {
    const parameters = recordingParameters(query)
    parameters.set('level', query.level)
    if (query.parentStartUtc) parameters.set('parentStartUtc', query.parentStartUtc)
    if (query.parentEndUtc) parameters.set('parentEndUtc', query.parentEndUtc)
    return this.request<RecordingTimeNode[]>(`${RECORDINGS_ROOT}/time?${parameters.toString()}`, { signal })
  }

  deleteRecording(recordingId: string): Promise<void> {
    return this.request<void>(`${RECORDINGS_ROOT}/${encodeURIComponent(recordingId)}`, { method: 'DELETE' })
  }

  regenerateRecordingThumbnail(recordingId: string): Promise<void> {
    return this.request<void>(
      `${RECORDINGS_ROOT}/${encodeURIComponent(recordingId)}/thumbnail/regenerate`,
      { method: 'POST' },
    )
  }

  async fetchRecordingMedia(url: string, signal?: AbortSignal): Promise<Blob> {
    const response = await this.authorizedFetch(url, { signal })
    return response.blob()
  }

  private async request<T>(url: string, init: RequestInit): Promise<T> {
    const response = await this.authorizedFetch(url, init)
    if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T
    return response.json() as Promise<T>
  }

  private async authorizedFetch(url: string, init: RequestInit): Promise<Response> {
    const response = await fetch(url, {
      cache: 'no-store',
      ...init,
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${this.accessToken()}`,
        ...init.headers,
      },
    })
    if (response.status === 401 || response.status === 403) {
      throw new MonitoringApiError('Your operator session is not authorized.', 'unauthorized')
    }
    if (!response.ok) {
      let detail = 'The Local Hub could not complete that request.'
      try {
        const problem = await response.json() as { detail?: string; errors?: Record<string, string[]> }
        detail = Object.values(problem.errors ?? {}).flat()[0] ?? problem.detail ?? detail
      } catch {
        // Keep the product-safe fallback.
      }
      throw new MonitoringApiError(detail, 'request_failed')
    }
    return response
  }
}

export class HubApi {
  constructor(private readonly accessToken: () => string = () => '') {}

  getSetup(signal?: AbortSignal): Promise<HubSetupState> {
    return hubRequest<HubSetupState>(HUB_ROOT + '/setup', { signal })
  }

  saveDraft(draft: HubSetupDraft): Promise<HubSetupState> {
    return hubRequest<HubSetupState>(HUB_ROOT + '/setup/draft', jsonRequest('PUT', draft))
  }

  testDatabase(database: HubDatabaseSettings): Promise<HubConnectionTestResult> {
    return hubRequest<HubConnectionTestResult>(
      HUB_ROOT + '/setup/database/test',
      jsonRequest('POST', { database }),
    )
  }

  configure(draft: HubSetupDraft): Promise<HubConfigurationResult> {
    return hubRequest<HubConfigurationResult>(HUB_ROOT + '/setup/configure', jsonRequest('POST', draft))
  }

  getStatus(signal?: AbortSignal): Promise<HubStatus> {
    return this.authorized<HubStatus>(HUB_ROOT + '/status', { signal })
  }

  createPairingSession(): Promise<PairingSession> {
    return this.authorized<PairingSession>(HUB_ROOT + '/pairing-sessions', { method: 'POST' })
  }

  private authorized<T>(url: string, init: RequestInit): Promise<T> {
    return hubRequest<T>(url, {
      ...init,
      headers: {
        ...init.headers,
        Authorization: 'Bearer ' + this.accessToken(),
      },
    })
  }
}

function recordingParameters(query: RecordingQuery | RecordingTimeQuery) {
  const parameters = new URLSearchParams()
  query.deviceIds?.forEach((deviceId) => parameters.append('deviceIds', deviceId))
  if (query.search?.trim()) parameters.set('search', query.search.trim())
  if (query.source && query.source !== 'All') parameters.set('source', query.source)
  if (query.dateFromUtc) parameters.set('dateFromUtc', query.dateFromUtc)
  if (query.dateToUtc) parameters.set('dateToUtc', query.dateToUtc)
  if ('exactDayUtc' in query && query.exactDayUtc) parameters.set('exactDayUtc', query.exactDayUtc)
  if (query.hourFrom !== undefined) parameters.set('hourFrom', String(query.hourFrom))
  if (query.hourTo !== undefined) parameters.set('hourTo', String(query.hourTo))
  if (query.minimumDurationMilliseconds !== undefined) parameters.set('minimumDurationMilliseconds', String(query.minimumDurationMilliseconds))
  if (query.maximumDurationMilliseconds !== undefined) parameters.set('maximumDurationMilliseconds', String(query.maximumDurationMilliseconds))
  if (query.minimumSizeBytes !== undefined) parameters.set('minimumSizeBytes', String(query.minimumSizeBytes))
  if (query.maximumSizeBytes !== undefined) parameters.set('maximumSizeBytes', String(query.maximumSizeBytes))
  if ('uploadState' in query && query.uploadState) parameters.set('uploadState', query.uploadState)
  if (query.thumbnailState) parameters.set('thumbnailState', query.thumbnailState)
  if ('sort' in query && query.sort) parameters.set('sort', query.sort)
  if ('page' in query && query.page !== undefined) parameters.set('page', String(query.page))
  if ('pageSize' in query && query.pageSize !== undefined) parameters.set('pageSize', String(query.pageSize))
  return parameters
}

export async function requestLocalOperatorSession(): Promise<OperatorTokenResponse> {
  const response = await fetch(DEVELOPMENT_TOKEN_ENDPOINT, {
    method: 'POST',
  })
  if (response.status === 404) {
    throw new DevelopmentTokenError(
      'Local operator session endpoint is unavailable. Use a development API server.',
      'not_available',
    )
  }
  if (!response.ok) {
    throw new DevelopmentTokenError(
      'The local operator token endpoint is unavailable.',
      'request_failed',
    )
  }
  return response.json() as Promise<OperatorTokenResponse>
}

export async function requestHubOperatorSession(): Promise<OperatorTokenResponse> {
  return hubRequest<OperatorTokenResponse>(HUB_ROOT + '/session', { method: 'POST' })
}

function jsonRequest(method: string, value: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  }
}

async function hubRequest<T>(url: string, init: RequestInit): Promise<T> {
  const response = await fetch(url, {
    cache: 'no-store',
    ...init,
    headers: {
      Accept: 'application/json',
      ...init.headers,
    },
  })
  if (!response.ok) {
    let detail = 'The Local Hub could not complete that request.'
    try {
      const problem = await response.json() as { detail?: string; errors?: Record<string, string[]> }
      detail = Object.values(problem.errors ?? {}).flat()[0] ?? problem.detail ?? detail
    } catch {
      // Keep the product-safe fallback.
    }
    throw new HubApiError(detail, response.status)
  }
  if (response.status === 204 || response.headers.get('content-length') === '0') return undefined as T
  return response.json() as Promise<T>
}

export class MonitoringApiError extends Error {
  constructor(message: string, readonly code: 'unauthorized' | 'request_failed') {
    super(message)
  }
}

export class HubApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
  }
}
