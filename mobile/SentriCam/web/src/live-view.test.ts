/// <reference types="node" />

import { BrowserLiveMediaSession } from './live-view'
import { readFileSync } from 'node:fs'

const liveViewStyles = readFileSync('src/styles.css', 'utf8')

describe('browser WebRTC subscriber', () => {
  beforeEach(() => {
    FakePeer.instances = []
    vi.stubGlobal('RTCPeerConnection', FakePeer)
  })

  afterEach(() => vi.unstubAllGlobals())

  it('creates a receive-only LAN peer and emits only SDP and ICE signaling', async () => {
    const offers: unknown[] = []
    const candidates: unknown[] = []
    const subscriber = new BrowserLiveMediaSession()

    await subscriber.start(
      'session-1',
      async (offer) => { offers.push(offer) },
      async (candidate) => { candidates.push(candidate) },
      () => undefined,
      () => undefined,
    )
    const peer = FakePeer.instances[0]
    peer.onicecandidate?.({ candidate: { toJSON: () => ({ candidate: 'candidate:host', sdpMid: '0', sdpMLineIndex: 0 }) } })

    expect(peer.configuration).toEqual({ iceServers: [] })
    expect(peer.transceivers).toEqual([{ kind: 'video', init: { direction: 'recvonly' } }])
    expect(offers).toEqual([{ sessionId: 'session-1', type: 'offer', sdp: 'v=0 browser' }])
    expect(candidates).toEqual([{ sessionId: 'session-1', candidate: 'candidate:host', sdpMid: '0', sdpMLineIndex: 0, usernameFragment: null }])
    expect(JSON.stringify({ offers, candidates })).not.toContain('videoBytes')
  })

  it('queues Android ICE until the remote answer is installed', async () => {
    const subscriber = new BrowserLiveMediaSession()
    await subscriber.start('session-1', async () => undefined, async () => undefined, () => undefined, () => undefined)
    const peer = FakePeer.instances[0]

    await subscriber.addIceCandidate({ sessionId: 'session-1', candidate: 'candidate:android', sdpMid: '0', sdpMLineIndex: 0, usernameFragment: null })
    expect(peer.candidates).toHaveLength(0)
    await subscriber.acceptAnswer({ sessionId: 'session-1', type: 'answer', sdp: 'v=0 android' })

    expect(peer.remoteDescription).toEqual({ type: 'answer', sdp: 'v=0 android' })
    expect(peer.candidates).toHaveLength(1)
  })

  it('reports browser receive statistics for lifecycle heartbeats', async () => {
    const subscriber = new BrowserLiveMediaSession()
    await subscriber.start('session-1', async () => undefined, async () => undefined, () => undefined, () => undefined)

    const statistics = await subscriber.statistics()

    expect(statistics).toMatchObject({
      sessionId: 'session-1', source: 'browser', bytesReceived: 4096,
      framesPerSecond: 29.8, roundTripTimeMilliseconds: 24, packetsLost: 1,
      frameWidth: 1280, frameHeight: 720,
    })
  })

  it('trusts intrinsic WebRTC orientation and preserves aspect ratio without CSS rotation hacks', () => {
    const videoRule = liveViewStyles.match(/\.live-video video\s*\{([^}]*)\}/)?.[1]

    expect(videoRule).toBeDefined()
    expect(videoRule).toContain('object-fit: contain')
    expect(videoRule).not.toMatch(/transform|rotate|scaleX|scaleY/)
  })
})

class FakePeer {
  static instances: FakePeer[] = []
  configuration: RTCConfiguration
  transceivers: unknown[] = []
  candidates: RTCIceCandidateInit[] = []
  remoteDescription: RTCSessionDescriptionInit | null = null
  connectionState: RTCPeerConnectionState = 'new'
  onicecandidate: ((event: { candidate: { toJSON(): RTCIceCandidateInit } | null }) => void) | null = null
  ontrack: ((event: RTCTrackEvent) => void) | null = null
  onconnectionstatechange: (() => void) | null = null

  constructor(configuration: RTCConfiguration) {
    this.configuration = configuration
    FakePeer.instances.push(this)
  }
  addTransceiver(kind: string, init: RTCRtpTransceiverInit) { this.transceivers.push({ kind, init }); return {} as RTCRtpTransceiver }
  async createOffer() { return { type: 'offer' as const, sdp: 'v=0 browser' } }
  async setLocalDescription() { return undefined }
  async setRemoteDescription(description: RTCSessionDescriptionInit) { this.remoteDescription = description }
  async addIceCandidate(candidate: RTCIceCandidateInit) { this.candidates.push(candidate) }
  async getStats() {
    return new Map([
      ['inbound', { type: 'inbound-rtp', kind: 'video', bytesReceived: 4096, framesPerSecond: 29.8, jitter: .003, packetsLost: 1, frameWidth: 1280, frameHeight: 720 }],
      ['pair', { type: 'candidate-pair', state: 'succeeded', nominated: true, currentRoundTripTime: .024 }],
    ]) as unknown as RTCStatsReport
  }
  close() { this.connectionState = 'closed' }
}
