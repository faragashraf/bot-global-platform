import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { MonitoringApi } from '../api'
import { formatBytes } from '../format'
import type { RecordingQuery, RecordingTimeLevel, RecordingTimeNode } from '../models'
import { Icon } from './Icon'

const EXPANSION_KEY = 'sentricam-recording-time-expansion-v1'

export function TimeNavigationTree({ api, filters, selectedRange, mobileOpen, onClose, onSelect, onFailure }: {
  api: MonitoringApi
  filters: RecordingQuery
  selectedRange: { startUtc?: string; endUtc?: string }
  mobileOpen: boolean
  onClose: () => void
  onSelect: (node: RecordingTimeNode) => void
  onFailure: (failure: unknown) => void
}) {
  const [roots, setRoots] = useState<RecordingTimeNode[]>([])
  const [children, setChildren] = useState<Record<string, RecordingTimeNode[]>>({})
  const [loadingKeys, setLoadingKeys] = useState<Set<string>>(new Set(['root']))
  const [expanded, setExpanded] = useState<Set<string>>(() => readExpansion())
  const requestSequence = useRef(0)
  const aggregationFilters = useMemo(() => omitDisplayState(filters), [filters])
  const filterKey = useMemo(() => JSON.stringify(aggregationFilters), [aggregationFilters])

  useEffect(() => {
    const abort = new AbortController()
    const sequence = ++requestSequence.current
    setLoadingKeys(new Set(['root']))
    setChildren({})
    api.listRecordingTime({ ...aggregationFilters, level: 'Year' }, abort.signal)
      .then((nodes) => { if (sequence === requestSequence.current) setRoots(nodes) })
      .catch((failure) => { if (!(failure instanceof DOMException && failure.name === 'AbortError')) onFailure(failure) })
      .finally(() => { if (sequence === requestSequence.current) setLoadingKeys(new Set()) })
    return () => abort.abort()
  }, [aggregationFilters, api, filterKey, onFailure])

  const loadChildren = useCallback(async (node: RecordingTimeNode) => {
    if (!node.childrenLevel || children[node.key] || loadingKeys.has(node.key)) return
    setLoadingKeys((current) => new Set(current).add(node.key))
    try {
      const nodes = await api.listRecordingTime({
        ...aggregationFilters,
        level: node.childrenLevel,
        parentStartUtc: node.startUtc,
        parentEndUtc: node.endUtc,
      })
      setChildren((current) => ({ ...current, [node.key]: nodes }))
    } catch (failure) {
      onFailure(failure)
    } finally {
      setLoadingKeys((current) => { const next = new Set(current); next.delete(node.key); return next })
    }
  }, [aggregationFilters, api, children, loadingKeys, onFailure])

  const toggle = useCallback((node: RecordingTimeNode) => {
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(node.key)) next.delete(node.key)
      else { next.add(node.key); void loadChildren(node) }
      writeExpansion(next)
      return next
    })
  }, [loadChildren])

  useEffect(() => {
    const knownNodes = [...roots, ...Object.values(children).flat()]
    knownNodes
      .filter((node) => expanded.has(node.key) && node.hasChildren && !children[node.key])
      .forEach((node) => { void loadChildren(node) })
  }, [children, expanded, loadChildren, roots])

  return (
    <>
      {mobileOpen && <button className="time-tree-backdrop" aria-label="Close time navigation" onClick={onClose}/>}
      <aside className={`time-tree${mobileOpen ? ' time-tree--open' : ''}`} aria-label="Browse recordings by time">
        <div className="time-tree__heading"><div><span className="eyebrow">Navigate</span><h2>Recorded time</h2></div><button className="time-tree__close" aria-label="Close time navigation" onClick={onClose}><Icon name="close"/></button></div>
        <p>Weeks start Monday and are clipped to their parent month. Times are shown locally.</p>
        {loadingKeys.has('root') ? <TimeTreeSkeleton/> : roots.length === 0 ? <div className="time-tree__empty">No dates match these filters.</div> : <div role="tree" aria-label="Recording dates">{roots.map((node) => <TreeNode key={node.key} node={node} depth={1} childrenByKey={children} expanded={expanded} loadingKeys={loadingKeys} selectedRange={selectedRange} onToggle={toggle} onSelect={(value) => { onSelect(value); onClose() }}/>)}</div>}
      </aside>
    </>
  )
}

function TreeNode({ node, depth, childrenByKey, expanded, loadingKeys, selectedRange, onToggle, onSelect }: {
  node: RecordingTimeNode
  depth: number
  childrenByKey: Record<string, RecordingTimeNode[]>
  expanded: Set<string>
  loadingKeys: Set<string>
  selectedRange: { startUtc?: string; endUtc?: string }
  onToggle: (node: RecordingTimeNode) => void
  onSelect: (node: RecordingTimeNode) => void
}) {
  const isExpanded = expanded.has(node.key)
  const selected = selectedRange.startUtc === node.startUtc && selectedRange.endUtc === node.endUtc
  const childNodes = (childrenByKey[node.key] ?? []).filter((child) => child.key !== node.key)
  const localLabel = localizeNodeLabel(node)
  const keyDown = (event: React.KeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowRight' && node.hasChildren && !isExpanded) { event.preventDefault(); onToggle(node) }
    if (event.key === 'ArrowLeft' && isExpanded) { event.preventDefault(); onToggle(node) }
  }
  return (
    <div role="treeitem" aria-level={depth} aria-expanded={node.hasChildren ? isExpanded : undefined} aria-selected={selected}>
      <div className={`time-tree__node${selected ? ' time-tree__node--selected' : ''}`} style={{ '--tree-depth': depth } as React.CSSProperties}>
        {node.hasChildren ? <button className={`time-tree__toggle${isExpanded ? ' time-tree__toggle--open' : ''}`} aria-label={`${isExpanded ? 'Collapse' : 'Expand'} ${localLabel}`} onClick={() => onToggle(node)}><Icon name="chevron" size={15}/></button> : <span className="time-tree__spacer"/>}
        <button className="time-tree__select" onKeyDown={keyDown} onClick={() => onSelect(node)}><span>{localLabel}</span><small>{node.recordingCount.toLocaleString()} · {formatBytes(node.totalSizeBytes)}</small></button>
      </div>
      {isExpanded && <div role="group">{loadingKeys.has(node.key) ? <div className="time-tree__loading">Loading…</div> : childNodes.map((child) => <TreeNode key={child.key} node={child} depth={depth + 1} childrenByKey={childrenByKey} expanded={expanded} loadingKeys={loadingKeys} selectedRange={selectedRange} onToggle={onToggle} onSelect={onSelect}/>)}</div>}
    </div>
  )
}

function omitDisplayState(query: RecordingQuery) {
  const { sort: _sort, page: _page, pageSize: _pageSize, exactDayUtc: _exactDay, uploadState: _upload, ...filters } = query
  return filters
}

function localizeNodeLabel(node: RecordingTimeNode) {
  const start = new Date(node.startUtc)
  if (node.key.startsWith('year:')) return node.label
  if (node.key.startsWith('month:')) return new Intl.DateTimeFormat(undefined, { month: 'long' }).format(start)
  if (node.key.startsWith('day:')) return new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short' }).format(start)
  if (node.key.startsWith('hour:')) return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(start)
  return node.label
}

function readExpansion() {
  try { return new Set<string>(JSON.parse(sessionStorage.getItem(EXPANSION_KEY) ?? '[]') as string[]) } catch { return new Set<string>() }
}

function writeExpansion(expanded: Set<string>) {
  try { sessionStorage.setItem(EXPANSION_KEY, JSON.stringify([...expanded])) } catch { /* session storage may be unavailable */ }
}

function TimeTreeSkeleton() {
  return <div className="time-tree__skeleton" aria-label="Loading recording dates">{Array.from({ length: 6 }, (_, index) => <span className="skeleton" key={index}/>)}</div>
}
