import {
  HttpClient,
  HttpHeaders,
  HttpParams
} from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CreateNotificationCampaignRequest,
  NotificationAudiencePreview,
  NotificationCampaignAccepted,
  NotificationCampaignFilters,
  NotificationCampaignPage,
  NotificationCampaignSummary
} from '../models/notification-campaign.models';

@Injectable({ providedIn: 'root' })
export class NotificationCampaignsAdminService {
  private readonly http = inject(HttpClient);
  private readonly resourceUrl = '/api/admin/notification-campaigns';

  previewAudience(
    platformClientId: string
  ): Observable<NotificationAudiencePreview> {
    return this.http.get<NotificationAudiencePreview>(
      `${this.resourceUrl}/audience-preview/${encodeURIComponent(platformClientId)}`,
      { withCredentials: true }
    );
  }

  create(
    request: CreateNotificationCampaignRequest,
    idempotencyKey: string
  ): Observable<NotificationCampaignAccepted> {
    return this.http.post<NotificationCampaignAccepted>(
      this.resourceUrl,
      request,
      {
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
        withCredentials: true
      }
    );
  }

  list(
    filters: NotificationCampaignFilters
  ): Observable<NotificationCampaignPage> {
    let params = new HttpParams()
      .set('page', filters.page)
      .set('pageSize', filters.pageSize);

    if (filters.platformClientId) {
      params = params.set('platformClientId', filters.platformClientId);
    }

    if (filters.status) {
      params = params.set('status', filters.status);
    }

    if (filters.fromUtc) {
      params = params.set('fromUtc', filters.fromUtc);
    }

    if (filters.toUtc) {
      params = params.set('toUtc', filters.toUtc);
    }

    return this.http.get<NotificationCampaignPage>(this.resourceUrl, {
      params,
      withCredentials: true
    });
  }

  find(campaignId: string): Observable<NotificationCampaignSummary> {
    return this.http.get<NotificationCampaignSummary>(
      `${this.resourceUrl}/${encodeURIComponent(campaignId)}`,
      { withCredentials: true }
    );
  }
}
