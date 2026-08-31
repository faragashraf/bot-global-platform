import { DevelopmentTokenError, MonitoringApi, requestLocalOperatorSession } from './api'

describe('development token API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('maps 404 to not_available error on development endpoint', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(null, { status: 404 }),
    )

    await expect(requestLocalOperatorSession()).rejects.toMatchObject({
      code: 'not_available',
    } satisfies Pick<DevelopmentTokenError, 'code'>)
    await expect(requestLocalOperatorSession()).rejects.toBeInstanceOf(DevelopmentTokenError)
  })

  it('returns operator token payload from the development endpoint', async () => {
    const responseBody = {
      accessToken: 'dev-token',
      expiresAtUtc: new Date().toISOString(),
      tokenType: 'Bearer',
      operatorDisplayName: 'Local Dev Operator',
    }

    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify(responseBody), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    const response = await requestLocalOperatorSession()
    expect(response.accessToken).toBe('dev-token')
    expect(response.tokenType).toBe('Bearer')
  })
})

describe('recording API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('queries recordings newest-first through the protected API without exposing the token', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{"items":[],"page":1,"pageSize":24,"totalCount":0,"totalPages":0,"hasPreviousPage":false,"hasNextPage":false}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
    const api = new MonitoringApi(() => 'operator-secret')

    await api.listRecordings({ deviceIds: ['device 1', 'device 2'], search: 'front door', source: 'Motion', sort: 'Longest', page: 2 })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/recordings?deviceIds=device+1&deviceIds=device+2&search=front+door&source=Motion&sort=Longest&page=2',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer operator-secret' }),
      }),
    )
    expect(document.body).not.toHaveTextContent('operator-secret')
  })

  it('deletes recordings through the protected API', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }))
    const api = new MonitoringApi(() => 'operator-token')

    await api.deleteRecording('recording/one')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/recordings/recording%2Fone',
      expect.objectContaining({ method: 'DELETE' }),
    )
  })
})

describe('camera control API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('uses the shared operator-authorized endpoint and typed unified command body', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      commandId: 'one', state: 'Queued',
    }), { status: 202, headers: { 'Content-Type': 'application/json' } }))
    const api = new MonitoringApi(() => 'operator-secret')
    const request = { control: 'zoom', value: { boolean: null, number: 2, text: null }, correlationId: 'request-one' }

    await api.sendCameraControl('camera/one', request)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/camera-control/devices/camera%2Fone/commands',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(request),
        headers: expect.objectContaining({ Authorization: 'Bearer operator-secret', 'Content-Type': 'application/json' }),
      }),
    )
    expect(document.body).not.toHaveTextContent('operator-secret')
  })

  it('submits auditable per-device group commands through the authorized group boundary', async () => {
    const response = { correlationId: 'wall-record', items: [], succeeded: 0, failed: 0 }
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(response), {
      status: 202,
      headers: { 'Content-Type': 'application/json' },
    }))
    const api = new MonitoringApi(() => 'operator-secret')
    const request = {
      deviceIds: ['device-1', 'device-2'],
      control: 'recording',
      value: { boolean: null, number: null, text: 'start' },
      correlationId: 'wall-record',
    }

    await api.sendCameraControlGroup(request)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/camera-control/group-actions',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(request),
        headers: expect.objectContaining({ Authorization: 'Bearer operator-secret' }),
      }),
    )
  })
})
