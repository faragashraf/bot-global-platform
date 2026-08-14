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
