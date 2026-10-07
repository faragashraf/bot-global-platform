import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { MobileVersionPoliciesAdminService } from '../../data-access/mobile-version-policies-admin.service';
import {
  MobileVersionPolicyAdminItem,
  MobileVersionPolicyDraft,
} from '../../models/mobile-version-policy.models';

@Component({
  selector: 'app-mobile-version-policies-page',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
  ],
  templateUrl:
    './mobile-version-policies-page.component.html',
  styleUrl:
    './mobile-version-policies-page.component.scss',
  changeDetection:
    ChangeDetectionStrategy.OnPush,
})
export class MobileVersionPoliciesPageComponent {
  private readonly service =
    inject(MobileVersionPoliciesAdminService);

  readonly policies =
    signal<MobileVersionPolicyAdminItem[]>([]);

  readonly drafts =
    signal<Record<string, MobileVersionPolicyDraft>>({});

  readonly loading = signal(false);
  readonly savingKey = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly savedKey = signal<string | null>(null);

  readonly managedCount =
    computed(() => this.policies().length);

  readonly activeCount =
    computed(() =>
      this.policies().filter(policy => policy.isActive)
        .length,
    );

  readonly persistedCount =
    computed(() =>
      this.policies().filter(policy => policy.isPersisted)
        .length,
    );

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.savedKey.set(null);

    this.service
      .list()
      .pipe(
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: policies => {
          this.policies.set(policies);
          this.drafts.set(
            Object.fromEntries(
              policies.map(policy => [
                this.key(policy),
                this.createDraft(policy),
              ]),
            ),
          );
        },
        error: () =>
          this.error.set(
            'auth.management.versionPolicies.errors.load',
          ),
      });
  }

  draftFor(
    policy: MobileVersionPolicyAdminItem,
  ): MobileVersionPolicyDraft {
    return this.drafts()[this.key(policy)]
      ?? this.createDraft(policy);
  }

  updateDraft(
    policy: MobileVersionPolicyAdminItem,
    field: keyof MobileVersionPolicyDraft,
    value: string | boolean,
  ): void {
    const key = this.key(policy);

    this.drafts.update(drafts => ({
      ...drafts,
      [key]: {
        ...this.draftFor(policy),
        [field]: value,
      },
    }));

    if (this.savedKey() === key) {
      this.savedKey.set(null);
    }
  }

  save(policy: MobileVersionPolicyAdminItem): void {
    const key = this.key(policy);
    const draft = this.draftFor(policy);

    this.savingKey.set(key);
    this.error.set(null);
    this.savedKey.set(null);

    this.service
      .update(
        policy.applicationKey,
        policy.platform,
        {
          latestVersion: draft.latestVersion.trim(),
          minimumSupportedVersion:
            draft.minimumSupportedVersion.trim(),
          message:
            draft.message.trim() || null,
          storeDestination:
            draft.storeDestination.trim() || null,
          isActive: draft.isActive,
        },
      )
      .pipe(
        finalize(() => this.savingKey.set(null)),
      )
      .subscribe({
        next: updated => {
          this.policies.update(policies =>
            policies.map(item =>
              this.key(item) === key ? updated : item,
            ),
          );
          this.drafts.update(drafts => ({
            ...drafts,
            [key]: this.createDraft(updated),
          }));
          this.savedKey.set(key);
        },
        error: error => {
          this.error.set(
            error?.status === 400
              ? 'auth.management.versionPolicies.errors.validation'
              : 'auth.management.versionPolicies.errors.save',
          );
        },
      });
  }

  hasChanges(policy: MobileVersionPolicyAdminItem): boolean {
    const draft = this.draftFor(policy);

    return draft.latestVersion.trim() !== policy.latestVersion
      || draft.minimumSupportedVersion.trim()
        !== policy.minimumSupportedVersion
      || this.normalizeOptional(draft.message)
        !== (policy.message ?? '')
      || this.normalizeOptional(draft.storeDestination)
        !== (policy.storeDestination ?? '')
      || draft.isActive !== policy.isActive;
  }

  isSaving(policy: MobileVersionPolicyAdminItem): boolean {
    return this.savingKey() === this.key(policy);
  }

  isSaved(policy: MobileVersionPolicyAdminItem): boolean {
    return this.savedKey() === this.key(policy);
  }

  platformLabel(platform: string): string {
    return `auth.management.versionPolicies.platforms.${platform}`;
  }

  platformIcon(platform: string): string {
    return platform === 'ios'
      ? 'pi pi-apple'
      : 'pi pi-android';
  }

  private createDraft(
    policy: MobileVersionPolicyAdminItem,
  ): MobileVersionPolicyDraft {
    return {
      latestVersion: policy.latestVersion,
      minimumSupportedVersion:
        policy.minimumSupportedVersion,
      message: policy.message ?? '',
      storeDestination: policy.storeDestination ?? '',
      isActive: policy.isActive,
    };
  }

  private key(
    policy: Pick<
      MobileVersionPolicyAdminItem,
      'applicationKey' | 'platform'
    >,
  ): string {
    return `${policy.applicationKey}:${policy.platform}`;
  }

  private normalizeOptional(value: string): string {
    return value.trim();
  }
}
