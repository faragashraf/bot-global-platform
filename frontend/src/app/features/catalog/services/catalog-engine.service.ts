import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith } from 'rxjs';

import {
  CATALOG_CATEGORIES,
  CATALOG_CATEGORY_DEFINITIONS,
  CATALOG_PRODUCT_LINK_TYPES,
  resolveCatalogCategory,
  type CatalogCategoryDefinition,
  type CatalogProduct,
  type PublicCatalogCategory
} from '../models/catalog.model';
import { CATALOG_REPOSITORY } from './catalog.repository';

type CatalogLoadStatus = 'loading' | 'ready' | 'error';

interface CatalogLoadState {
  readonly status: CatalogLoadStatus;
  readonly products: readonly CatalogProduct[];
}

interface CatalogIndexes {
  readonly category: ReadonlyMap<PublicCatalogCategory, readonly CatalogProduct[]>;
  readonly product: ReadonlyMap<string, CatalogProduct>;
}

const LOADING_STATE: CatalogLoadState = { status: 'loading', products: [] };
const ERROR_STATE: CatalogLoadState = { status: 'error', products: [] };
const PLACEHOLDER_HOST = ['example', 'com'].join('.');

function isSafeExternalLink(url: string): boolean {
  try {
    const parsed = new URL(url);
    const host = parsed.hostname.toLowerCase();
    return (parsed.protocol === 'https:' || parsed.protocol === 'http:') &&
      host !== PLACEHOLDER_HOST &&
      !host.endsWith(`.${PLACEHOLDER_HOST}`);
  } catch {
    return false;
  }
}

export function validateCatalogProducts(products: readonly CatalogProduct[]): void {
  const ids = new Set<string>();
  const categorySlugs = new Set<string>();

  for (const product of products) {
    if (!product.id.trim()) {
      throw new Error('Catalog product ID must not be empty');
    }
    if (ids.has(product.id)) {
      throw new Error(`Duplicate catalog product ID detected: ${product.id}`);
    }
    ids.add(product.id);

    const category = resolveCatalogCategory(product.category);
    if (!category) {
      throw new Error(`Invalid catalog category detected for product: ${product.id}`);
    }
    if (!product.slug.trim()) {
      throw new Error(`Catalog product slug must not be empty: ${product.id}`);
    }
    if (!product.name.en.trim() || !product.name.ar.trim()) {
      throw new Error(`Catalog product name must be localized: ${product.id}`);
    }

    for (const media of [product.heroMedia, ...product.screenshots]) {
      if (media && !media.url.trim()) {
        throw new Error(`Catalog media URL must not be empty: ${product.id}`);
      }
    }

    for (const link of product.links) {
      if (!CATALOG_PRODUCT_LINK_TYPES.includes(link.type)) {
        throw new Error(`Invalid catalog link type detected: ${product.id}`);
      }
      if (!isSafeExternalLink(link.url)) {
        throw new Error(`Invalid catalog link URL detected: ${product.id}`);
      }
    }

    const categorySlug = `${category}:${product.slug}`;
    if (categorySlugs.has(categorySlug)) {
      throw new Error(`Duplicate catalog slug detected: ${category}/${product.slug}`);
    }
    categorySlugs.add(categorySlug);
  }
}

@Injectable({
  providedIn: 'root'
})
export class CatalogEngine {
  private readonly repository = inject(CATALOG_REPOSITORY);

  private readonly state = toSignal(
    this.repository.products$.pipe(
      map((products): CatalogLoadState => {
        validateCatalogProducts(products);
        return { status: 'ready', products };
      }),
      startWith(LOADING_STATE),
      catchError(() => of(ERROR_STATE))
    ),
    { initialValue: LOADING_STATE }
  );

  private readonly indexes = computed<CatalogIndexes>(() => {
    const categoryIndex = new Map<PublicCatalogCategory, CatalogProduct[]>();
    const productIndex = new Map<string, CatalogProduct>();

    for (const category of CATALOG_CATEGORIES) {
      categoryIndex.set(category, []);
    }

    for (const product of this.state().products) {
      productIndex.set(`${product.category}:${product.slug}`, product);
      categoryIndex.get(product.category)?.push(product);
    }

    return { category: categoryIndex, product: productIndex };
  });

  readonly isLoading = computed(() => this.state().status === 'loading');
  readonly hasLoadError = computed(() => this.state().status === 'error');

  getProductsByCategory(category: PublicCatalogCategory): readonly CatalogProduct[] {
    return this.indexes().category.get(category) ?? [];
  }

  getFeaturedProducts(category?: PublicCatalogCategory, limit = 4): readonly CatalogProduct[] {
    const base = category ? this.getProductsByCategory(category) : this.state().products;
    return base.filter((product) => product.featured).slice(0, limit);
  }

  getProductByCategoryAndSlug(category: PublicCatalogCategory, slug: string): CatalogProduct | null {
    const key = `${category}:${slug}`;
    return this.indexes().product.get(key) ?? null;
  }

  getProductCount(category: PublicCatalogCategory): number {
    return this.getProductsByCategory(category).length;
  }

  getCategoryDefinition(category: PublicCatalogCategory): CatalogCategoryDefinition {
    return CATALOG_CATEGORY_DEFINITIONS[category];
  }

  getCategoryPath(category: PublicCatalogCategory): string {
    return `/${this.getCategoryDefinition(category).path}`;
  }

  getProductRoute(product: CatalogProduct): string {
    return `${this.getCategoryPath(product.category)}/${product.slug}`;
  }
}
