import {
  NOTIFICATION_CAMPAIGN_LIMITS,
  NotificationCampaignDraft,
  validateNotificationCampaignDraft
} from './notification-campaign.models';

describe('notification campaign validation', () => {
  const validDraft: NotificationCampaignDraft = {
    platformClientId: 'client-id',
    titleAr: 'عنوان',
    titleEn: 'Title',
    bodyAr: 'نص',
    bodyEn: 'Body',
    priority: 'Normal',
    lifetimeDays: 28
  };

  it('requires both Arabic and English titles and bodies', () => {
    expect(validateNotificationCampaignDraft({
      ...validDraft,
      titleAr: '',
      titleEn: '',
      bodyAr: '',
      bodyEn: ''
    })).toEqual(expect.objectContaining({
      titleAr: 'required',
      titleEn: 'required',
      bodyAr: 'required',
      bodyEn: 'required'
    }));
  });

  it('enforces title and body character limits centrally', () => {
    const errors = validateNotificationCampaignDraft({
      ...validDraft,
      titleEn: 'x'.repeat(NOTIFICATION_CAMPAIGN_LIMITS.title + 1),
      bodyAr: 'س'.repeat(NOTIFICATION_CAMPAIGN_LIMITS.body + 1)
    });

    expect(errors.titleEn).toBe('maximum');
    expect(errors.bodyAr).toBe('maximum');
  });
});
