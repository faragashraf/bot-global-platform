import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject, of } from 'rxjs';

import { PlatformClientsAdminService } from '../../../platform-clients/data-access/platform-clients-admin.service';
import { PlatformClientListItem } from '../../../platform-clients/models/platform-client.models';
import { NotificationCampaignsAdminService } from '../../data-access/notification-campaigns-admin.service';
import {
  CreateNotificationCampaignRequest,
  NotificationAudiencePreview,
  NotificationCampaignAccepted,
  NotificationCampaignFilters,
  NotificationCampaignPage,
  NotificationCampaignSummary
} from '../../models/notification-campaign.models';
import { NotificationCenterPageComponent } from './notification-center-page.component';

describe('NotificationCenterPageComponent', () => {
  let campaigns: FakeCampaignService;
  let fixture: ComponentFixture<NotificationCenterPageComponent>;
  let component: NotificationCenterPageComponent;

  beforeEach(async () => {
    campaigns = new FakeCampaignService();

    await TestBed.configureTestingModule({
      imports: [NotificationCenterPageComponent],
      providers: [
        provideNoopAnimations(),
        provideTranslateService({ lang: 'en', fallbackLang: 'en' }),
        {
          provide: PlatformClientsAdminService,
          useValue: new FakePlatformClientsService()
        },
        {
          provide: NotificationCampaignsAdminService,
          useValue: campaigns
        }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(NotificationCenterPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    vi.restoreAllMocks();
  });

  it('shows only active Platform Clients and loads audience preview on selection', () => {
    expect(component.applicationOptions().map(option => option.id)).toEqual([
      'active-client'
    ]);

    component.selectApplication('active-client');

    expect(campaigns.previewCalls).toEqual(['active-client']);
    expect(component.audiencePreview()?.activeDeviceCount).toBe(3);
    expect(component.audiencePreview()?.distinctExternalSubjectCount).toBe(2);
  });

  it('opens an explicit confirmation only for a valid bilingual campaign', () => {
    component.openConfirmation();
    expect(component.confirmationOpen()).toBe(false);
    expect(component.showValidation()).toBe(true);

    makeValidDraft(component);
    component.openConfirmation();

    expect(component.confirmationOpen()).toBe(true);
    expect(component.submissionKey()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    );
  });

  it('prevents double submit and reuses the same key only for retrying that submission', () => {
    makeValidDraft(component);
    component.openConfirmation();
    const firstKey = component.submissionKey();

    component.sendCampaign();
    component.sendCampaign();
    expect(campaigns.createCalls).toHaveLength(1);
    expect(campaigns.createCalls[0].idempotencyKey).toBe(firstKey);

    campaigns.createResults[0].error(new HttpErrorResponse({ status: 503 }));
    expect(component.sending()).toBe(false);
    expect(component.submissionKey()).toBe(firstKey);

    component.sendCampaign();
    expect(campaigns.createCalls).toHaveLength(2);
    expect(campaigns.createCalls[1].idempotencyKey).toBe(firstKey);
  });

  it('handles a 202 accepted state and clears notification content', () => {
    makeValidDraft(component);
    component.openConfirmation();
    component.sendCampaign();
    campaigns.createResults[0].next(acceptedCampaign());
    campaigns.createResults[0].complete();

    expect(component.acceptedCampaign()?.status).toBe('Queued');
    expect(component.titleAr()).toBe('');
    expect(component.titleEn()).toBe('');
    expect(component.confirmationOpen()).toBe(false);
    expect(component.submissionKey()).toBeNull();
  });

  it('keeps a safe failure state and never persists message content in browser storage', () => {
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem');
    localStorageSpy.mockClear();
    makeValidDraft(component);
    component.openConfirmation();
    component.sendCampaign();
    campaigns.createResults[0].error(new HttpErrorResponse({ status: 400 }));

    expect(component.submitErrorKey()).toBe(
      'auth.management.notifications.errors.validation'
    );
    expect(localStorageSpy).not.toHaveBeenCalled();
  });

  it('renders aggregate history with dispatched and accepted terminology, never delivered', () => {
    campaigns.page = campaignPage('Completed');
    component.loadHistory();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent?.toLowerCase() ?? '';

    expect(text).toContain('dispatched');
    expect(text).toContain('accepted');
    expect(text).not.toContain('delivered');
    expect(text).not.toContain('registrationtoken');
    expect(text).not.toContain('external subject');
  });

  it('stops active campaign polling when the page is destroyed', () => {
    const clearIntervalSpy = vi.spyOn(window, 'clearInterval');
    campaigns.page = campaignPage('Dispatching');
    component.loadHistory();
    fixture.destroy();

    expect(clearIntervalSpy).toHaveBeenCalled();
  });
});

function makeValidDraft(component: NotificationCenterPageComponent): void {
  component.selectApplication('active-client');
  component.updateTitleAr('عنوان عربي');
  component.updateTitleEn('English title');
  component.updateBodyAr('رسالة عربية');
  component.updateBodyEn('English message');
}

function acceptedCampaign(): NotificationCampaignAccepted {
  return {
    campaignId: 'campaign-id',
    status: 'Queued',
    audienceAsOfUtc: '2026-08-21T10:00:00Z',
    expectedSubjectCount: 2,
    expectedDeviceCount: 3,
    actualRecipientCount: 0,
    createdAtUtc: '2026-08-21T10:00:00Z',
    expiresAtUtc: '2026-09-18T10:00:00Z'
  };
}

function campaignPage(
  status: NotificationCampaignSummary['status']
): NotificationCampaignPage {
  return {
    items: [{
      campaignId: 'campaign-id',
      platformClientId: 'active-client',
      platformClientKey: 'enpo-connect',
      platformClientDisplayName: 'ENPO Connect',
      audienceKind: 'AllCurrentActiveDevices',
      priority: 'Normal',
      type: 'general',
      status,
      audienceAsOfUtc: '2026-08-21T10:00:00Z',
      createdAtUtc: '2026-08-21T10:00:00Z',
      expiresAtUtc: '2026-09-18T10:00:00Z',
      processingStartedAtUtc: '2026-08-21T10:00:01Z',
      completedAtUtc: status === 'Completed' ? '2026-08-21T10:01:00Z' : null,
      createdByDisplayName: 'Administrator',
      audienceSubjectCount: 2,
      audienceDeviceCount: 3,
      pushCapableDeviceCount: 2,
      pendingCount: status === 'Dispatching' ? 1 : 0,
      signalRDispatchedCount: 1,
      fcmAcceptedCount: 2,
      failedCount: 0,
      skippedCount: 0,
      expiredCount: 0
    }],
    page: 1,
    pageSize: 10,
    totalCount: 1,
    queuedOrProcessingCount: status === 'Dispatching' ? 1 : 0,
    completedCount: status === 'Completed' ? 1 : 0,
    completedWithFailuresOrExpiredCount: 0
  };
}

class FakePlatformClientsService {
  list(): Observable<PlatformClientListItem[]> {
    return of([
      platformClient('active-client', 'Active'),
      platformClient('inactive-client', 'Disabled')
    ]);
  }
}

class FakeCampaignService {
  readonly previewCalls: string[] = [];
  readonly createCalls: Array<{
    request: CreateNotificationCampaignRequest;
    idempotencyKey: string;
  }> = [];
  readonly createResults: Subject<NotificationCampaignAccepted>[] = [];
  page = campaignPage('Completed');

  previewAudience(platformClientId: string): Observable<NotificationAudiencePreview> {
    this.previewCalls.push(platformClientId);
    return of({
      platformClientId,
      clientKey: 'enpo-connect',
      displayName: 'ENPO Connect',
      audienceAsOfUtc: '2026-08-21T10:00:00Z',
      distinctExternalSubjectCount: 2,
      activeDeviceCount: 3,
      pushCapableDeviceCount: 2
    });
  }

  create(
    request: CreateNotificationCampaignRequest,
    idempotencyKey: string
  ): Observable<NotificationCampaignAccepted> {
    this.createCalls.push({ request, idempotencyKey });
    const result = new Subject<NotificationCampaignAccepted>();
    this.createResults.push(result);
    return result.asObservable();
  }

  list(filters: NotificationCampaignFilters): Observable<NotificationCampaignPage> {
    return of({ ...this.page, page: filters.page, pageSize: filters.pageSize });
  }

  find(campaignId: string): Observable<NotificationCampaignSummary> {
    return of(this.page.items[0]!);
  }
}

function platformClient(id: string, status: string): PlatformClientListItem {
  return {
    id,
    clientKey: id,
    displayName: id === 'active-client' ? 'ENPO Connect' : 'Inactive app',
    status,
    createdAtUtc: '2026-08-01T00:00:00Z',
    disabledAtUtc: status === 'Active' ? null : '2026-08-20T00:00:00Z',
    capabilities: [],
    credentials: [],
    activeCredentialCount: 0
  };
}
