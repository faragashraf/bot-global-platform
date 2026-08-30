import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { NotificationCampaignsAdminService } from './notification-campaigns-admin.service';

describe('NotificationCampaignsAdminService', () => {
  let service: NotificationCampaignsAdminService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(NotificationCampaignsAdminService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses only relative api URLs for preview and aggregate detail', () => {
    service.previewAudience('client id').subscribe();
    const preview = http.expectOne(
      '/api/admin/notification-campaigns/audience-preview/client%20id'
    );
    expect(preview.request.method).toBe('GET');
    preview.flush({});

    service.find('campaign id').subscribe();
    const detail = http.expectOne(
      '/api/admin/notification-campaigns/campaign%20id'
    );
    expect(detail.request.url.startsWith('/api/')).toBe(true);
    expect(detail.request.url).not.toContain('localhost');
    detail.flush({});
  });

  it('sends the idempotency key header with the safe campaign body', () => {
    const requestBody = {
      platformClientId: 'client-id',
      titleAr: 'عنوان',
      titleEn: 'Title',
      bodyAr: 'نص',
      bodyEn: 'Body',
      type: 'general' as const,
      priority: 'Normal' as const,
      lifetimeDays: 28,
      audienceKind: 'AllCurrentActiveDevices' as const
    };

    service.create(requestBody, 'submission-uuid').subscribe();

    const request = http.expectOne('/api/admin/notification-campaigns');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('submission-uuid');
    expect(request.request.body).toEqual(requestBody);
    expect(request.request.body.registrationToken).toBeUndefined();
    expect(request.request.body.externalSubjectId).toBeUndefined();
    request.flush({}, { status: 202, statusText: 'Accepted' });
  });

  it('builds server pagination and history filters', () => {
    service.list({
      platformClientId: 'client-id',
      status: 'Dispatching',
      fromUtc: '2026-08-01T00:00:00.000Z',
      toUtc: '2026-08-21T00:00:00.000Z',
      page: 2,
      pageSize: 10
    }).subscribe();

    const request = http.expectOne(candidate =>
      candidate.url === '/api/admin/notification-campaigns'
    );
    expect(request.request.params.get('platformClientId')).toBe('client-id');
    expect(request.request.params.get('status')).toBe('Dispatching');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('pageSize')).toBe('10');
    request.flush({ items: [], page: 2, pageSize: 10, totalCount: 0 });
  });
});
