import { HttpErrorResponse } from '@angular/common/http';
import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  signal
} from '@angular/core';
import {
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { AdminCatalogService } from '../../data-access/admin-catalog.service';
import {
  AdminCatalogCategory,
  AdminCatalogLinkType,
  AdminCatalogProductDetail,
  AdminCatalogProductLink,
  AdminCatalogProductLocalization,
  AdminCatalogProductWriteRequest
} from '../../models/admin-catalog.model';

type LocalizationForm = FormGroup<{
  name: FormControl<string>;
  shortDescription: FormControl<string>;
  description: FormControl<string>;
  displayStatus: FormControl<string>;
  platforms: FormControl<string>;
  technologies: FormControl<string>;
}>;

type LinkForm = FormGroup<{
  type: FormControl<AdminCatalogLinkType>;
  url: FormControl<string>;
  labelEn: FormControl<string>;
  labelAr: FormControl<string>;
  sortOrder: FormControl<number>;
}>;

@Component({
  selector: 'bgp-catalog-product-form-page',
  standalone: true,
  imports: [NgTemplateOutlet, ReactiveFormsModule, RouterLink, TranslateModule],
  templateUrl: './catalog-product-form-page.component.html',
  styleUrl: './catalog-product-form-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CatalogProductFormPageComponent {
  private readonly catalog = inject(AdminCatalogService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly productId = this.route.snapshot.paramMap.get('id');
  readonly editMode = this.productId !== null;
  readonly loading = signal(this.editMode);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly loadFailed = signal(false);

  readonly categories: readonly AdminCatalogCategory[] = [
    'app',
    'game',
    'program'
  ];
  readonly linkTypes: readonly AdminCatalogLinkType[] = [
    'support',
    'privacy',
    'store',
    'download',
    'website'
  ];

  readonly form = new FormGroup({
    slug: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(100),
        Validators.pattern(/^[a-z0-9]+(?:-[a-z0-9]+)*$/)
      ]
    }),
    category: new FormControl<AdminCatalogCategory>('app', {
      nonNullable: true,
      validators: [Validators.required]
    }),
    featured: new FormControl({ value: false, disabled: true }, {
      nonNullable: true
    }),
    sortOrder: new FormControl(0, {
      nonNullable: true,
      validators: [Validators.required, Validators.min(0)]
    }),
    en: this.createLocalizationForm(),
    ar: this.createLocalizationForm(),
    links: new FormArray<LinkForm>([])
  });

  constructor() {
    if (this.productId) {
      this.loadProduct(this.productId);
    }
  }

  get links(): FormArray<LinkForm> {
    return this.form.controls.links;
  }

  addLink(link?: AdminCatalogProductLink): void {
    this.links.push(this.createLinkForm(link));
  }

  removeLink(index: number): void {
    this.links.removeAt(index);
  }

  invalid(path: string): boolean {
    const control = this.form.get(path);
    return Boolean(control?.invalid && (control.dirty || control.touched));
  }

  submit(): void {
    if (this.loading() || this.saving()) return;

    this.form.markAllAsTouched();
    if (this.form.invalid) return;

    this.error.set(null);
    this.saving.set(true);
    const request = this.toRequest();
    const operation = this.productId
      ? this.catalog.updateProduct(this.productId, request)
      : this.catalog.createProduct(request);

    operation
      .pipe(
        finalize(() => this.saving.set(false)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: () => {
          void this.router.navigate(['/admin/catalog'], {
            queryParams: { saved: this.editMode ? 'updated' : 'created' }
          });
        },
        error: (error: HttpErrorResponse) => {
          this.error.set(this.errorKey(error));
        }
      });
  }

  private loadProduct(id: string): void {
    this.catalog.getProduct(id)
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (product) => {
          if (product.publicationStatus !== 'Draft') {
            this.loadFailed.set(true);
            this.error.set(
              'auth.management.catalogWorkspace.authoring.errors.nonDraft'
            );
            return;
          }

          this.patchProduct(product);
        },
        error: (error: HttpErrorResponse) => {
          this.loadFailed.set(true);
          this.error.set(this.errorKey(error));
        }
      });
  }

  private patchProduct(product: AdminCatalogProductDetail): void {
    this.form.patchValue({
      slug: product.slug,
      category: product.category,
      featured: product.featured,
      sortOrder: product.sortOrder,
      en: this.localizationFormValue(product.localizations.en),
      ar: this.localizationFormValue(product.localizations.ar)
    });
    this.links.clear();
    product.links.forEach((link) => this.addLink(link));
  }

  private toRequest(): AdminCatalogProductWriteRequest {
    const value = this.form.getRawValue();
    return {
      slug: value.slug.trim(),
      category: value.category,
      featured: false,
      sortOrder: value.sortOrder,
      localizations: {
        en: this.localizationRequest(value.en),
        ar: this.localizationRequest(value.ar)
      },
      links: value.links.map((link) => ({
        type: link.type,
        url: link.url.trim(),
        labelEn: link.labelEn.trim() || undefined,
        labelAr: link.labelAr.trim() || undefined,
        sortOrder: link.sortOrder
      }))
    };
  }

  private createLocalizationForm(): LocalizationForm {
    const requiredText = [Validators.required];
    return new FormGroup({
      name: new FormControl('', {
        nonNullable: true,
        validators: [...requiredText, Validators.maxLength(200)]
      }),
      shortDescription: new FormControl('', {
        nonNullable: true,
        validators: [...requiredText, Validators.maxLength(600)]
      }),
      description: new FormControl('', {
        nonNullable: true,
        validators: requiredText
      }),
      displayStatus: new FormControl('', {
        nonNullable: true,
        validators: [Validators.maxLength(150)]
      }),
      platforms: new FormControl('', { nonNullable: true }),
      technologies: new FormControl('', { nonNullable: true })
    });
  }

  private createLinkForm(link?: AdminCatalogProductLink): LinkForm {
    return new FormGroup({
      type: new FormControl<AdminCatalogLinkType>(link?.type ?? 'website', {
        nonNullable: true,
        validators: [Validators.required]
      }),
      url: new FormControl(link?.url ?? '', {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.maxLength(2048),
          Validators.pattern(/^https?:\/\/.+/i)
        ]
      }),
      labelEn: new FormControl(link?.labelEn ?? '', {
        nonNullable: true,
        validators: [Validators.maxLength(200)]
      }),
      labelAr: new FormControl(link?.labelAr ?? '', {
        nonNullable: true,
        validators: [Validators.maxLength(200)]
      }),
      sortOrder: new FormControl(link?.sortOrder ?? this.links.length, {
        nonNullable: true,
        validators: [Validators.required, Validators.min(0)]
      })
    });
  }

  private localizationRequest(value: {
    name: string;
    shortDescription: string;
    description: string;
    displayStatus: string;
    platforms: string;
    technologies: string;
  }): AdminCatalogProductLocalization {
    return {
      name: value.name.trim(),
      shortDescription: value.shortDescription.trim(),
      description: value.description.trim(),
      displayStatus: value.displayStatus.trim() || null,
      platforms: this.parseList(value.platforms),
      technologies: this.parseList(value.technologies)
    };
  }

  private localizationFormValue(localization: AdminCatalogProductLocalization) {
    return {
      name: localization.name,
      shortDescription: localization.shortDescription,
      description: localization.description,
      displayStatus: localization.displayStatus ?? '',
      platforms: localization.platforms.join('\n'),
      technologies: localization.technologies.join('\n')
    };
  }

  private parseList(value: string): readonly string[] {
    return value
      .split(/[,\n]/)
      .map((item) => item.trim())
      .filter(Boolean);
  }

  private errorKey(error: HttpErrorResponse): string {
    switch (error.status) {
      case 400:
        return 'auth.management.catalogWorkspace.authoring.errors.request';
      case 401:
      case 403:
        return 'auth.management.catalogWorkspace.authoring.errors.forbidden';
      case 404:
        return 'auth.management.catalogWorkspace.authoring.errors.notFound';
      case 409:
        return 'auth.management.catalogWorkspace.authoring.errors.conflict';
      case 422:
        return 'auth.management.catalogWorkspace.authoring.errors.validation';
      default:
        return 'auth.management.catalogWorkspace.authoring.errors.save';
    }
  }
}
