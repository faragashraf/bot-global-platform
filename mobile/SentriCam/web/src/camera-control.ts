import type { CameraCapabilityDescriptor, CameraControlCommandRequest, CameraControlValue, DateTimeOverlayConfiguration } from './models'

export const cameraControlIds = {
  recording: 'recording',
  lens: 'lens',
  zoom: 'zoom',
  torch: 'torch',
  exposure: 'exposureCompensation',
  preview: 'preview',
  fps: 'framesPerSecond',
  resolution: 'resolution',
  bitrate: 'bitrate',
  quality: 'quality',
  nightProfile: 'nightProfile',
  dateTimeOverlay: 'dateTimeOverlay',
} as const

export const recordingActions = { start: 'start', stop: 'stop' } as const

export type CameraControlId = typeof cameraControlIds[keyof typeof cameraControlIds]

export function capability(capabilities: CameraCapabilityDescriptor[], id: CameraControlId) {
  return capabilities.find((item) => item.id === id)
}

export function canWrite(descriptor: CameraCapabilityDescriptor | undefined) {
  return descriptor?.supported === true && descriptor.writable === true
}

export function commandRequest(
  control: string,
  value: boolean | number | string | DateTimeOverlayConfiguration,
  expectedVersion?: number,
): CameraControlCommandRequest {
  const typed: CameraControlValue = typeof value === 'boolean'
    ? { boolean: value, number: null, text: null }
    : typeof value === 'number'
      ? { boolean: null, number: value, text: null }
      : typeof value === 'string'
        ? { boolean: null, number: null, text: value }
        : { boolean: null, number: null, text: null, dateTimeOverlay: value }
  return {
    control,
    value: typed,
    correlationId: globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    expectedVersion: expectedVersion ?? null,
  }
}

export function commandStateLabel(value: number | string) {
  if (typeof value === 'string') return value
  return ({ 1: 'Queued', 2: 'Executing', 3: 'Retrying', 4: 'Succeeded', 5: 'Failed', 6: 'Canceled' } as Record<number, string>)[value] ?? 'Unknown'
}
