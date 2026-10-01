import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { MonitoringApi } from '../api'
import type { DeviceLiveState, PagedRecordingResult, RecordingQuery, RecordingSort, RecordingView, RecordingViewMode } from '../models'
import { Icon } from './Icon'
import { RecordingItem, type RecordingActions } from './RecordingItem'
import { RecordingLibraryToolbar } from './RecordingLibraryToolbar'
import { TimeNavigationTree } from './TimeNavigationTree'
import { OPERATIONAL_FALLBACK_POLL_MILLIS } from '../realtime'

const VIEW_STORAGE_KEY = 'sentricam-recording-view-v1'
const SORT_STORAGE_KEY = 'sentricam-recording-sort-v1'
const EMPTY_RESULT: PagedRecordingResult = { items: [], page: 1, pageSize: 24, totalCount: 0, totalPages: 0, hasPreviousPage: false, hasNextPage: false }

export function RecordingsPage({
  api,
  devices,
  initialDeviceId,
  onUnauthorized,
}: {
  api: MonitoringApi
  devices: DeviceLiveState[]
  initialDeviceId?: string | null
  onUnauthorized: (failure: unknown) => void
}) {
  const [query, setQuery] = useState<RecordingQuery>(() => readQuery(initialDeviceId))
  const [result, setResult] = useState<PagedRecordingResult>(EMPTY_RESULT)
  const [viewMode, setViewMode] = useState<RecordingViewMode>(() => readViewMode())
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)
  const [playing, setPlaying] = useState<RecordingView | null>(null)
  const [mediaUrl, setMediaUrl] = useState<string | null>(null)
  const [mediaError, setMediaError] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [timeDrawerOpen, setTimeDrawerOpen] = useState(false)
  const requestSequence = useRef(0)
  const didWriteUrl = useRef(false)

  useEffect(() => {
    if (!initialDeviceId || query.deviceIds?.length) return
    setQuery((current) => ({ ...current, deviceIds: [initialDeviceId], page: 1 }))
  }, [initialDeviceId, query.deviceIds?.length])

  useEffect(() => {
    const pop = () => setQuery(readQuery(initialDeviceId))
    window.addEventListener('popstate', pop)
    return () => window.removeEventListener('popstate', pop)
  }, [initialDeviceId])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      const next = writeQuery(query)
      if (next === window.location.href) return
      if (didWriteUrl.current) window.history.pushState(null, '', next)
      else { window.history.replaceState(null, '', next); didWriteUrl.current = true }
    }, 220)
    return () => window.clearTimeout(timer)
  }, [query])

  useEffect(() => {
    const abort = new AbortController()
    const sequence = ++requestSequence.current
    setLoading(true)
    setError(null)
    api.listRecordings(query, abort.signal)
      .then((next) => {
        if (sequence !== requestSequence.current) return
        setResult(next)
        setSelected((current) => new Set([...current].filter((id) => next.items.some((item) => item.recordingId === id))))
      })
      .catch((failure) => {
        if (failure instanceof DOMException && failure.name === 'AbortError') return
        if (sequence === requestSequence.current) setError('The recordings library could not be loaded.')
        onUnauthorized(failure)
      })
      .finally(() => { if (sequence === requestSequence.current) setLoading(false) })
    return () => abort.abort()
  }, [api, onUnauthorized, query, refreshKey])

  useEffect(() => {
    const timer = window.setInterval(
      () => setRefreshKey((value) => value + 1),
      OPERATIONAL_FALLBACK_POLL_MILLIS,
    )
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    if (!playing) return
    const abort = new AbortController()
    let localUrl: string | null = null
    setMediaError(false)
    api.fetchRecordingMedia(playing.contentUrl, abort.signal)
      .then((blob) => { localUrl = URL.createObjectURL(blob); setMediaUrl(localUrl) })
      .catch((failure) => {
        if (failure instanceof DOMException && failure.name === 'AbortError') return
        setMediaError(true)
        onUnauthorized(failure)
      })
    return () => { abort.abort(); setMediaUrl(null); setMediaError(false); if (localUrl) URL.revokeObjectURL(localUrl) }
  }, [api, onUnauthorized, playing])

  const download = useCallback(async (recording: RecordingView) => {
    setBusyId(recording.recordingId)
    try {
      const blob = await api.fetchRecordingMedia(recording.downloadUrl)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = recording.fileName
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (failure) { onUnauthorized(failure) } finally { setBusyId(null) }
  }, [api, onUnauthorized])

  const remove = useCallback(async (recording: RecordingView) => {
    if (!window.confirm(`Delete this ${recording.motion ? 'motion' : 'manual'} recording from ${recording.deviceName}?`)) return
    setBusyId(recording.recordingId)
    try {
      await api.deleteRecording(recording.recordingId)
      setResult((current) => ({ ...current, items: current.items.filter((item) => item.recordingId !== recording.recordingId), totalCount: Math.max(0, current.totalCount - 1) }))
    } catch (failure) { onUnauthorized(failure) } finally { setBusyId(null) }
  }, [api, onUnauthorized])

  const regenerateThumbnail = useCallback(async (recording: RecordingView) => {
    setBusyId(recording.recordingId)
    try { await api.regenerateRecordingThumbnail(recording.recordingId); setRefreshKey((value) => value + 1) }
    catch (failure) { onUnauthorized(failure) }
    finally { setBusyId(null) }
  }, [api, onUnauthorized])

  const actions = useMemo<RecordingActions>(() => ({ play: setPlaying, download: (value) => void download(value), remove: (value) => void remove(value), regenerateThumbnail: (value) => void regenerateThumbnail(value) }), [download, regenerateThumbnail, remove])
  const changeQuery = useCallback((next: RecordingQuery) => {
    if (next.sort) { try { localStorage.setItem(SORT_STORAGE_KEY, next.sort) } catch { /* local storage may be unavailable */ } }
    setQuery(next)
  }, [])
  const changeViewMode = useCallback((next: RecordingViewMode) => {
    try { localStorage.setItem(VIEW_STORAGE_KEY, next) } catch { /* local storage may be unavailable */ }
    setViewMode(next)
  }, [])
  const selectNode = useCallback((node: { startUtc: string; endUtc: string }) => setQuery((current) => ({ ...current, dateFromUtc: node.startUtc, dateToUtc: node.endUtc, exactDayUtc: undefined, page: 1 })), [])
  const updateSelection = useCallback((id: string, checked: boolean) => setSelected((current) => { const next = new Set(current); if (checked) next.add(id); else next.delete(id); return next }), [])

  return (
    <main className="recordings-page">
      <section className="recordings-heading"><div><span className="eyebrow">Local archive</span><h1>Recordings Library</h1><p>Browse, filter, and review completed device recordings stored on this Hub.</p></div><div className="recordings-heading__actions"><button className="button time-tree-trigger" onClick={() => setTimeDrawerOpen(true)}><Icon name="calendar"/>Browse time</button><button className="button button--quiet" onClick={() => setRefreshKey((value) => value + 1)}><Icon name="refresh"/>Refresh</button></div></section>
      <RecordingLibraryToolbar query={query} devices={devices} resultCount={result.totalCount} viewMode={viewMode} scopedDeviceId={initialDeviceId} onQueryChange={changeQuery} onViewModeChange={changeViewMode}/>
      <div className="recording-library-layout">
        <TimeNavigationTree api={api} filters={query} selectedRange={{ startUtc: query.dateFromUtc, endUtc: query.dateToUtc }} mobileOpen={timeDrawerOpen} onClose={() => setTimeDrawerOpen(false)} onSelect={selectNode} onFailure={onUnauthorized}/>
        <section className="recording-results" aria-label="Recording results">
          {selected.size > 0 && <div className="selection-bar"><strong>{selected.size} selected</strong><button className="text-button" onClick={() => setSelected(new Set())}>Clear selection</button></div>}
          {loading && result.items.length === 0 ? <RecordingSkeleton mode={viewMode}/> : error ? <section className="recordings-empty recordings-error" role="alert"><Icon name="camera" size={38}/><h2>Couldn’t load recordings</h2><p>{error}</p><button className="button" onClick={() => setRefreshKey((value) => value + 1)}>Try again</button></section> : result.items.length === 0 ? <section className="recordings-empty"><Icon name="grid" size={38}/><h2>No recordings match</h2><p>Adjust or clear the active filters. New completed uploads appear here automatically.</p></section> : <div className={`recording-collection recording-collection--${viewMode}`} aria-busy={loading}>{result.items.map((recording) => <RecordingItem key={recording.recordingId} api={api} recording={recording} mode={viewMode} selected={selected.has(recording.recordingId)} busy={busyId === recording.recordingId} actions={actions} onSelect={updateSelection}/>)}</div>}
          {result.totalPages > 1 && <nav className="recording-pagination" aria-label="Recording pages"><button className="button button--quiet" disabled={!result.hasPreviousPage || loading} onClick={() => setQuery((current) => ({ ...current, page: Math.max(1, (current.page ?? 1) - 1) }))}>Previous</button><span>Page {result.page} of {result.totalPages}</span><button className="button button--quiet" disabled={!result.hasNextPage || loading} onClick={() => setQuery((current) => ({ ...current, page: (current.page ?? 1) + 1 }))}>Next</button></nav>}
        </section>
      </div>
      {playing && <div className="media-modal" role="dialog" aria-modal="true" aria-label={`Playing recording from ${playing.deviceName}`}><div className="media-modal__panel"><button className="media-modal__close" aria-label="Close player" onClick={() => setPlaying(null)}><Icon name="close"/></button>{mediaUrl ? <video controls preload="metadata" src={mediaUrl}/> : mediaError ? <div className="media-loading" role="alert">Recording media unavailable.</div> : <div className="media-loading"><div className="spinner"/>Loading recording…</div>}<div><h2>{playing.motion ? 'Motion recording' : 'Manual recording'}</h2><p>{playing.deviceName} · {new Date(playing.createdUtc).toLocaleString()}</p></div></div></div>}
    </main>
  )
}

function RecordingSkeleton({ mode }: { mode: RecordingViewMode }) {
  return <div className={`recording-collection recording-collection--${mode}`} aria-label="Loading recordings">{Array.from({ length: mode === 'list' ? 6 : 8 }, (_, index) => <div className="skeleton recording-card-skeleton" key={index}/>)}</div>
}

function readViewMode(): RecordingViewMode {
  try { const value = localStorage.getItem(VIEW_STORAGE_KEY); if (value === 'large' || value === 'compact' || value === 'list') return value } catch { /* local storage may be unavailable */ }
  return 'large'
}

function readSort(): RecordingSort {
  try { const value = localStorage.getItem(SORT_STORAGE_KEY) as RecordingSort | null; if (['Newest', 'Oldest', 'Longest', 'Shortest', 'Largest', 'Smallest', 'DeviceName', 'UploadTime'].includes(value ?? '')) return value! } catch { /* local storage may be unavailable */ }
  return 'Newest'
}

function readQuery(initialDeviceId?: string | null): RecordingQuery {
  const parameters = new URLSearchParams(window.location.search)
  const deviceIds = parameters.getAll('device')
  return cleanQuery({
    deviceIds: deviceIds.length ? deviceIds : initialDeviceId ? [initialDeviceId] : undefined,
    search: parameters.get('q') ?? undefined,
    source: (parameters.get('source') ?? undefined) as RecordingQuery['source'],
    dateFromUtc: parameters.get('from') ?? undefined,
    dateToUtc: parameters.get('to') ?? undefined,
    hourFrom: numberParameter(parameters, 'hourFrom'),
    hourTo: numberParameter(parameters, 'hourTo'),
    minimumDurationMilliseconds: scaledParameter(parameters, 'minDuration', 1_000),
    maximumDurationMilliseconds: scaledParameter(parameters, 'maxDuration', 1_000),
    minimumSizeBytes: scaledParameter(parameters, 'minSize', 1_048_576),
    maximumSizeBytes: scaledParameter(parameters, 'maxSize', 1_048_576),
    thumbnailState: (parameters.get('thumbnail') ?? undefined) as RecordingQuery['thumbnailState'],
    sort: (parameters.get('sort') as RecordingSort | null) ?? readSort(),
    page: numberParameter(parameters, 'page') ?? 1,
    pageSize: 24,
  })
}

function writeQuery(query: RecordingQuery) {
  const url = new URL(window.location.href)
  const parameters = url.searchParams
  ;['device', 'q', 'source', 'from', 'to', 'hourFrom', 'hourTo', 'minDuration', 'maxDuration', 'minSize', 'maxSize', 'thumbnail', 'sort', 'page'].forEach((key) => parameters.delete(key))
  parameters.set('view', 'recordings')
  query.deviceIds?.forEach((id) => parameters.append('device', id))
  setParameter(parameters, 'q', query.search)
  setParameter(parameters, 'source', query.source === 'All' ? undefined : query.source)
  setParameter(parameters, 'from', query.dateFromUtc)
  setParameter(parameters, 'to', query.dateToUtc)
  setParameter(parameters, 'hourFrom', query.hourFrom)
  setParameter(parameters, 'hourTo', query.hourTo)
  setParameter(parameters, 'minDuration', query.minimumDurationMilliseconds === undefined ? undefined : query.minimumDurationMilliseconds / 1_000)
  setParameter(parameters, 'maxDuration', query.maximumDurationMilliseconds === undefined ? undefined : query.maximumDurationMilliseconds / 1_000)
  setParameter(parameters, 'minSize', query.minimumSizeBytes === undefined ? undefined : query.minimumSizeBytes / 1_048_576)
  setParameter(parameters, 'maxSize', query.maximumSizeBytes === undefined ? undefined : query.maximumSizeBytes / 1_048_576)
  setParameter(parameters, 'thumbnail', query.thumbnailState)
  setParameter(parameters, 'sort', query.sort === 'Newest' ? undefined : query.sort)
  setParameter(parameters, 'page', (query.page ?? 1) > 1 ? query.page : undefined)
  return url.toString()
}

function cleanQuery(query: RecordingQuery): RecordingQuery {
  return { ...query, source: query.source ?? 'All', sort: query.sort ?? 'Newest', page: Math.max(1, query.page ?? 1), pageSize: 24 }
}

function setParameter(parameters: URLSearchParams, key: string, value: string | number | undefined) {
  if (value !== undefined && value !== '') parameters.set(key, String(value))
}

function numberParameter(parameters: URLSearchParams, key: string) {
  const value = parameters.get(key)
  return value === null || value === '' || Number.isNaN(Number(value)) ? undefined : Number(value)
}

function scaledParameter(parameters: URLSearchParams, key: string, multiplier: number) {
  const value = numberParameter(parameters, key)
  return value === undefined ? undefined : Math.round(value * multiplier)
}
