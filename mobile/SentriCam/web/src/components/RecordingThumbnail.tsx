import { useEffect, useRef, useState } from 'react'
import type { MonitoringApi } from '../api'
import type { RecordingThumbnailState } from '../models'
import { Icon } from './Icon'

export function RecordingThumbnail({ api, url, alt, state }: {
  api: MonitoringApi
  url: string | null
  alt: string
  state: RecordingThumbnailState
}) {
  const container = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === 'undefined')
  const [objectUrl, setObjectUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (visible || !container.current || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setVisible(true)
        observer.disconnect()
      }
    }, { rootMargin: '240px' })
    observer.observe(container.current)
    return () => observer.disconnect()
  }, [visible])

  useEffect(() => {
    if (!url || !visible) return
    const abort = new AbortController()
    let localUrl: string | null = null
    setFailed(false)
    api.fetchRecordingMedia(url, abort.signal).then((blob) => {
      localUrl = URL.createObjectURL(blob)
      setObjectUrl(localUrl)
    }).catch((failure) => {
      if (!(failure instanceof DOMException && failure.name === 'AbortError')) setFailed(true)
      setObjectUrl(null)
    })
    return () => {
      abort.abort()
      setObjectUrl(null)
      if (localUrl) URL.revokeObjectURL(localUrl)
    }
  }, [api, url, visible])

  const label = failed || state === 'Failed'
    ? 'Thumbnail unavailable'
    : state === 'Pending' || state === 'Processing'
      ? 'Preparing thumbnail…'
      : 'Recording preview'

  return (
    <div className="recording-thumbnail" ref={container}>
      {objectUrl && !failed
        ? <img className="recording-card__image" src={objectUrl} alt={alt} loading="lazy" decoding="async" onError={() => setFailed(true)} />
        : <div className={`recording-card__placeholder recording-card__placeholder--${state.toLowerCase()}`}><Icon name={failed || state === 'Failed' ? 'camera' : 'recording'} size={34}/><span>{label}</span></div>}
    </div>
  )
}
