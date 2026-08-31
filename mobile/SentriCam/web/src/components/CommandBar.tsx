import type { CommandAction } from '../models'
import { Icon } from './Icon'

export function CommandBar({ monitoring, busy, onCommand }: {
  monitoring: boolean
  busy: CommandAction | null
  onCommand: (action: CommandAction) => void
}) {
  return (
    <div className="command-bar" aria-label="Device controls">
      <button className="button button--quiet" disabled={busy !== null} onClick={() => onCommand('ping')}>
        <Icon name="ping" /> {busy === 'ping' ? 'Pinging…' : 'Ping'}
      </button>
      <button className="button button--quiet" disabled={busy !== null} onClick={() => onCommand('refresh')}>
        <Icon name="refresh" /> {busy === 'refresh' ? 'Refreshing…' : 'Refresh'}
      </button>
      {monitoring ? (
        <button className="button button--danger" disabled={busy !== null} onClick={() => onCommand('stop-monitoring')}>
          <Icon name="stop" /> {busy === 'stop-monitoring' ? 'Stopping…' : 'Stop monitoring'}
        </button>
      ) : (
        <button className="button button--primary" disabled={busy !== null} onClick={() => onCommand('start-monitoring')}>
          <Icon name="play" /> {busy === 'start-monitoring' ? 'Starting…' : 'Start monitoring'}
        </button>
      )}
    </div>
  )
}
