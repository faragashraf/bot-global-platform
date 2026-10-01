import type { DeviceLiveState, RecordingQuery, RecordingSort, RecordingThumbnailState, RecordingViewMode } from '../models'
import { Icon } from './Icon'

const sortOptions: Array<[RecordingSort, string]> = [
  ['Newest', 'Newest'],
  ['Oldest', 'Oldest'],
  ['Longest', 'Longest'],
  ['Shortest', 'Shortest'],
  ['Largest', 'Largest'],
  ['Smallest', 'Smallest'],
  ['DeviceName', 'Device name'],
  ['UploadTime', 'Upload time'],
]

export function RecordingLibraryToolbar({
  query,
  devices,
  resultCount,
  viewMode,
  scopedDeviceId,
  onQueryChange,
  onViewModeChange,
}: {
  query: RecordingQuery
  devices: DeviceLiveState[]
  resultCount: number
  viewMode: RecordingViewMode
  scopedDeviceId?: string | null
  onQueryChange: (query: RecordingQuery) => void
  onViewModeChange: (mode: RecordingViewMode) => void
}) {
  const update = (patch: Partial<RecordingQuery>) => onQueryChange({ ...query, ...patch, page: 1 })
  const selectedDevices = query.deviceIds ?? []
  const scopedDevice = devices.find((device) => device.deviceId === scopedDeviceId)
  const toggleDevice = (deviceId: string) => update({
    deviceIds: selectedDevices.includes(deviceId)
      ? selectedDevices.filter((id) => id !== deviceId)
      : [...selectedDevices, deviceId],
  })
  const clearFilters = () => onQueryChange({ sort: query.sort, page: 1, pageSize: query.pageSize })
  const chips = activeChips(query, devices)

  return (
    <section className="library-toolbar" aria-label="Recording filters and display options">
      {scopedDevice && <div className="recording-scope"><Icon name="devices" size={16}/><span>Opened from <strong>{scopedDevice.friendlyName}</strong>. You can refine or expand the device selection.</span></div>}
      <div className="library-toolbar__primary">
        <label className="search-field"><Icon name="search" size={17}/><input aria-label="Search recordings" value={query.search ?? ''} maxLength={100} onChange={(event) => update({ search: event.target.value || undefined })} placeholder="Search filename, device, or session" /></label>
        <details className="device-picker">
          <summary aria-label="Choose devices"><Icon name="devices" size={17}/>{selectedDevices.length ? `${selectedDevices.length} devices` : 'All devices'}</summary>
          <fieldset><legend>Devices</legend>{devices.map((device) => <label key={device.deviceId}><input type="checkbox" checked={selectedDevices.includes(device.deviceId)} onChange={() => toggleDevice(device.deviceId)} />{device.friendlyName}</label>)}</fieldset>
        </details>
        <select aria-label="Recording source" value={query.source ?? 'All'} onChange={(event) => update({ source: event.target.value as RecordingQuery['source'] })}>
          <option value="All">All sources</option><option value="Motion">Motion</option><option value="Manual">Manual</option><option value="MonitoringSession">Monitoring sessions</option>
        </select>
        <details className="advanced-filters">
          <summary><Icon name="filter" size={17}/>More filters</summary>
          <div className="advanced-filters__panel">
            <fieldset><legend>Date and hour</legend><label>From<input aria-label="Date from" type="datetime-local" value={toLocalInput(query.dateFromUtc)} onChange={(event) => update({ dateFromUtc: fromLocalInput(event.target.value) })}/></label><label>To<input aria-label="Date to" type="datetime-local" value={toLocalInput(query.dateToUtc)} onChange={(event) => update({ dateToUtc: fromLocalInput(event.target.value) })}/></label><div className="range-pair"><label>Hour from<input aria-label="Hour from" type="number" min="0" max="23" value={query.hourFrom ?? ''} onChange={(event) => update({ hourFrom: optionalNumber(event.target.value) })}/></label><label>Hour to<input aria-label="Hour to" type="number" min="1" max="24" value={query.hourTo ?? ''} onChange={(event) => update({ hourTo: optionalNumber(event.target.value) })}/></label></div></fieldset>
            <fieldset><legend>Duration (seconds)</legend><div className="range-pair"><label>Minimum<input aria-label="Minimum duration" type="number" min="0" value={millisecondsToUnit(query.minimumDurationMilliseconds, 1_000)} onChange={(event) => update({ minimumDurationMilliseconds: unitToNumber(event.target.value, 1_000) })}/></label><label>Maximum<input aria-label="Maximum duration" type="number" min="0" value={millisecondsToUnit(query.maximumDurationMilliseconds, 1_000)} onChange={(event) => update({ maximumDurationMilliseconds: unitToNumber(event.target.value, 1_000) })}/></label></div></fieldset>
            <fieldset><legend>Size (MB)</legend><div className="range-pair"><label>Minimum<input aria-label="Minimum size" type="number" min="0" step="0.1" value={millisecondsToUnit(query.minimumSizeBytes, 1_048_576)} onChange={(event) => update({ minimumSizeBytes: unitToNumber(event.target.value, 1_048_576) })}/></label><label>Maximum<input aria-label="Maximum size" type="number" min="0" step="0.1" value={millisecondsToUnit(query.maximumSizeBytes, 1_048_576)} onChange={(event) => update({ maximumSizeBytes: unitToNumber(event.target.value, 1_048_576) })}/></label></div></fieldset>
            <label>Thumbnail state<select aria-label="Thumbnail state" value={query.thumbnailState ?? ''} onChange={(event) => update({ thumbnailState: (event.target.value || undefined) as RecordingThumbnailState | undefined })}><option value="">Any state</option><option value="Ready">Ready</option><option value="Pending">Pending</option><option value="Processing">Processing</option><option value="Failed">Failed</option></select></label>
          </div>
        </details>
      </div>
      <div className="library-toolbar__secondary">
        <div className="filter-chips" aria-label="Active filters">{chips.map((chip) => <button key={chip.key} onClick={() => update(chip.clear)}>{chip.label}<Icon name="close" size={13}/></button>)}{chips.length > 0 && <button className="clear-filters" onClick={clearFilters}>Clear all filters</button>}</div>
        <div className="library-toolbar__display"><strong>{resultCount.toLocaleString()} recording{resultCount === 1 ? '' : 's'}</strong><label>Sort<select aria-label="Sort recordings" value={query.sort ?? 'Newest'} onChange={(event) => update({ sort: event.target.value as RecordingSort })}>{sortOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><div className="view-switcher" role="group" aria-label="Recording view"><ViewButton label="Large grid" icon="grid" active={viewMode === 'large'} onClick={() => onViewModeChange('large')}/><ViewButton label="Compact grid" icon="compact" active={viewMode === 'compact'} onClick={() => onViewModeChange('compact')}/><ViewButton label="List view" icon="list" active={viewMode === 'list'} onClick={() => onViewModeChange('list')}/></div></div>
      </div>
    </section>
  )
}

function ViewButton({ label, icon, active, onClick }: { label: string; icon: 'grid' | 'compact' | 'list'; active: boolean; onClick: () => void }) {
  return <button className={active ? 'view-switcher__button view-switcher__button--active' : 'view-switcher__button'} aria-label={label} aria-pressed={active} onClick={onClick}><Icon name={icon} size={17}/></button>
}

function activeChips(query: RecordingQuery, devices: DeviceLiveState[]) {
  const chips: Array<{ key: string; label: string; clear: Partial<RecordingQuery> }> = []
  query.deviceIds?.forEach((id) => chips.push({ key: `device-${id}`, label: devices.find((device) => device.deviceId === id)?.friendlyName ?? 'Device', clear: { deviceIds: query.deviceIds?.filter((value) => value !== id) } }))
  if (query.search) chips.push({ key: 'search', label: `“${query.search}”`, clear: { search: undefined } })
  if (query.source && query.source !== 'All') chips.push({ key: 'source', label: query.source === 'MonitoringSession' ? 'Monitoring session' : query.source, clear: { source: undefined } })
  if (query.dateFromUtc) chips.push({ key: 'from', label: `From ${new Date(query.dateFromUtc).toLocaleString()}`, clear: { dateFromUtc: undefined } })
  if (query.dateToUtc) chips.push({ key: 'to', label: `To ${new Date(query.dateToUtc).toLocaleString()}`, clear: { dateToUtc: undefined } })
  if (query.hourFrom !== undefined || query.hourTo !== undefined) chips.push({ key: 'hours', label: `Hours ${query.hourFrom ?? 0}:00–${query.hourTo ?? 24}:00`, clear: { hourFrom: undefined, hourTo: undefined } })
  if (query.minimumDurationMilliseconds !== undefined || query.maximumDurationMilliseconds !== undefined) chips.push({ key: 'duration', label: 'Duration range', clear: { minimumDurationMilliseconds: undefined, maximumDurationMilliseconds: undefined } })
  if (query.minimumSizeBytes !== undefined || query.maximumSizeBytes !== undefined) chips.push({ key: 'size', label: 'Size range', clear: { minimumSizeBytes: undefined, maximumSizeBytes: undefined } })
  if (query.thumbnailState) chips.push({ key: 'thumbnail', label: `${query.thumbnailState} thumbnails`, clear: { thumbnailState: undefined } })
  return chips
}

function toLocalInput(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function fromLocalInput(value: string) {
  return value ? new Date(value).toISOString() : undefined
}

function optionalNumber(value: string) {
  return value === '' ? undefined : Number(value)
}

function unitToNumber(value: string, multiplier: number) {
  return value === '' ? undefined : Math.round(Number(value) * multiplier)
}

function millisecondsToUnit(value: number | undefined, divisor: number) {
  return value === undefined ? '' : String(value / divisor)
}
