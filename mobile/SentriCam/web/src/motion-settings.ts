import type { MotionSettingDescriptor } from './models'

export const motionSettingIds = {
  enabled: 'motion.enabled',
  sensitivity: 'motion.sensitivity',
  advancedSensitivity: 'motion.advancedSensitivity',
  triggerDelay: 'motion.triggerDelayMillis',
  stopDelay: 'motion.stopDelayMillis',
  cooldown: 'motion.cooldownMillis',
  frameInterval: 'motion.frameIntervalMillis',
  warmupFrames: 'motion.warmupFrameCount',
} as const

export type MotionSettingId = typeof motionSettingIds[keyof typeof motionSettingIds]

export const writableMotionSettingIds = new Set<MotionSettingId>([
  motionSettingIds.enabled,
  motionSettingIds.sensitivity,
  motionSettingIds.advancedSensitivity,
  motionSettingIds.triggerDelay,
  motionSettingIds.stopDelay,
  motionSettingIds.cooldown,
])

export function motionCapability(capabilities: MotionSettingDescriptor[], id: MotionSettingId) {
  return capabilities.find((item) => item.id === id)
}

export function motionDurationLabel(value: string | number) {
  const milliseconds = Number(value)
  if (milliseconds === 0) return 'Immediate'
  if (milliseconds === 1_000) return '1 second'
  return `${milliseconds / 1_000} seconds`
}

export function isMotionSetting(value: string): value is MotionSettingId {
  return value.startsWith('motion.')
}

export function isAdvancedSensitivityMode(value: string) {
  return value.toLowerCase() === 'advanced'
}
