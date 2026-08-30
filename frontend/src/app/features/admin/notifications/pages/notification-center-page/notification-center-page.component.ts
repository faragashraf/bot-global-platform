import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslatePipe } from '@ngx-translate/core';
import { DialogModule } from 'primeng/dialog';
import { SelectModule } from 'primeng/select';
import { finalize } from 'rxjs';

import { PlatformClientsAdminService } from '../../../platform-clients/data-access/platform-clients-admin.service';
import { PlatformClientListItem } from '../../../platform-clients/models/platform-client.models';
import { NotificationCampaignsAdminService } from '../../data-access/notification-campaigns-admin.service';
import {
  NOTIFICATION_CAMPAIGN_LIMITS,
  NotificationAudiencePreview,
  NotificationCampaignAccepted,
  NotificationCampaignDraft,
  NotificationCampaignPage,
  NotificationCampaignPriority,
  NotificationCampaignStatus,
  NotificationCampaignSummary,
  validateNotificationCampaignDraft
} from '../../models/notification-campaign.models';

interface ApplicationOption {
  readonly id: string;
  readonly label: string;
  readonly displayName: string;
  readonly clientKey: string;
}

@Component({
  selector: 'app-notification-center-page',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    DialogModule,
    SelectModule
  ],
  templateUrl: './notification-center-page.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class NotificationCenterPageComponent {
  private readonly campaignsService = inject(NotificationCampaignsAdminService);
  private readonly platformClientsService = inject(PlatformClientsAdminService);
  private readonly destroyRef = inject(DestroyRef);

  readonly limits = NOTIFICATION_CAMPAIGN_LIMITS;
  readonly applications = signal<PlatformClientListItem[]>([]);
  readonly applicationsLoading = signal(false);
  readonly applicationsError = signal(false);
  readonly selectedPlatformClientId = signal('');
  readonly audiencePreview = signal<NotificationAudiencePreview | null>(null);
  readonly audienceLoading = signal(false);
  readonly audienceError = signal(false);

  readonly titleAr = signal('');
  readonly titleEn = signal('');
  readonly bodyAr = signal('');
  readonly bodyEn = signal('');
  readonly priority = signal<NotificationCampaignPriority>('Normal');
  readonly lifetimeDays = signal(28);
  readonly showValidation = signal(false);
  readonly confirmationOpen = signal(false);
  readonly sending = signal(false);
  readonly submitErrorKey = signal<string | null>(null);
  readonly acceptedCampaign = signal<NotificationCampaignAccepted | null>(null);
  readonly submissionKey = signal<string | null>(null);

  readonly history = signal<NotificationCampaignPage>({
    items: [],
    page: 1,
    pageSize: 10,
    totalCount: 0,
    queuedOrProcessingCount: 0,
    completedCount: 0,
    completedWithFailuresOrExpiredCount: 0
  });
  readonly historyLoading = signal(false);
  readonly historyError = signal(false);
  readonly historyApplicationId = signal('');
  readonly historyStatus = signal<NotificationCampaignStatus | ''>('');
  readonly periodDays = signal(30);
  readonly detailOpen = signal(false);
  readonly detailLoading = signal(false);
  readonly selectedCampaign = signal<NotificationCampaignSummary | null>(null);

  readonly statusOptions: readonly NotificationCampaignStatus[] = [
    'Queued',
    'PreparingAudience',
    'Dispatching',
    'Completed',
    'CompletedWithFailures',
    'Expired',
    'Failed',
    'Cancelled'
  ];

  readonly lifetimeOptions = [1, 7, 14, 28] as const;
  readonly periodOptions = [7, 30, 90] as const;

  readonly activeApplications = computed(() =>
    this.applications().filter(
      application => application.status.toLowerCase() === 'active'
    )
  );

  readonly applicationOptions = computed<ApplicationOption[]>(() =>
    this.activeApplications().map(application => ({
      id: application.id,
      displayName: application.displayName,
      clientKey: application.clientKey,
      label: `${application.displayName} · ${application.clientKey}`
    }))
  );

  readonly selectedApplication = computed(() =>
    this.applicationOptions().find(
      application => application.id === this.selectedPlatformClientId()
    ) ?? null
  );

  readonly draft = computed<NotificationCampaignDraft>(() => ({
    platformClientId: this.selectedPlatformClientId(),
    titleAr: this.titleAr(),
    titleEn: this.titleEn(),
    bodyAr: this.bodyAr(),
    bodyEn: this.bodyEn(),
    priority: this.priority(),
    lifetimeDays: this.lifetimeDays()
  }));

  readonly draftErrors = computed(() =>
    validateNotificationCampaignDraft(this.draft())
  );

  readonly composerValid = computed(() =>
    Object.keys(this.draftErrors()).length === 0
    && (this.audiencePreview()?.activeDeviceCount ?? 0) > 0
  );

  readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.history().totalCount / this.history().pageSize))
  );

  private pollingHandle: number | null = null;

  constructor() {
    this.loadApplications();
    this.loadHistory();
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  refresh(): void {
    this.loadApplications();
    this.loadHistory();

    if (this.selectedPlatformClientId()) {
      this.loadAudiencePreview(this.selectedPlatformClientId());
    }
  }

  selectApplication(platformClientId: string | null): void {
    this.selectedPlatformClientId.set(platformClientId ?? '');
    this.markDraftChanged();
    this.audiencePreview.set(null);
    this.audienceError.set(false);

    if (platformClientId) {
      this.loadAudiencePreview(platformClientId);
    }
  }

  updateTitleAr(value: string): void {
    this.titleAr.set(value);
    this.markDraftChanged();
  }

  updateTitleEn(value: string): void {
    this.titleEn.set(value);
    this.markDraftChanged();
  }

  updateBodyAr(value: string): void {
    this.bodyAr.set(value);
    this.markDraftChanged();
  }

  updateBodyEn(value: string): void {
    this.bodyEn.set(value);
    this.markDraftChanged();
  }

  setPriority(value: NotificationCampaignPriority): void {
    this.priority.set(value);
    this.markDraftChanged();
  }

  setLifetimeDays(value: number): void {
    this.lifetimeDays.set(Number(value));
    this.markDraftChanged();
  }

  openConfirmation(): void {
    this.showValidation.set(true);
    this.submitErrorKey.set(null);

    if (!this.composerValid() || this.sending()) {
      return;
    }

    this.submissionKey.update(key => key ?? globalThis.crypto.randomUUID());
    this.confirmationOpen.set(true);
  }

  closeConfirmation(): void {
    if (!this.sending()) {
      this.confirmationOpen.set(false);
    }
  }

  sendCampaign(): void {
    if (this.sending() || !this.composerValid()) {
      return;
    }

    const idempotencyKey = this.submissionKey()
      ?? globalThis.crypto.randomUUID();
    this.submissionKey.set(idempotencyKey);
    this.sending.set(true);
    this.submitErrorKey.set(null);

    const draft = this.draft();
    this.campaignsService.create(
      {
        platformClientId: draft.platformClientId,
        titleAr: draft.titleAr.trim(),
        titleEn: draft.titleEn.trim(),
        bodyAr: draft.bodyAr.trim(),
        bodyEn: draft.bodyEn.trim(),
        type: 'general',
        priority: draft.priority,
        lifetimeDays: draft.lifetimeDays,
        audienceKind: 'AllCurrentActiveDevices'
      },
      idempotencyKey
    )
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: accepted => {
          this.acceptedCampaign.set(accepted);
          this.confirmationOpen.set(false);
          this.submissionKey.set(null);
          this.clearMessageContent();
          this.loadHistory();
        },
        error: (error: HttpErrorResponse) => {
          this.submitErrorKey.set(
            error.status === 409
              ? 'auth.management.notifications.errors.conflict'
              : error.status === 400
                ? 'auth.management.notifications.errors.validation'
                : 'auth.management.notifications.errors.send'
          );
        }
      });
  }

  applyHistoryFilters(): void {
    this.loadHistory(false, 1);
  }

  resetHistoryFilters(): void {
    this.historyApplicationId.set('');
    this.historyStatus.set('');
    this.periodDays.set(30);
    this.loadHistory(false, 1);
  }

  previousPage(): void {
    if (this.history().page > 1) {
      this.loadHistory(false, this.history().page - 1);
    }
  }

  nextPage(): void {
    if (this.history().page < this.totalPages()) {
      this.loadHistory(false, this.history().page + 1);
    }
  }

  openDetail(campaign: NotificationCampaignSummary): void {
    this.selectedCampaign.set(campaign);
    this.detailOpen.set(true);
    this.detailLoading.set(true);

    this.campaignsService.find(campaign.campaignId)
      .pipe(finalize(() => this.detailLoading.set(false)))
      .subscribe({
        next: detail => this.selectedCampaign.set(detail),
        error: () => this.historyError.set(true)
      });
  }

  closeDetail(): void {
    this.detailOpen.set(false);
    this.selectedCampaign.set(null);
  }

  statusTone(status: NotificationCampaignStatus): string {
    switch (status) {
      case 'Completed': return 'success';
      case 'CompletedWithFailures': return 'warning';
      case 'Cancelled': return 'warning';
      case 'Expired':
      case 'Failed': return 'danger';
      case 'Dispatching': return 'active';
      case 'PreparingAudience': return 'preparing';
      default: return 'queued';
    }
  }

  private loadApplications(): void {
    this.applicationsLoading.set(true);
    this.applicationsError.set(false);
    this.platformClientsService.list()
      .pipe(finalize(() => this.applicationsLoading.set(false)))
      .subscribe({
        next: applications => this.applications.set(applications),
        error: () => this.applicationsError.set(true)
      });
  }

  private loadAudiencePreview(platformClientId: string): void {
    this.audienceLoading.set(true);
    this.audienceError.set(false);
    this.campaignsService.previewAudience(platformClientId)
      .pipe(finalize(() => this.audienceLoading.set(false)))
      .subscribe({
        next: preview => {
          if (preview.platformClientId === this.selectedPlatformClientId()) {
            this.audiencePreview.set(preview);
          }
        },
        error: () => this.audienceError.set(true)
      });
  }

  loadHistory(silent = false, page = this.history().page): void {
    if (!silent) {
      this.historyLoading.set(true);
    }
    this.historyError.set(false);

    const to = new Date();
    const from = new Date(to);
    from.setUTCDate(from.getUTCDate() - this.periodDays());

    this.campaignsService.list({
      platformClientId: this.historyApplicationId() || undefined,
      status: this.historyStatus() || undefined,
      fromUtc: from.toISOString(),
      toUtc: to.toISOString(),
      page,
      pageSize: this.history().pageSize
    })
      .pipe(finalize(() => this.historyLoading.set(false)))
      .subscribe({
        next: result => {
          this.history.set(result);
          this.updatePolling(result.items);
        },
        error: () => {
          this.historyError.set(true);
          this.stopPolling();
        }
      });
  }

  private updatePolling(campaigns: readonly NotificationCampaignSummary[]): void {
    const hasActiveCampaign = campaigns.some(campaign =>
      campaign.status === 'Queued'
      || campaign.status === 'PreparingAudience'
      || campaign.status === 'Dispatching'
    );

    if (hasActiveCampaign && this.pollingHandle === null) {
      this.pollingHandle = window.setInterval(
        () => this.loadHistory(true),
        15_000
      );
    } else if (!hasActiveCampaign) {
      this.stopPolling();
    }
  }

  private stopPolling(): void {
    if (this.pollingHandle !== null) {
      window.clearInterval(this.pollingHandle);
      this.pollingHandle = null;
    }
  }

  private markDraftChanged(): void {
    if (!this.sending()) {
      this.submissionKey.set(null);
      this.acceptedCampaign.set(null);
    }
  }

  private clearMessageContent(): void {
    this.titleAr.set('');
    this.titleEn.set('');
    this.bodyAr.set('');
    this.bodyEn.set('');
    this.priority.set('Normal');
    this.lifetimeDays.set(28);
    this.showValidation.set(false);
  }
}
