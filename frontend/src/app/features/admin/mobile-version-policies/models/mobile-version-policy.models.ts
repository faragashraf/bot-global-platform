export interface MobileVersionPolicyAdminItem {
  readonly applicationKey: string;
  readonly applicationName: string;
  readonly platform: string;
  readonly latestVersion: string;
  readonly minimumSupportedVersion: string;
  readonly message: string | null;
  readonly storeDestination: string | null;
  readonly isActive: boolean;
  readonly isPersisted: boolean;
  readonly updatedAtUtc: string | null;
  readonly updatedByDisplayName: string | null;
}

export interface UpdateMobileVersionPolicyRequest {
  readonly latestVersion: string;
  readonly minimumSupportedVersion: string;
  readonly message: string | null;
  readonly storeDestination: string | null;
  readonly isActive: boolean;
}

export interface MobileVersionPolicyDraft {
  latestVersion: string;
  minimumSupportedVersion: string;
  message: string;
  storeDestination: string;
  isActive: boolean;
}
