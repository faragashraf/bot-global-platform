import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { LanguageService } from '../../../../../core/i18n/language.service';
import { AdminCatalogService } from '../../data-access/admin-catalog.service';
import {
  AdminCatalogCategory,
  AdminCatalogProduct,
  AdminPublicationStatus
} from '../../models/admin-catalog.model';

type FeaturedFilter = 'all' | 'featured' | 'standard';

@Component({
  selector: 'bgp-catalog-management-page',
  standalone: true,
  imports: [FormsModule, RouterLink, TranslateModule],
  templateUrl: './catalog-management-page.component.html',
  styleUrl: './catalog-management-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CatalogManagementPageComponent {
  private readonly catalog = inject(AdminCatalogService);
  private readonly route = inject(ActivatedRoute);
  readonly language = inject(LanguageService);

  readonly products = signal<readonly AdminCatalogProduct[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly total = signal(0);
  readonly saved = signal<'created' | 'updated' | null>(
    this.readSavedState()
  );

  search = '';
  category: AdminCatalogCategory | '' = '';
  status: AdminPublicationStatus | '' = '';
  featured: FeaturedFilter = 'all';

  readonly hasProducts = computed(() => this.products().length > 0);

  readonly summary = computed(() => {
    const values = this.products();

    return {
      published: values.filter(
        (product) => product.publicationStatus === 'Published'
      ).length,
      draft: values.filter(
        (product) => product.publicationStatus === 'Draft'
      ).length,
      archived: values.filter(
        (product) => product.publicationStatus === 'Archived'
      ).length
    };
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);

    this.catalog
      .getProducts({
        search: this.search || undefined,
        category: this.category || undefined,
        status: this.status || undefined,
        featured:
          this.featured === 'all'
            ? undefined
            : this.featured === 'featured'
      })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (response) => {
          this.products.set(response.items);
          this.total.set(response.total);
        },
        error: (error: HttpErrorResponse) => {
          this.products.set([]);
          this.total.set(0);

          this.error.set(
            error.status === 401 || error.status === 403
              ? 'auth.management.catalogWorkspace.errors.forbidden'
              : 'auth.management.catalogWorkspace.errors.load'
          );
        }
      });
  }

  resetFilters(): void {
    this.search = '';
    this.category = '';
    this.status = '';
    this.featured = 'all';
    this.load();
  }

  displayName(product: AdminCatalogProduct): string {
    const preferred =
      this.language.language() === 'ar'
        ? product.nameAr
        : product.nameEn;

    return preferred || product.nameEn || product.nameAr || product.slug;
  }

  categoryKey(category: AdminCatalogCategory): string {
    return `auth.management.catalogWorkspace.categories.${category}`;
  }

  statusKey(status: AdminPublicationStatus): string {
    return `auth.management.catalogWorkspace.statuses.${status}`;
  }

  private readSavedState(): 'created' | 'updated' | null {
    const value = this.route.snapshot.queryParamMap.get('saved');
    return value === 'created' || value === 'updated' ? value : null;
  }
}
