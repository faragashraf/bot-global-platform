export const NOTIFICATION_CAMPAIGN_LIMITS = {
  title: 200,
  body: 4000,
  minimumLifetimeDays: 1,
  maximumLifetimeDays: 28
} as const;

export type NotificationCampaignPriority = 'Normal' | 'High';

export type NotificationCampaignStatus =
  | 'Queued'
  | 'PreparingAudience'
  | 'Dispatching'
  | 'Completed'
  | 'CompletedWithFailures'
  | 'Expired'
  | 'Failed';

export interface NotificationCampaignDraft {
  platformClientId: string;
  titleAr: string;
  titleEn: string;
  bodyAr: string;
  bodyEn: string;
  priority: NotificationCampaignPriority;
  lifetimeDays: number;
}

export type NotificationCampaignDraftErrors = Partial<
  Record<keyof NotificationCampaignDraft, 'required' | 'maximum' | 'range'>
>;

export function validateNotificationCampaignDraft(
  draft: NotificationCampaignDraft
): NotificationCampaignDraftErrors {
  const errors: NotificationCampaignDraftErrors = {};

  validateText('platformClientId', draft.platformClientId, undefined);
  validateText('titleAr', draft.titleAr, NOTIFICATION_CAMPAIGN_LIMITS.title);
  validateText('titleEn', draft.titleEn, NOTIFICATION_CAMPAIGN_LIMITS.title);
  validateText('bodyAr', draft.bodyAr, NOTIFICATION_CAMPAIGN_LIMITS.body);
  validateText('bodyEn', draft.bodyEn, NOTIFICATION_CAMPAIGN_LIMITS.body);

  if (draft.priority !== 'Normal' && draft.priority !== 'High') {
    errors.priority = 'required';
  }

  if (
    draft.lifetimeDays < NOTIFICATION_CAMPAIGN_LIMITS.minimumLifetimeDays ||
    draft.lifetimeDays > NOTIFICATION_CAMPAIGN_LIMITS.maximumLifetimeDays
  ) {
    errors.lifetimeDays = 'range';
  }

  return errors;

  function validateText(
    field: 'platformClientId' | 'titleAr' | 'titleEn' | 'bodyAr' | 'bodyEn',
    value: string,
    maximum: number | undefined
  ): void {
    if (!value.trim()) {
      errors[field] = 'required';
    } else if (maximum !== undefined && value.trim().length > maximum) {
      errors[field] = 'maximum';
    }
  }
}

export interface NotificationAudiencePreview {
  platformClientId: string;
  clientKey: string;
  displayName: string;
  audienceAsOfUtc: string;
  distinctExternalSubjectCount: number;
  activeDeviceCount: number;
  pushCapableDeviceCount: number;
}

export interface CreateNotificationCampaignRequest {
  platformClientId: string;
  titleAr: string;
  titleEn: string;
  bodyAr: string;
  bodyEn: string;
  type: 'general';
  priority: NotificationCampaignPriority;
  lifetimeDays: number;
  audienceKind: 'AllCurrentActiveDevices';
}

export interface NotificationCampaignAccepted {
  campaignId: string;
  status: NotificationCampaignStatus;
  audienceAsOfUtc: string;
  expectedSubjectCount: number;
  expectedDeviceCount: number;
  actualRecipientCount: number;
  createdAtUtc: string;
  expiresAtUtc: string;
}

export interface NotificationCampaignSummary {
  campaignId: string;
  platformClientId: string;
  platformClientKey: string;
  platformClientDisplayName: string;
  audienceKind: 'AllCurrentActiveDevices';
  priority: NotificationCampaignPriority;
  type: string;
  status: NotificationCampaignStatus;
  audienceAsOfUtc: string;
  createdAtUtc: string;
  expiresAtUtc: string;
  processingStartedAtUtc: string | null;
  completedAtUtc: string | null;
  createdByDisplayName: string;
  audienceSubjectCount: number;
  audienceDeviceCount: number;
  pushCapableDeviceCount: number;
  pendingCount: number;
  signalRDispatchedCount: number;
  fcmAcceptedCount: number;
  failedCount: number;
  skippedCount: number;
  expiredCount: number;
}

export interface NotificationCampaignPage {
  items: NotificationCampaignSummary[];
  page: number;
  pageSize: number;
  totalCount: number;
  queuedOrProcessingCount: number;
  completedCount: number;
  completedWithFailuresOrExpiredCount: number;
}

export interface NotificationCampaignFilters {
  platformClientId?: string;
  status?: NotificationCampaignStatus;
  fromUtc?: string;
  toUtc?: string;
  page: number;
  pageSize: number;
}
