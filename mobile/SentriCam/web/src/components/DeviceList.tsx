import type { DeviceLiveState } from '../models'
import { formatRelative } from '../format'
import { Icon } from './Icon'
import { StatusBadge } from './StatusBadge'
import { getEffectiveConnectionState } from '../monitoringHealth'

export function DeviceList({ devices, selectedId, onSelect }: {
  devices: DeviceLiveState[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  return (
    <aside className="device-rail" aria-label="Devices">
      <div className="device-rail__heading">
        <div><span className="eyebrow">Fleet</span><h2>Devices</h2></div>
        <span className="device-count">{devices.length}</span>
      </div>
      <div className="device-list">
        {devices.map((device) => {
          const effective = getEffectiveConnectionState(device)
          return (
            <button
              className={`device-row${device.deviceId === selectedId ? ' device-row--selected' : ''}${effective.isOffline ? ' device-row--offline' : effective.isRecovering ? ' device-row--recovering' : ''}`}
              key={device.deviceId}
              onClick={() => onSelect(device.deviceId)}
            >
              <span className="device-row__icon"><Icon name="camera" /></span>
              <span className="device-row__body">
                <span className="device-row__title">{device.friendlyName}</span>
                <span className="device-row__meta">{device.platform} · {formatRelative(device.lastHeartbeatAtUtc)}</span>
              </span>
              <StatusBadge tone={effective.tone} pulse={effective.pulse}>
                {effective.label}
              </StatusBadge>
            </button>
          )
        })}
      </div>
    </aside>
  )
}
