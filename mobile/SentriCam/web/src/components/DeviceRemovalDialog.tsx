import { useState } from 'react'
import type { DeviceRemovalImpact, DeviceRemovalResult } from '../models'
import { formatRelative } from '../format'
import { Icon } from './Icon'

export function DeviceRemovalDialog({
  deviceId,
  loadImpact,
  removeDevice,
  onRemoved,
}: {
  deviceId: string
  loadImpact: (deviceId: string) => Promise<DeviceRemovalImpact>
  removeDevice: (deviceId: string, deviceName: string) => Promise<DeviceRemovalResult>
  onRemoved: (result: DeviceRemovalResult) => void
}) {
  const [impact, setImpact] = useState<DeviceRemovalImpact | null>(null)
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const inspect = async () => {
    setOpen(true)
    setLoading(true)
    setConfirmed(false)
    setError(null)
    try {
      setImpact(await loadImpact(deviceId))
    } catch {
      setError('The Hub could not check this device. Try again.')
    } finally {
      setLoading(false)
    }
  }

  const remove = async () => {
    if (!impact || !confirmed || !impact.canRemove || removing) return
    setRemoving(true)
    setError(null)
    try {
      const result = await removeDevice(deviceId, impact.deviceName)
      setOpen(false)
      onRemoved(result)
    } catch {
      setError('The device could not be removed. Refresh its status and try again.')
    } finally {
      setRemoving(false)
    }
  }

  return <>
    <button className="button button--danger" onClick={inspect}><Icon name="trash"/>Remove device</button>
    {open && <div className="confirmation-modal" role="dialog" aria-modal="true" aria-labelledby="remove-device-title">
      <section className="confirmation-modal__panel">
        <div className="confirmation-modal__heading">
          <div><span className="eyebrow">Administrator action</span><h2 id="remove-device-title">Remove device</h2></div>
          <button className="button button--quiet" aria-label="Close removal confirmation" onClick={() => setOpen(false)}><Icon name="close"/></button>
        </div>
        {loading ? <div className="detail-loading"><div className="spinner"/>Checking device state…</div> : error && !impact ? <div className="error-banner" role="alert">{error}</div> : impact && <>
          <dl className="removal-summary">
            <div><dt>Device name</dt><dd>{impact.deviceName}</dd></div>
            <div><dt>Device model</dt><dd>{impact.manufacturer} {impact.model}</dd></div>
            <div><dt>Last connection</dt><dd>{formatRelative(impact.lastConnectionAtUtc)}</dd></div>
          </dl>
          <p>Removing this device revokes its pairing, disconnects Realtime, stops Live, and resolves outstanding commands. The phone must scan a new pairing QR before it can reconnect.</p>
          <div className="retention-notice"><Icon name="shield"/><span><strong>Your history is preserved.</strong> Uploaded recordings, recording metadata, audit history, and historical events are not deleted.</span></div>
          {impact.conflicts.length > 0 && <div className="removal-conflicts" role="status">{impact.conflicts.map((conflict) => <p key={conflict.code}><strong>{conflict.blocksRemoval ? 'Action required:' : 'Removal effect:'}</strong> {conflict.message}</p>)}</div>}
          <label className="confirmation-check"><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)}/><span>I understand this phone will return to setup and require pairing again.</span></label>
          {error && <p className="form-error" role="alert">{error}</p>}
          <div className="confirmation-modal__actions">
            <button className="button button--quiet" onClick={() => setOpen(false)}>Cancel</button>
            <button className="button button--danger" disabled={!confirmed || !impact.canRemove || removing} onClick={remove}>{removing ? 'Removing…' : `Remove ${impact.deviceName}`}</button>
          </div>
        </>}
      </section>
    </div>}
  </>
}
