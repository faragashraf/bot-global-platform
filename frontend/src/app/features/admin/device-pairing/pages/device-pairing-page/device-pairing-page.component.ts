import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { DialogModule } from 'primeng/dialog';

import { DevicePairingAdminService } from '../../data-access/device-pairing-admin.service';
import {
  DevicePairingDetail,
  DevicePairingListItem,
} from '../../models/device-pairing.models';
import { LanguageService } from '../../../../../core/i18n/language.service';

@Component({
  selector: 'app-device-pairing-page',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    DialogModule,
  ],
  templateUrl:
    './device-pairing-page.component.html',
  styleUrl:
    './device-pairing-page.component.scss',
  changeDetection:
    ChangeDetectionStrategy.OnPush,
})
export class DevicePairingPageComponent {
  private readonly service =
    inject(DevicePairingAdminService);
  private readonly translations =
    inject(TranslateService);
  private readonly languageService =
    inject(LanguageService);

  readonly dateLocale = computed(() =>
    this.languageService.language() === 'ar'
      ? 'ar-EG'
      : 'en-US',
  );

  readonly devices =
    signal<DevicePairingListItem[]>([]);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly search = signal('');

  readonly filteredDevices = computed(() => {
    const query =
      this.search().trim().toLowerCase();

    if (!query) {
      return this.devices();
    }

    return this.devices().filter(
      (device) =>
        (device.deviceName ?? '')
          .toLowerCase()
          .includes(query)
        || device.platform
          .toLowerCase()
          .includes(query)
        || device.installationId
          .toLowerCase()
          .includes(query)
        || (device.externalSubjectId ?? '')
          .toLowerCase()
          .includes(query)
        || device.platformClientDisplayName
          .toLowerCase()
          .includes(query),
    );
  });

  readonly activeCount = computed(
    () =>
      this.devices().filter((d) => d.isActive)
        .length,
  );

  readonly revokedCount = computed(
    () =>
      this.devices().filter((d) => !d.isActive)
        .length,
  );

  readonly detailOpen = signal(false);

  readonly detailLoading = signal(false);

  readonly detail =
    signal<DevicePairingDetail | null>(null);

  readonly revokeTarget =
    signal<DevicePairingListItem | null>(null);

  readonly purgeHistory = signal(false);

  readonly revoking = signal(false);

  readonly revokeResult = signal<string | null>(
    null,
  );

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.service
      .list()
      .pipe(
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (devices) =>
          this.devices.set(devices),
        error: () =>
          this.error.set(
            'auth.management.devicePairing.loadError',
          ),
      });
  }

  onSearchChange(value: string): void {
    this.search.set(value);
  }

  openDetail(device: DevicePairingListItem): void {
    this.detailOpen.set(true);
    this.detailLoading.set(true);
    this.detail.set(null);
    this.error.set(null);

    this.service
      .find(device.deviceId)
      .pipe(
        finalize(() =>
          this.detailLoading.set(false),
        ),
      )
      .subscribe({
        next: (detail) => this.detail.set(detail),
        error: () => {
          this.detailOpen.set(false);

          this.error.set(
            'auth.management.devicePairing.loadError',
          );
        },
      });
  }

  closeDetail(): void {
    if (this.detailLoading()) {
      return;
    }

    this.detailOpen.set(false);
    this.detail.set(null);
  }

  requestRevoke(
    device: DevicePairingListItem,
    event?: Event,
  ): void {
    event?.stopPropagation();

    if (!device.isActive) {
      return;
    }

    this.revokeTarget.set(device);
    this.purgeHistory.set(false);
    this.revokeResult.set(null);
  }

  cancelRevoke(): void {
    if (this.revoking()) {
      return;
    }

    this.revokeTarget.set(null);
    this.revokeResult.set(null);
  }

  confirmRevoke(): void {
    const target = this.revokeTarget();

    if (!target) {
      return;
    }

    const confirmMessage =
      this.translations.instant(
        'auth.management.devicePairing.revoke.confirmText',
        { device: target.deviceName ?? target.platform },
      );

    if (!window.confirm(confirmMessage)) {
      return;
    }

    this.revoking.set(true);
    this.error.set(null);

    this.service
      .revoke(
        target.deviceId,
        this.purgeHistory(),
      )
      .pipe(finalize(() => this.revoking.set(false)))
      .subscribe({
        next: (result) => {
          this.revokeResult.set(
            this.describeRevokeResult(result),
          );
          this.revokeTarget.set(null);
          this.load();

          if (this.detailOpen()) {
            this.openDetailById(target.deviceId);
          }
        },
        error: (error) =>
          this.error.set(
            error?.error?.message
              ?? 'auth.management.devicePairing.revoke.error',
          ),
      });
  }

  kindLabel(kind: string): string {
    return `auth.management.devicePairing.timeline.kinds.${kind}`;
  }

  actorLabel(actorType: string): string {
    return `auth.management.devicePairing.timeline.actors.${actorType}`;
  }

  private describeRevokeResult(result: {
    purgedHistory: boolean;
    alreadyRevoked: boolean;
    purgedAuditEntries: number;
    purgedDeliveryEntries: number;
  }): string {
    if (result.alreadyRevoked) {
      return this.translations.instant(
        'auth.management.devicePairing.revoke.resultAlreadyRevoked',
      );
    }

    return result.purgedHistory
      ? this.translations.instant(
          'auth.management.devicePairing.revoke.resultPurged',
          {
            audit: result.purgedAuditEntries,
            delivery:
              result.purgedDeliveryEntries,
          },
        )
      : this.translations.instant(
          'auth.management.devicePairing.revoke.resultKept',
        );
  }

  private openDetailById(deviceId: string): void {
    this.detailLoading.set(true);

    this.service
      .find(deviceId)
      .pipe(
        finalize(() =>
          this.detailLoading.set(false),
        ),
      )
      .subscribe({
        next: (detail) => this.detail.set(detail),
        error: () => this.detailOpen.set(false),
      });
  }
}
