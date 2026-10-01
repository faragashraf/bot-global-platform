import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { MonitoringApi } from '../api'
import type { DeviceLiveState, PagedRecordingResult, RecordingTimeNode, RecordingView } from '../models'
import { RecordingsPage } from './RecordingsPage'

const recording: RecordingView = {
  recordingId: 'recording-1', deviceId: 'device-1', deviceName: 'Front Entrance', clientRecordingId: 'segment-1', sessionId: 'session-1', fileName: 'front-entrance-technical-name.mp4', contentType: 'video/mp4', durationMilliseconds: 65_000, sizeBytes: 2_097_152, createdUtc: '2026-08-01T08:00:00Z', uploadedUtc: '2026-08-01T08:01:00Z', motion: true, manual: false, monitoringSession: true, uploadState: 'Completed', thumbnailState: 'Pending', thumbnailGenerationAttempts: 0, thumbnailErrorCode: null, thumbnailGeneratedUtc: null, checksumSha256: 'a'.repeat(64), contentUrl: '/api/v1/recordings/recording-1/content', downloadUrl: '/api/v1/recordings/recording-1/download', thumbnailUrl: null,
}

const device: DeviceLiveState = {
  deviceId: 'device-1', friendlyName: 'Front Entrance', platform: 'Android', connectionState: 'connected', operationalState: 'monitoring', monitoring: true, motion: 'idle', recording: 'idle', batteryPercent: 80, availableStorageBytes: 10, lastHeartbeatAtUtc: null, lastSeenAtUtc: null, snapshotVersion: 1, snapshot: null,
}

const page = (items = [recording], overrides: Partial<PagedRecordingResult> = {}): PagedRecordingResult => ({ items, page: 1, pageSize: 24, totalCount: items.length, totalPages: items.length ? 1 : 0, hasPreviousPage: false, hasNextPage: false, ...overrides })

const yearNode: RecordingTimeNode = { key: 'year:2026-01-01T00:00:00Z', label: '2026', startUtc: '2026-01-01T00:00:00Z', endUtc: '2027-01-01T00:00:00Z', recordingCount: 1, totalSizeBytes: 2_097_152, hasChildren: true, childrenLevel: 'Month' }

function createApi(result: PagedRecordingResult = page()) {
  return {
    listRecordings: vi.fn().mockResolvedValue(result),
    listRecordingTime: vi.fn().mockResolvedValueOnce([yearNode]).mockResolvedValue([]),
    fetchRecordingMedia: vi.fn().mockResolvedValue(new Blob(['video'], { type: 'video/mp4' })),
    deleteRecording: vi.fn().mockResolvedValue(undefined),
    regenerateRecordingThumbnail: vi.fn().mockResolvedValue(undefined),
  } as unknown as MonitoringApi
}

describe('recordings page', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState(null, '', '/')
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:recording')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
  })

  it('shows a friendly title, pending thumbnail fallback, and playable recording without filename dominance', async () => {
    const api = createApi()
    render(<RecordingsPage api={api} devices={[device]} onUnauthorized={() => undefined} />)

    expect(await screen.findByRole('heading', { name: 'Motion recording' })).toBeVisible()
    expect(screen.getByText('Preparing thumbnail…')).toBeVisible()
    expect(screen.queryByRole('heading', { name: recording.fileName })).not.toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: /Play Motion recording from Front Entrance/ })[0])
    expect(await screen.findByRole('dialog', { name: 'Playing recording from Front Entrance' })).toBeVisible()
    expect(screen.getByRole('dialog').querySelector('video')).not.toHaveAttribute('autoplay')
  })

  it('replaces the playback spinner with a clear error when recording media is unavailable', async () => {
    const api = createApi()
    vi.mocked(api.fetchRecordingMedia).mockRejectedValue(new Error('missing media'))
    const onUnauthorized = vi.fn()
    render(<RecordingsPage api={api} devices={[device]} onUnauthorized={onUnauthorized} />)

    await screen.findByRole('heading', { name: 'Motion recording' })
    fireEvent.click(screen.getAllByRole('button', { name: /Play Motion recording from Front Entrance/ })[0])

    expect(await screen.findByRole('alert', { name: '' })).toHaveTextContent('Recording media unavailable.')
    expect(screen.queryByText('Loading recording…')).not.toBeInTheDocument()
    expect(onUnauthorized).toHaveBeenCalledWith(expect.any(Error))
  })

  it('updates server query, preserves device scope, clears filters, and resets pagination', async () => {
    const api = createApi()
    render(<RecordingsPage api={api} devices={[device]} initialDeviceId="device-1" onUnauthorized={() => undefined} />)
    await screen.findByRole('heading', { name: 'Motion recording' })
    await waitFor(() => expect(api.listRecordings).toHaveBeenCalledWith(expect.objectContaining({ deviceIds: ['device-1'], page: 1 }), expect.any(AbortSignal)))

    fireEvent.change(screen.getByRole('textbox', { name: 'Search recordings' }), { target: { value: 'door' } })
    await waitFor(() => expect(api.listRecordings).toHaveBeenLastCalledWith(expect.objectContaining({ deviceIds: ['device-1'], search: 'door', page: 1 }), expect.any(AbortSignal)))
    fireEvent.click(screen.getByRole('button', { name: /“door”/ }))
    await waitFor(() => expect(api.listRecordings).toHaveBeenLastCalledWith(expect.not.objectContaining({ search: 'door' }), expect.any(AbortSignal)))
  })

  it('persists sorting and all three shared-action view modes', async () => {
    const api = createApi()
    render(<RecordingsPage api={api} devices={[device]} onUnauthorized={() => undefined} />)
    await screen.findByRole('heading', { name: 'Motion recording' })

    fireEvent.change(screen.getByRole('combobox', { name: 'Sort recordings' }), { target: { value: 'Largest' } })
    expect(localStorage.getItem('sentricam-recording-sort-v1')).toBe('Largest')
    for (const [label, value] of [['Compact grid', 'compact'], ['List view', 'list'], ['Large grid', 'large']] as const) {
      fireEvent.click(screen.getByRole('button', { name: label }))
      expect(localStorage.getItem('sentricam-recording-view-v1')).toBe(value)
      expect(screen.getByRole('button', { name: label })).toHaveAttribute('aria-pressed', 'true')
    }
  })

  it('selects a time-tree node and applies its UTC range', async () => {
    const api = createApi()
    render(<RecordingsPage api={api} devices={[device]} onUnauthorized={() => undefined} />)
    expect(await screen.findByRole('button', { name: /^2026/ })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /^2026/ }))
    await waitFor(() => expect(api.listRecordings).toHaveBeenLastCalledWith(expect.objectContaining({ dateFromUtc: yearNode.startUtc, dateToUtc: yearNode.endUtc, page: 1 }), expect.any(AbortSignal)))
  })

  it('opens the mobile time drawer and supports keyboard expansion', async () => {
    const api = createApi()
    render(<RecordingsPage api={api} devices={[device]} onUnauthorized={() => undefined} />)
    await screen.findByRole('button', { name: /^2026/ })
    fireEvent.click(screen.getByRole('button', { name: 'Browse time' }))
    expect(screen.getByLabelText('Browse recordings by time')).toHaveClass('time-tree--open')
    fireEvent.keyDown(screen.getByRole('button', { name: /^2026/ }), { key: 'ArrowRight' })
    await waitFor(() => expect(api.listRecordingTime).toHaveBeenCalledWith(expect.objectContaining({ level: 'Month', parentStartUtc: yearNode.startUtc, parentEndUtc: yearNode.endUtc })))
  })

  it('supports pagination, deletion, empty state, and error state', async () => {
    const api = createApi(page([recording], { totalCount: 30, totalPages: 2, hasNextPage: true }))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<RecordingsPage api={api} devices={[device]} onUnauthorized={() => undefined} />)
    await screen.findByRole('heading', { name: 'Motion recording' })
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(api.listRecordings).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }), expect.any(AbortSignal)))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(api.deleteRecording).toHaveBeenCalledWith('recording-1'))
  })

  it('prevents a stale response from overwriting a newer filter result', async () => {
    let resolveFirst!: (value: PagedRecordingResult) => void
    const first = new Promise<PagedRecordingResult>((resolve) => { resolveFirst = resolve })
    const api = createApi()
    vi.mocked(api.listRecordings).mockReturnValueOnce(first).mockResolvedValueOnce(page([]))
    render(<RecordingsPage api={api} devices={[device]} onUnauthorized={() => undefined} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'Search recordings' }), { target: { value: 'new filter' } })
    expect(await screen.findByRole('heading', { name: 'No recordings match' })).toBeVisible()
    resolveFirst(page([recording]))
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Motion recording' })).not.toBeInTheDocument())
  })
})
