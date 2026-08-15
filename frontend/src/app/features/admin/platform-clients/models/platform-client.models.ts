export interface PlatformClientCredentialListItem {
  id: string;
  createdAtUtc: string;
  expiresAtUtc: string | null;
  revokedAtUtc: string | null;
  isUsable: boolean;
}

export interface PlatformClientListItem {
  id: string;
  clientKey: string;
  displayName: string;
  status: string;
  createdAtUtc: string;
  disabledAtUtc: string | null;
  capabilities: string[];
  credentials: PlatformClientCredentialListItem[];
  activeCredentialCount: number;
}

export interface CreatePlatformClientRequest {
  clientKey: string;
  displayName: string;
  capabilities: string[];
}

export interface CreatedPlatformClient {
  clientId: string;
  clientKey: string;
  displayName: string;
  capabilities: string[];
  clientSecret: string;
}

export interface RotatedPlatformClientCredential {
  clientId: string;
  credentialId: string;
  clientKey: string;
  clientSecret: string;
  createdAtUtc: string;
}

export type PlatformCapabilityImpact =
  | 'Low'
  | 'Medium'
  | 'High';

export interface PlatformCapabilityDescriptor {
  capability: string;
  name: string;
  description: string;
  grantEffect: string;
  revokeEffect: string;
  impact: PlatformCapabilityImpact;
}

export interface PlatformClientCapabilityState {
  clientId: string;
  clientKey: string;
  grantedCapabilities: string[];
}

export interface SetPlatformClientCapabilitiesRequest {
  capabilities: string[];
}
