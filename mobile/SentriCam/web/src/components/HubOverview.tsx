import { useCallback, useEffect, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { HubApi } from '../api'
import type { HubCapabilityStatus, HubStatus, PairingSession } from '../models'
import { Icon } from './Icon'
import { StatusBadge } from './StatusBadge'

export function HubOverview({
  api,
  onShowDevices,
}: {
  api: HubApi
  onShowDevices: () => void
}) {
  const [status, setStatus] = useState<HubStatus | null>(null)
  const [pairing, setPairing] = useState<PairingSession | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const refresh = useCallback(async () => {
    setRefreshing(true)
    setError(null)
    try {
      const [nextStatus, nextPairing] = await Promise.all([
        api.getStatus(),
        api.createPairingSession(),
      ])
      setStatus(nextStatus)
      setPairing(nextPairing)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'The Hub status is temporarily unavailable.')
    } finally {
      setRefreshing(false)
    }
  }, [api])

  const refreshPairing = useCallback(async () => {
    try {
      setPairing(await api.createPairingSession())
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'A new pairing code could not be created.')
    }
  }, [api])

  useEffect(() => { void refresh() }, [refresh])

  useEffect(() => {
    if (!pairing) return
    const refreshAt = new Date(pairing.expiresAtUtc).getTime() - Date.now() - 30_000
    const timer = window.setTimeout(() => void refreshPairing(), Math.max(1_000, refreshAt))
    return () => window.clearTimeout(timer)
  }, [pairing, refreshPairing])

  if (!status && !error) {
    return <div className="hub-overview hub-overview--loading"><div className="spinner spinner--large"/><p>Checking your Hub…</p></div>
  }

  return (
    <main className="hub-overview">
      {error && <div className="error-banner" role="alert"><span>{error}</span><button onClick={() => void refresh()}>Try again</button></div>}
      {status && (
        <>
          <section className="hub-hero">
            <div>
              <span className="eyebrow">{modeLabel(status.mode)} · Local Hub</span>
              <div className="hub-ready-line"><span className="hub-ready-check"><Icon name="check" size={24}/></span><h1>Hub Ready</h1></div>
              <p>{status.hubName} is protecting recordings and ready to welcome your devices.</p>
            </div>
            <div className="hub-hero__meta"><StatusBadge tone="positive" pulse>Running</StatusBadge><button className="button button--quiet" disabled={refreshing} onClick={() => void refresh()}><Icon name="refresh" size={16}/>{refreshing ? 'Checking…' : 'Check status'}</button></div>
          </section>

          <section className="hub-status-grid" aria-label="Hub capability status">
            <CapabilityCard icon="storage" title="Storage" capability={status.storage}/>
            <CapabilityCard icon="database" title={status.mode === 'home' ? 'Hub data' : 'Database'} capability={status.database}/>
            <CapabilityCard icon="media" title="Media engine" capability={status.mediaEngine}/>
            <article className="capability-card">
              <span className="capability-card__icon"><Icon name="devices" size={22}/></span>
              <div><small>Devices</small><strong>{status.deviceMessage}</strong><p>{status.connectedDevices === 0 ? 'Pair a phone to get started.' : 'Open the device list for live status.'}</p></div>
              <span className={'capability-dot ' + (status.connectedDevices > 0 ? 'capability-dot--ready' : 'capability-dot--waiting')}/>
            </article>
          </section>

          <section className="hub-main-grid">
            <article className="pairing-card">
              <div className="pairing-card__copy"><span className="eyebrow">Add your first device</span><h2>Scan to pair</h2><p>Open SentriCam and scan this code with its built-in scanner. No address, port, or access key is required.</p><ol><li>Open SentriCam on Android</li><li>Tap Scan Hub QR</li><li>Point the phone at this short-lived code</li></ol></div>
              <div className="pairing-qr-wrap">
                {pairing ? <QRCodeSVG value={pairing.payload} size={208} level="M" marginSize={2} bgColor="#ffffff" fgColor="#07100e" title={'Pair with ' + pairing.hubName}/> : <div className="pairing-qr-placeholder"><div className="spinner"/></div>}
                <div className="pairing-qr-label"><span className="status-light"/><span><strong>Ready to scan</strong><small>Refreshes automatically for security</small></span></div>
              </div>
            </article>

            <aside className="hub-details-card">
              <span className="eyebrow">This Hub</span>
              <h2>{status.hubName}</h2>
              <dl>
                <div><dt><Icon name="folder" size={17}/>Recording folder</dt><dd title={status.recordingFolder}>{compactPath(status.recordingFolder)}</dd></div>
                <div><dt><Icon name="storage" size={17}/>Storage policy</dt><dd>{storageLabel(status.storagePolicy)}</dd></div>
                <div><dt><Icon name="pulse" size={17}/>Automatic start</dt><dd>{status.startAutomatically ? 'On' : 'Off'}</dd></div>
              </dl>
              <button className="button button--quiet button--wide" onClick={onShowDevices}><Icon name="devices" size={17}/>View devices</button>
            </aside>
          </section>
        </>
      )}
    </main>
  )
}

function CapabilityCard({ icon, title, capability }: { icon: Parameters<typeof Icon>[0]['name']; title: string; capability: HubCapabilityStatus }) {
  return <article className="capability-card"><span className="capability-card__icon"><Icon name={icon} size={22}/></span><div><small>{title}</small><strong>{capability.label}</strong><p>{capability.detail}</p></div><span className={'capability-dot capability-dot--' + capability.status}/></article>
}

function modeLabel(mode: HubStatus['mode']) {
  return mode === 'enterprise' ? 'Enterprise' : mode === 'office' ? 'Office' : 'Home'
}

function storageLabel(policy: HubStatus['storagePolicy']) {
  return policy === 'keep-more' ? 'Keep more' : policy === 'space-saver' ? 'Space saver' : policy === 'custom' ? 'Advanced' : 'Balanced'
}

function compactPath(path: string) {
  if (path.length <= 34) return path
  return '…' + path.slice(-33)
}
