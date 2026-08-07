import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { CatalogEngine } from '../../services/catalog-engine.service';
import {
  resolveCatalogCategory,
  type CatalogProduct,
  type PublicCatalogCategory
} from '../../models/catalog.model';
import { CatalogSectionHeaderComponent } from '../../../../shared/ui/catalog/catalog-section-header.component';
import { EmptyCatalogStateComponent } from '../../../../shared/ui/catalog/empty-catalog-state.component';
import { ProductCardComponent } from '../../../../shared/ui/catalog/product-card.component';

@Component({
  selector: 'bgp-catalog-category-page',
  standalone: true,
  imports: [
    TranslateModule,
    CatalogSectionHeaderComponent,
    ProductCardComponent,
    EmptyCatalogStateComponent
  ],
  templateUrl: './catalog-category-page.component.html',
  styleUrl: './catalog-category-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CatalogCategoryPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly catalogEngine = inject(CatalogEngine);

  protected readonly category = computed<PublicCatalogCategory | null>(() => {
    const value = this.route.parent?.snapshot.data['catalogCategory'];
    return resolveCatalogCategory(value) ;
  });

  protected readonly isCategoryKnown = computed(() => this.category() !== null);
  protected readonly isLoading = this.catalogEngine.isLoading;
  protected readonly hasLoadError = this.catalogEngine.hasLoadError;

  protected readonly titleKey = computed(() => {
    const value = this.category();
    return value
      ? this.catalogEngine.getCategoryDefinition(value).titleKey
      : 'catalog.categories.apps.title';
  });

  protected readonly descriptionKey = computed(() => {
    const value = this.category();
    return value
      ? this.catalogEngine.getCategoryDefinition(value).descriptionKey
      : 'catalog.categories.apps.description';
  });

  protected readonly products = computed<readonly CatalogProduct[]>(() => {
    const value = this.category();
    return value ? this.catalogEngine.getProductsByCategory(value) : [];
  });

  protected readonly count = computed(() => this.products().length);

  protected readonly hasProducts = computed(() => this.products().length > 0);

  protected readonly productPath = computed(() => {
    return '/apps';
  });

  protected buildProductRoute(product: CatalogProduct): string {
    return this.catalogEngine.getProductRoute(product);
  }
}
