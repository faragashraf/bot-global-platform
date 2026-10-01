export type DeviceLiveState = {
  deviceId: string
  friendlyName: string
  platform: string
  connectionState: 'connected' | 'recovering' | 'offline'
  operationalState: string
  monitoring: boolean
  motion: string
  recording: string
  batteryPercent: number | null
  availableStorageBytes: number | null
  lastHeartbeatAtUtc: string | null
  lastSeenAtUtc: string | null
  snapshotVersion: number | null
  snapshot: Record<string, unknown> | null
  health?: DeviceOperationalHealth
}

export type SubsystemHealth = {
  subsystem: string
  lifecycle: string
  health: string
  recoveryReason: string | null
  reconnectCount: number
  lastFailureAtUtc: string | null
  lastRecoveryAtUtc: string | null
  recoveryDurationMilliseconds: number | null
  updatedAtUtc: string | null
}

export type DeviceOperationalHealth = {
  overall: string
  subsystems: SubsystemHealth[]
  reportedAtUtc: string | null
  presenceState: string | null
  isCurrent: boolean
  outageStartedAtUtc: string | null
  outageCause: string | null
}

export type RecoveryHistoryEntry = {
  subsystem: string
  lifecycle: string
  health: string
  recoveryReason: string | null
  occurredAtUtc: string
  snapshotVersion: number
}

export type RemoteCommandView = {
  commandId: string
  deviceId: string
  commandType: number
  state: number
  correlationId: string
  resultCode: string | null
  requestedAtUtc: string
  completedAtUtc: string | null
}

export type DeviceMonitoringDetails = {
  device: DeviceLiveState
  recentCommands: RemoteCommandView[]
  recoveryHistory?: RecoveryHistoryEntry[]
}

export type DeviceRemovalConflict = {
  code: 'recording_active' | 'live_active' | 'command_executing' | string
  message: string
  blocksRemoval: boolean
}

export type DeviceRemovalImpact = {
  deviceId: string
  deviceName: string
  manufacturer: string
  model: string
  lastConnectionAtUtc: string | null
  canRemove: boolean
  conflicts: DeviceRemovalConflict[]
  retention: {
    uploadedRecordingsPreserved: boolean
    recordingMetadataPreserved: boolean
    auditHistoryPreserved: boolean
    historicalEventsPreserved: boolean
  }
}

export type DeviceRemovalResult = {
  deviceId: string
  removedAtUtc: string
  credentialsRevoked: boolean
  realtimeDisconnected: boolean
  commandsResolved: number
  liveSessionReleased: boolean
  uploadedRecordingsPreserved: boolean
  auditId: string
}

export type CommandAction = 'ping' | 'refresh' | 'start-monitoring' | 'stop-monitoring'

export type RecordingView = {
  recordingId: string
  deviceId: string
  deviceName: string
  clientRecordingId: string
  sessionId: string
  fileName: string
  contentType: string
  durationMilliseconds: number
  sizeBytes: number
  createdUtc: string
  uploadedUtc: string
  motion: boolean
  manual: boolean
  monitoringSession: boolean
  uploadState: RecordingUploadState
  thumbnailState: RecordingThumbnailState
  thumbnailGenerationAttempts: number
  thumbnailErrorCode: string | null
  thumbnailGeneratedUtc: string | null
  checksumSha256: string
  contentUrl: string
  downloadUrl: string
  thumbnailUrl: string | null
}

export type RecordingTriggerFilter = 'All' | 'Motion' | 'Manual'
export type RecordingSourceFilter = 'All' | 'Motion' | 'Manual' | 'MonitoringSession'
export type RecordingUploadState = 'Completed'
export type RecordingThumbnailState = 'Pending' | 'Processing' | 'Ready' | 'Failed'
export type RecordingSort = 'Newest' | 'Oldest' | 'Longest' | 'Shortest' | 'Largest' | 'Smallest' | 'DeviceName' | 'UploadTime' | 'RecordingStartTime'
export type RecordingViewMode = 'large' | 'compact' | 'list'

export type RecordingQuery = {
  deviceIds?: string[]
  search?: string
  source?: RecordingSourceFilter
  dateFromUtc?: string
  dateToUtc?: string
  exactDayUtc?: string
  hourFrom?: number
  hourTo?: number
  minimumDurationMilliseconds?: number
  maximumDurationMilliseconds?: number
  minimumSizeBytes?: number
  maximumSizeBytes?: number
  uploadState?: RecordingUploadState
  thumbnailState?: RecordingThumbnailState
  sort?: RecordingSort
  page?: number
  pageSize?: number
}

export type PagedRecordingResult = {
  items: RecordingView[]
  page: number
  pageSize: number
  totalCount: number
  totalPages: number
  hasPreviousPage: boolean
  hasNextPage: boolean
}

export type RecordingTimeLevel = 'Year' | 'Month' | 'Week' | 'Day' | 'Hour'

export type RecordingTimeNode = {
  key: string
  label: string
  startUtc: string
  endUtc: string
  recordingCount: number
  totalSizeBytes: number
  hasChildren: boolean
  childrenLevel: RecordingTimeLevel | null
}

export type RecordingTimeQuery = Omit<RecordingQuery, 'sort' | 'page' | 'pageSize' | 'exactDayUtc' | 'uploadState'> & {
  level: RecordingTimeLevel
  parentStartUtc?: string
  parentEndUtc?: string
}

export type HubMode = 'home' | 'office' | 'enterprise'
export type HubDatabaseProvider = 'sqlite' | 'sqlserver' | 'postgresql'
export type HubStoragePolicy = 'balanced' | 'keep-more' | 'space-saver' | 'custom'

export type HubDatabaseSettings = {
  provider: HubDatabaseProvider
  host: string | null
  port: number | null
  databaseName: string | null
  username: string | null
  password: string | null
  encrypt: boolean
  trustServerCertificate: boolean
  useIntegratedSecurity: boolean
  hasStoredPassword: boolean
}

export type HubNetworkSettings = {
  httpsEnabled: boolean
  port: number
  certificatePath: string | null
  generateCertificate: boolean
}

export type HubMediaSettings = {
  preferBundledEngine: boolean
  customPath: string | null
}

export type HubSetupDraft = {
  mode: HubMode
  hubName: string
  recordingFolder: string
  storagePolicy: HubStoragePolicy
  retentionDays: number
  minimumFreeSpaceGb: number
  startAutomatically: boolean
  openDashboard: boolean
  database: HubDatabaseSettings
  network: HubNetworkSettings
  media: HubMediaSettings
  currentStep: string
}

export type HubSetupState = {
  isConfigured: boolean
  state: 'NotStarted' | 'InProgress' | 'Completed' | 'Failed' | 'NeedsRepair'
  draft: HubSetupDraft
  defaultRecordingFolder: string
  supportedDatabaseProviders: HubDatabaseProvider[]
  version: string
  configuredAtUtc: string | null
  failure: HubSetupFailure | null
}

export type HubSetupFailure = {
  code: string
  message: string
  failedAtUtc: string
  canRetry: boolean
}

export type HubConfigurationResult = {
  isConfigured: boolean
  hubName: string
  dashboardPath: string
  restartRequired: boolean
  completedActions: string[]
}

export type HubConnectionTestResult = {
  success: boolean
  status: string
  message: string
  latencyMilliseconds: number | null
}

export type HubCapabilityStatus = {
  status: 'ready' | 'warning' | 'missing' | 'broken'
  label: string
  detail: string | null
}

export type HubStatus = {
  isReady: boolean
  hubName: string
  mode: HubMode
  storage: HubCapabilityStatus
  database: HubCapabilityStatus
  mediaEngine: HubCapabilityStatus
  connectedDevices: number
  deviceMessage: string
  recordingFolder: string
  storagePolicy: HubStoragePolicy
  startAutomatically: boolean
  configuredAtUtc: string | null
}

export type PairingSession = {
  payload: string
  hubName: string
  hubAddress: string
  expiresAtUtc: string
  protocolVersion: string
  hubFingerprint: string
}

export type LiveQualityId = 'auto' | 'low' | 'medium' | 'high'
export type LiveViewState = 'idle' | 'connecting' | 'connected' | 'disconnected' | 'buffering' | 'failed'

export type LiveQualityOption = {
  id: LiveQualityId
  label: string
  available: boolean
  width: number
  height: number
  framesPerSecond: number
}

export type LiveCameraCapability = {
  lens: 'front' | 'back'
  available: boolean
  torch: boolean
  zoom: boolean
  resolutions: string[]
  frameRates: number[]
}

export type LiveDeviceCapabilities = {
  deviceId: string
  cameras: LiveCameraCapability[]
  previewVisibilitySupported: boolean
  reportedAtUtc: string
  readiness: LiveReadinessState
  unavailableReason: string | null
}

export type LiveReadinessState = 'unavailable' | 'initializing' | 'ready' | 'starting' | 'live' | 'recovering' | 'failed'

export type LiveViewAvailability = {
  deviceId: string
  available: boolean
  unavailableReason: string | null
  capabilities: LiveDeviceCapabilities | null
  qualityOptions: LiveQualityOption[]
  readiness: LiveReadinessState
  readinessReportedAtUtc: string | null
}

export type LiveSession = {
  sessionId: string
  deviceId: string
  state: 'connecting' | 'negotiating' | 'connected' | 'buffering' | 'disconnected' | 'failed' | 'closed'
  quality: LiveQualityId
  createdAtUtc: string
  lastActivityAtUtc: string
  expiresAtUtc: string
  errorCode: string | null
  deviceAcknowledged: boolean
}

export type LiveSessionDescription = {
  sessionId: string
  type: 'offer' | 'answer'
  sdp: string
}

export type LiveIceCandidate = {
  sessionId: string
  candidate: string
  sdpMid: string | null
  sdpMLineIndex: number | null
  usernameFragment: string | null
}

export type LiveStatistics = {
  sessionId: string
  source: 'browser' | 'device'
  bytesReceived: number | null
  bytesSent: number | null
  framesPerSecond: number | null
  roundTripTimeMilliseconds: number | null
  jitterMilliseconds: number | null
  packetsLost: number | null
  frameWidth: number | null
  frameHeight: number | null
  sampledAtUtc: string
}

export type CameraControlValue = {
  boolean: boolean | null
  number: number | null
  text: string | null
  dateTimeOverlay?: DateTimeOverlayConfiguration | null
}

export type DateTimeOverlayConfiguration = {
  enabled: boolean
  dateEnabled: boolean
  timeEnabled: boolean
  use24HourTime: boolean
  position: 'topLeft' | 'topRight' | 'bottomLeft' | 'bottomRight'
}

export type CameraCapabilityDescriptor = {
  id: string
  supported: boolean
  writable: boolean
  currentValue: CameraControlValue | null
  minimum: number | null
  maximum: number | null
  step: number | null
  allowedValues: string[] | null
  unit: string | null
  reason: string | null
}

export type CameraControlSettings = {
  lens: string
  zoom: number
  torch: boolean
  exposureCompensation: number
  preview: string
  framesPerSecond: number
  resolution: string
  bitrate: number
  quality: string
  nightProfile: string
  dateTimeOverlay?: DateTimeOverlayConfiguration | null
}

export type CameraControlTelemetry = {
  cameraOnline: boolean
  streaming: boolean
  recording: boolean
  recordingState: 'idle' | 'starting' | 'recording' | 'stopping' | 'failed' | string
  recordingOrigin: 'none' | 'manual' | 'motion' | 'remote' | string
  motionArmed: boolean
  batteryPercent: number | null
  temperatureCelsius: number | null
  availableStorageBytes: number | null
  charging: boolean | null
  connectionQuality: string
  previewAvailable: boolean
  audioAvailable: boolean
  latestRecordingUpload: CameraControlRecordingUpload | null
}

export type CameraControlRecordingUpload = {
  clientRecordingId: string
  state: 'queued' | 'uploading' | 'retrying' | 'uploaded' | 'failed' | string
  progressPercent: number
  lastErrorCode: string | null
  serverRecordingId: string | null
  recordedAtUtc: string
  uploadedAtUtc: string | null
}

export type CameraControlDeviceReport = {
  deviceId: string
  settings: CameraControlSettings
  capabilities: CameraCapabilityDescriptor[]
  telemetry: CameraControlTelemetry
  reportedAtUtc: string
  motionSettings: MotionSettingsDeviceReport | null
}

export type MotionSettingValue = CameraControlValue

export type MotionSettingDescriptor = CameraCapabilityDescriptor & {
  requiresCameraRestart: boolean
}

export type MotionSettingsValues = {
  enabled: boolean
  sensitivity: 'low' | 'medium' | 'high' | 'advanced' | string
  advancedSensitivity: number
  triggerDelayMillis: number
  stopDelayMillis: number
  cooldownMillis: number
}

export type MotionEffectiveConfiguration = {
  selectedMode: string
  source: 'preset' | 'custom' | string
  threshold: number
  requiredPositiveFrames: number
  noiseTolerance: number
  changedAreaThreshold: number
  brightnessChangeTolerance: number
  confirmationBehavior: string
}

export type MotionSettingsDeviceReport = {
  settings: MotionSettingsValues
  capabilities: MotionSettingDescriptor[]
  version: number
  effectiveConfiguration?: MotionEffectiveConfiguration | null
}

export type CameraControlCommandState = 1 | 2 | 3 | 4 | 5 | 6 | 'Queued' | 'Executing' | 'Retrying' | 'Succeeded' | 'Failed' | 'Canceled'

export type CameraControlCommand = {
  commandId: string
  deviceId: string
  control: string
  value: CameraControlValue
  state: CameraControlCommandState
  attempts: number
  cancelable: boolean
  retriable: boolean
  correlationId: string
  actorId: string
  resultCode: string | null
  createdAtUtc: string
  completedAtUtc: string | null
  expectedVersion: number | null
}

export type CameraControlCenterView = {
  deviceId: string
  online: boolean
  desiredSettings: CameraControlSettings
  deviceState: CameraControlDeviceReport | null
  recentCommands: CameraControlCommand[]
  updatedAtUtc: string
}

export type CameraControlCommandRequest = {
  control: string
  value: CameraControlValue
  correlationId: string
  expectedVersion?: number | null
}

export type CameraControlGroupCommandRequest = {
  deviceIds: string[]
  control: string
  value: CameraControlValue
  correlationId?: string | null
}

export type CameraControlGroupCommandItem = {
  deviceId: string
  succeeded: boolean
  command: CameraControlCommand | null
  errorCode: string | null
  message: string | null
}

export type CameraControlGroupCommandResult = {
  correlationId: string
  items: CameraControlGroupCommandItem[]
  succeeded: number
  failed: number
}
