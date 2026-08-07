import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { LanguageService } from '../../../../core/i18n/language.service';
import {
  getLocalizedText,
  resolveCatalogCategory,
  type CatalogProduct,
  type PublicCatalogCategory
} from '../../models/catalog.model';
import { CatalogEngine } from '../../services/catalog-engine.service';
import { EmptyCatalogStateComponent } from '../../../../shared/ui/catalog/empty-catalog-state.component';
import { ProductMediaComponent } from '../../../../shared/ui/catalog/product-media.component';

@Component({
  selector: 'bgp-catalog-product-detail-page',
  standalone: true,
  imports: [RouterLink, TranslateModule, ProductMediaComponent, EmptyCatalogStateComponent],
  templateUrl: './catalog-product-detail-page.component.html',
  styleUrl: './catalog-product-detail-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CatalogProductDetailPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly catalogEngine = inject(CatalogEngine);
  private readonly language = inject(LanguageService);

  protected readonly category = computed<PublicCatalogCategory | null>(() => {
    const value = this.route.parent?.snapshot.data['catalogCategory'];
    return resolveCatalogCategory(value);
  });

  protected readonly isLoading = this.catalogEngine.isLoading;
  protected readonly hasLoadError = this.catalogEngine.hasLoadError;

  protected readonly productSlug = computed(() => this.route.snapshot.paramMap.get('slug'));

  protected readonly product = computed<CatalogProduct | null>(() => {
    const currentCategory = this.category();
    const slug = this.productSlug();
    if (!currentCategory || !slug) {
      return null;
    }
    return this.catalogEngine.getProductByCategoryAndSlug(currentCategory, slug);
  });

  protected readonly hasProduct = computed(() => this.product() !== null);

  protected readonly categoryTitleKey = computed(() => {
    const value = this.category();
    return value
      ? this.catalogEngine.getCategoryDefinition(value).titleKey
      : 'catalog.categories.apps.title';
  });

  protected readonly categoryPath = computed(() => {
    const value = this.category();
    return value ? this.catalogEngine.getCategoryPath(value) : '/apps';
  });

  protected readonly productName = computed(() => {
    const currentProduct = this.product();
    return currentProduct ? getLocalizedText(currentProduct.name, this.language.language()) : '';
  });

  protected readonly productShortDescription = computed(() => {
    const currentProduct = this.product();
    return currentProduct
      ? getLocalizedText(currentProduct.shortDescription, this.language.language())
      : '';
  });

  protected readonly productDescription = computed(() => {
    const currentProduct = this.product();
    return currentProduct
      ? getLocalizedText(currentProduct.description, this.language.language())
      : '';
  });

  protected readonly statusLabel = computed(() => {
    const currentProduct = this.product();
    return currentProduct?.status ? getLocalizedText(currentProduct.status, this.language.language()) : '';
  });

  protected readonly platforms = computed(() => {
    const currentProduct = this.product();
    return currentProduct
      ? currentProduct.platforms.map((platform) => getLocalizedText(platform, this.language.language()))
      : [];
  });

  protected readonly technologies = computed(() => {
    const currentProduct = this.product();
    return currentProduct
      ? currentProduct.technologies.map((technology) => getLocalizedText(technology, this.language.language()))
      : [];
  });

  protected readonly hasPlatforms = computed(() => this.platforms().length > 0);
  protected readonly hasTechnologies = computed(() => this.technologies().length > 0);
  protected readonly productLinks = computed(() => this.product()?.links ?? []);
  protected readonly hasLinks = computed(() => this.productLinks().length > 0);

  protected getLinkLabel(link: CatalogProduct['links'][number]): string {
    return link.label
      ? getLocalizedText(link.label, this.language.language())
      : '';
  }
}
