import type { MonitoringApi } from '../api'
import { formatBytes, formatTime } from '../format'
import type { RecordingView, RecordingViewMode } from '../models'
import { Icon } from './Icon'
import { RecordingThumbnail } from './RecordingThumbnail'
import { StatusBadge } from './StatusBadge'

export type RecordingActions = {
  play: (recording: RecordingView) => void
  download: (recording: RecordingView) => void
  remove: (recording: RecordingView) => void
  regenerateThumbnail: (recording: RecordingView) => void
}

export function RecordingItem({ api, recording, mode, selected, busy, actions, onSelect }: {
  api: MonitoringApi
  recording: RecordingView
  mode: RecordingViewMode
  selected: boolean
  busy: boolean
  actions: RecordingActions
  onSelect: (recordingId: string, selected: boolean) => void
}) {
  const title = recording.motion ? 'Motion recording' : 'Manual recording'
  const exactTime = formatTime(recording.createdUtc)
  return (
    <article className={`recording-card recording-card--${mode}${selected ? ' recording-card--selected' : ''}`}>
      <label className="recording-card__select"><input type="checkbox" checked={selected} onChange={(event) => onSelect(recording.recordingId, event.target.checked)} aria-label={`Select ${title} from ${recording.deviceName}`}/></label>
      <button className="recording-card__media" onClick={() => actions.play(recording)} aria-label={`Play ${title} from ${recording.deviceName}`}>
        <RecordingThumbnail api={api} url={recording.thumbnailUrl} alt={`Preview of ${title} from ${recording.deviceName}`} state={recording.thumbnailState} />
        <span className="recording-card__play"><Icon name="play" size={18}/></span>
        <span className="recording-card__duration">{formatDuration(recording.durationMilliseconds)}</span>
      </button>
      <div className="recording-card__body">
        <div className="recording-card__badges"><StatusBadge tone={recording.motion ? 'warning' : 'accent'}>{recording.motion ? 'Motion' : 'Manual'}</StatusBadge>{recording.thumbnailState !== 'Ready' && <StatusBadge tone={recording.thumbnailState === 'Failed' ? 'danger' : 'neutral'}>{recording.thumbnailState === 'Failed' ? 'Preview unavailable' : 'Preview processing'}</StatusBadge>}<span>{formatBytes(recording.sizeBytes)}</span></div>
        <h2>{title}</h2>
        <p className="recording-card__device">{recording.deviceName}</p>
        <p className="recording-card__time">{exactTime}</p>
        <dl className="recording-card__metadata"><div><dt>Started</dt><dd>{exactTime}</dd></div><div><dt>Duration</dt><dd>{formatDuration(recording.durationMilliseconds)}</dd></div><div><dt>Size</dt><dd>{formatBytes(recording.sizeBytes)}</dd></div><div><dt>Uploaded</dt><dd>{formatTime(recording.uploadedUtc)}</dd></div><div><dt>Source</dt><dd>{recording.motion ? 'Motion trigger' : 'Manual capture'}</dd></div></dl>
        <details className="recording-card__technical"><summary>File details</summary><span>{recording.fileName}</span><span>Session {recording.sessionId}</span></details>
        <div className="recording-card__actions"><button className="text-button" disabled={busy} onClick={() => actions.play(recording)}><Icon name="play" size={16}/>Play</button><button className="text-button" disabled={busy} onClick={() => actions.download(recording)}><Icon name="download" size={16}/>Download</button>{recording.thumbnailState === 'Failed' && <button className="text-button" disabled={busy} onClick={() => actions.regenerateThumbnail(recording)}><Icon name="retry" size={16}/>Retry preview</button>}<button className="text-button text-button--danger" disabled={busy} onClick={() => actions.remove(recording)}><Icon name="trash" size={16}/>Delete</button></div>
      </div>
    </article>
  )
}

export function formatDuration(milliseconds: number) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainder = seconds % 60
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}` : `${minutes}:${String(remainder).padStart(2, '0')}`
}
