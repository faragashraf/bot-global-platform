import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { PlatformClientsAdminService } from '../../data-access/platform-clients-admin.service';
import {
  CreatedPlatformClient,
  PlatformClientListItem,
} from '../../models/platform-client.models';

@Component({
  selector: 'app-platform-clients-page',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
  ],
  templateUrl:
    './platform-clients-page.component.html',
  styleUrl:
    './platform-clients-page.component.scss',
  changeDetection:
    ChangeDetectionStrategy.OnPush,
})
export class PlatformClientsPageComponent {
  private readonly service =
    inject(PlatformClientsAdminService);
  private readonly translations =
    inject(TranslateService);

  readonly clients =
    signal<PlatformClientListItem[]>([]);

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly rotating = signal(false);
  readonly revokingCredentialId = signal<string | null>(null);
  readonly copied = signal(false);
  readonly error =
    signal<string | null>(null);

  readonly createOpen = signal(false);

  readonly selectedClientForRotation = signal<PlatformClientListItem | null>(null);

  readonly createdClient =
    signal<CreatedPlatformClient | null>(null);

  readonly hasClients =
    computed(() => this.clients().length > 0);

  clientKey = '';
  displayName = '';

  capabilitiesText =
    'platform-clients:probe\n'
    + 'pairing:create\n'
    + 'pairing:status';

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.service
      .list()
      .pipe(
        finalize(
          () => this.loading.set(false),
        ),
      )
      .subscribe({
        next: (clients) =>
          this.clients.set(clients),
        error: () =>
          this.error.set(
            'auth.management.platformClients.loadError',
          ),
      });
  }

  openCreate(): void {
    this.clientKey = '';
    this.displayName = '';
    this.capabilitiesText =
      'platform-clients:probe\n'
      + 'pairing:create\n'
      + 'pairing:status';

    this.createOpen.set(true);
  }

  closeCreate(): void {
    if (!this.saving()) {
      this.createOpen.set(false);
    }
  }

  create(): void {
    const clientKey =
      this.clientKey.trim();

    const displayName =
      this.displayName.trim();

    if (!clientKey || !displayName) {
      return;
    }

    const capabilities =
      this.capabilitiesText
        .split(/[\n,]/)
        .map((value) => value.trim())
        .filter(Boolean);

    this.saving.set(true);
    this.error.set(null);

    this.service
      .create({
        clientKey,
        displayName,
        capabilities,
      })
      .pipe(
        finalize(
          () => this.saving.set(false),
        ),
      )
      .subscribe({
        next: (created) => {
          this.createOpen.set(false);
          this.copied.set(false);
          this.createdClient.set(created);
          this.load();
        },
        error: (error) => {
          this.error.set(
            error?.error?.message
            ?? 'Unable to create platform client.',
          );
        },
      });
  }

  async copySecret(): Promise<void> {
    const secret =
      this.createdClient()?.clientSecret;

    if (!secret) {
      return;
    }

    await navigator.clipboard.writeText(
      secret,
    );

    this.copied.set(true);

    window.setTimeout(
      () => this.copied.set(false),
      1800,
    );
  }

  closeSecret(): void {
    this.createdClient.set(null);
    this.copied.set(false);
  }
  requestRotate(client: PlatformClientListItem): void {
    this.selectedClientForRotation.set(client);
  }

  cancelRotate(): void {
    if (!this.rotating()) this.selectedClientForRotation.set(null);
  }

  rotateCredential(client: PlatformClientListItem): void {
    this.rotating.set(true);
    this.error.set(null);

    this.service.rotateCredential(client.id)
      .pipe(finalize(() => this.rotating.set(false)))
      .subscribe({
        next: (rotated) => {
          this.selectedClientForRotation.set(null);
          this.copied.set(false);
          this.createdClient.set({
            clientId: rotated.clientId,
            clientKey: rotated.clientKey,
            displayName: client.displayName,
            capabilities: client.capabilities,
            clientSecret: rotated.clientSecret,
          });
          this.load();
        },
        error: (error) => this.error.set(
          error?.error?.message ?? 'auth.management.platformClients.lifecycle.rotateError'
        ),
      });
  }

  revokeCredential(client: PlatformClientListItem, credentialId: string): void {
    if (!window.confirm(this.translations.instant('auth.management.platformClients.lifecycle.revokeConfirm'))) {
      return;
    }

    this.revokingCredentialId.set(credentialId);
    this.error.set(null);

    this.service.revokeCredential(client.id, credentialId)
      .pipe(finalize(() => this.revokingCredentialId.set(null)))
      .subscribe({
        next: () => this.load(),
        error: (error) => this.error.set(
          error?.error?.message ?? 'auth.management.platformClients.lifecycle.revokeError'
        ),
      });
  }

}
