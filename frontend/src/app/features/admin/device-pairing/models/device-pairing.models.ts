export interface DevicePairingListItem {
  deviceId: string;
  platformClientId: string;
  platformClientDisplayName: string;
  externalSubjectId: string | null;
  installationId: string;
  platform: string;
  deviceName: string | null;
  appVersion: string | null;
  createdAtUtc: string;
  lastPairedAtUtc: string;
  revokedAtUtc: string | null;
  isActive: boolean;
  hasActivePushRegistration: boolean;
}

export interface DevicePairingTimelineEntry {
  occurredAtUtc: string;
  kind: string;
  actorType: string;
  actorDisplayName: string | null;
  detail: string | null;
  source: string;
}

export interface DevicePushRegistrationItem {
  id: string;
  provider: string;
  createdAtUtc: string;
  updatedAtUtc: string;
  invalidatedAtUtc: string | null;
}

export interface DevicePairingDetail {
  device: DevicePairingListItem;
  pushRegistrations: DevicePushRegistrationItem[];
  timeline: DevicePairingTimelineEntry[];
  deliveryLogCount: number;
}

export interface AdminRevokeDeviceResult {
  deviceId: string;
  alreadyRevoked: boolean;
  purgedHistory: boolean;
  purgedAuditEntries: number;
  purgedDeliveryEntries: number;
}
