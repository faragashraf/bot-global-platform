import type { LiveIceCandidate, LiveSessionDescription, LiveStatistics, LiveViewState } from './models'

const developmentLiveDiagnostics: Array<Record<string, unknown>> = []

export interface LiveMediaSession {
  start(
    sessionId: string,
    onOffer: (offer: LiveSessionDescription) => Promise<void>,
    onCandidate: (candidate: LiveIceCandidate) => Promise<void>,
    onState: (state: LiveViewState) => void,
    onStream: (stream: MediaStream) => void,
  ): Promise<void>
  acceptAnswer(answer: LiveSessionDescription): Promise<void>
  addIceCandidate(candidate: LiveIceCandidate): Promise<void>
  statistics(): Promise<LiveStatistics | null>
  close(): void
}

export class BrowserLiveMediaSession implements LiveMediaSession {
  private peer: RTCPeerConnection | null = null
  private sessionId: string | null = null
  private remoteDescriptionReady = false
  private pendingCandidates: LiveIceCandidate[] = []

  async start(
    sessionId: string,
    onOffer: (offer: LiveSessionDescription) => Promise<void>,
    onCandidate: (candidate: LiveIceCandidate) => Promise<void>,
    onState: (state: LiveViewState) => void,
    onStream: (stream: MediaStream) => void,
  ) {
    this.close()
    this.sessionId = sessionId
    const peer = new RTCPeerConnection({ iceServers: [] })
    this.peer = peer
    liveDiagnostic('peer_created', peerState(peer))
    peer.addTransceiver('video', { direction: 'recvonly' })
    peer.onicecandidate = (event) => {
      if (!event.candidate || this.sessionId !== sessionId) return
      const json = event.candidate.toJSON()
      liveDiagnostic('local_candidate', candidateMetadata(json.candidate ?? ''))
      void onCandidate({
        sessionId,
        candidate: json.candidate ?? '',
        sdpMid: json.sdpMid ?? null,
        sdpMLineIndex: json.sdpMLineIndex ?? null,
        usernameFragment: json.usernameFragment ?? null,
      })
    }
    peer.ontrack = (event) => {
      const stream = event.streams[0] ?? new MediaStream([event.track])
      liveDiagnostic('remote_track', { kind: event.track.kind, readyState: event.track.readyState })
      onStream(stream)
    }
    peer.onconnectionstatechange = () => {
      liveDiagnostic('connection_state', peerState(peer))
      const state: LiveViewState = peer.connectionState === 'connected'
        ? 'connected'
        : peer.connectionState === 'disconnected'
          ? 'buffering'
          : peer.connectionState === 'failed' || peer.connectionState === 'closed'
            ? 'disconnected'
            : 'connecting'
      onState(state)
    }
    peer.oniceconnectionstatechange = () => liveDiagnostic('ice_connection_state', peerState(peer))
    peer.onicegatheringstatechange = () => liveDiagnostic('ice_gathering_state', peerState(peer))
    peer.onsignalingstatechange = () => liveDiagnostic('signaling_state', peerState(peer))
    const offer = await peer.createOffer()
    liveDiagnostic('offer_created', peerState(peer))
    await peer.setLocalDescription(offer)
    liveDiagnostic('local_description_set', peerState(peer))
    if (!offer.sdp) throw new Error('The browser could not create a Live View offer.')
    await onOffer({ sessionId, type: 'offer', sdp: offer.sdp })
    liveDiagnostic('offer_submitted', peerState(peer))
  }

  async acceptAnswer(answer: LiveSessionDescription) {
    const peer = this.requirePeer(answer.sessionId)
    liveDiagnostic('answer_received', peerState(peer))
    await peer.setRemoteDescription({ type: 'answer', sdp: answer.sdp })
    this.remoteDescriptionReady = true
    liveDiagnostic('remote_description_set', peerState(peer))
    const pending = this.pendingCandidates.splice(0)
    for (const candidate of pending) await this.addIceCandidate(candidate)
  }

  async addIceCandidate(candidate: LiveIceCandidate) {
    const peer = this.requirePeer(candidate.sessionId)
    liveDiagnostic('remote_candidate', {
      ...candidateMetadata(candidate.candidate),
      remoteDescriptionReady: this.remoteDescriptionReady,
    })
    if (!this.remoteDescriptionReady) {
      this.pendingCandidates.push(candidate)
      return
    }
    await peer.addIceCandidate({
      candidate: candidate.candidate,
      sdpMid: candidate.sdpMid,
      sdpMLineIndex: candidate.sdpMLineIndex,
      usernameFragment: candidate.usernameFragment ?? undefined,
    })
    liveDiagnostic('remote_candidate_accepted', peerState(peer))
  }

  async statistics(): Promise<LiveStatistics | null> {
    const peer = this.peer
    const sessionId = this.sessionId
    if (!peer || !sessionId) return null
    const reports = await peer.getStats()
    let inbound: Record<string, unknown> | null = null
    let candidatePair: Record<string, unknown> | null = null
    reports.forEach((report) => {
      if (report.type === 'inbound-rtp' && report.kind === 'video') inbound = report
      if (report.type === 'candidate-pair' && report.state === 'succeeded' && report.nominated) candidatePair = report
    })
    liveDiagnostic('statistics', summarizeIce(reports, peer))
    if (!inbound) return null
    const value = inbound as Record<string, unknown>
    const pair = candidatePair as Record<string, unknown> | null
    return {
      sessionId,
      source: 'browser',
      bytesReceived: number(value.bytesReceived),
      bytesSent: null,
      framesPerSecond: number(value.framesPerSecond),
      roundTripTimeMilliseconds: secondsToMilliseconds(pair?.currentRoundTripTime),
      jitterMilliseconds: secondsToMilliseconds(value.jitter),
      packetsLost: number(value.packetsLost),
      frameWidth: number(value.frameWidth),
      frameHeight: number(value.frameHeight),
      sampledAtUtc: new Date().toISOString(),
    }
  }

  close() {
    if (this.peer) {
      this.peer.onicecandidate = null
      this.peer.ontrack = null
      this.peer.onconnectionstatechange = null
      this.peer.oniceconnectionstatechange = null
      this.peer.onicegatheringstatechange = null
      this.peer.onsignalingstatechange = null
      this.peer.close()
    }
    this.peer = null
    this.sessionId = null
    this.remoteDescriptionReady = false
    this.pendingCandidates = []
  }

  private requirePeer(sessionId: string) {
    if (!this.peer || this.sessionId !== sessionId) throw new Error('The Live View session is no longer active.')
    return this.peer
  }
}

const number = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? value : null
const secondsToMilliseconds = (value: unknown) => {
  const seconds = number(value)
  return seconds === null ? null : seconds * 1_000
}

function peerState(peer: RTCPeerConnection) {
  return {
    connectionState: peer.connectionState,
    iceConnectionState: peer.iceConnectionState,
    iceGatheringState: peer.iceGatheringState,
    signalingState: peer.signalingState,
  }
}

function candidateMetadata(candidate: string) {
  const fields = candidate.trim().split(/\s+/)
  const typeIndex = fields.indexOf('typ')
  return {
    protocol: fields[2]?.toLowerCase() ?? 'unknown',
    addressScope: addressScope(fields[4] ?? ''),
    type: typeIndex >= 0 ? fields[typeIndex + 1] ?? 'unknown' : 'unknown',
  }
}

function addressScope(address: string) {
  if (address.endsWith('.local')) return 'mdns'
  if (/^10\.|^192\.168\.|^172\.(1[6-9]|2\d|3[01])\./.test(address)) return 'private_ipv4'
  if (/^169\.254\./.test(address)) return 'link_local_ipv4'
  if (address.includes(':')) return /^fe80:/i.test(address) ? 'link_local_ipv6' : 'ipv6'
  if (/^\d+\.\d+\.\d+\.\d+$/.test(address)) return 'public_ipv4'
  return 'hostname'
}

function summarizeIce(reports: RTCStatsReport, peer: RTCPeerConnection) {
  const localCandidateTypes = new Set<string>()
  const remoteCandidateTypes = new Set<string>()
  const candidatePairStates = new Set<string>()
  reports.forEach((report) => {
    if (report.type === 'local-candidate') {
      localCandidateTypes.add(
        `${report.candidateType ?? 'unknown'}/${report.protocol ?? 'unknown'}/` +
        `${report.networkType ?? 'unknown'}/${addressScope(report.address ?? '')}`,
      )
    }
    if (report.type === 'remote-candidate') {
      remoteCandidateTypes.add(
        `${report.candidateType ?? 'unknown'}/${report.protocol ?? 'unknown'}/` +
        `${addressScope(report.address ?? '')}`,
      )
    }
    if (report.type === 'candidate-pair') candidatePairStates.add(report.state ?? 'unknown')
  })
  return {
    ...peerState(peer),
    localCandidateTypes: [...localCandidateTypes],
    remoteCandidateTypes: [...remoteCandidateTypes],
    candidatePairStates: [...candidatePairStates],
  }
}

function liveDiagnostic(event: string, detail: Record<string, unknown>) {
  if (!import.meta.env.DEV) return
  console.info('[SentriCam Live]', event, detail)
  developmentLiveDiagnostics.push({ event, ...detail })
  if (developmentLiveDiagnostics.length > 100) developmentLiveDiagnostics.shift()
  document.documentElement.setAttribute('data-sentricam-live-diagnostics', JSON.stringify(developmentLiveDiagnostics))
  window.dispatchEvent(new CustomEvent('sentricam-live-diagnostic', { detail: { event, ...detail } }))
}
