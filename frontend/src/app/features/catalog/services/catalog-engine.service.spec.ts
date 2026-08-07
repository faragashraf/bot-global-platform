import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';

import type { CatalogProduct } from '../models/catalog.model';
import { CATALOG_REPOSITORY } from './catalog.repository';
import { CatalogEngine, validateCatalogProducts } from './catalog-engine.service';
import { InMemoryCatalogRepository } from './in-memory-catalog.repository';

const CATALOG_PRODUCT_FIXTURE: CatalogProduct = {
  id: 'catalog-fixture',
  slug: 'catalog-fixture',
  category: 'app',
  featured: true,
  name: { en: 'Catalog fixture', ar: 'عنصر اختبار للكتالوج' },
  shortDescription: { en: 'Test fixture', ar: 'عنصر للاختبار' },
  description: { en: 'Catalog test fixture.', ar: 'عنصر مخصص لاختبار الكتالوج.' },
  platforms: [],
  technologies: [],
  screenshots: [],
  links: []
};

describe('CatalogEngine', () => {
  let engine: CatalogEngine;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: CATALOG_REPOSITORY,
          useExisting: InMemoryCatalogRepository
        }
      ]
    });
    engine = TestBed.inject(CatalogEngine);
  });

  it('gets products by category', () => {
    expect(engine.getProductsByCategory('app').map((product) => product.slug))
      .toEqual(['sentricam']);
    expect(engine.getProductsByCategory('game')).toEqual([]);
    expect(engine.getProductsByCategory('program')).toEqual([]);
  });

  it('gets featured products', () => {
    expect(engine.getFeaturedProducts().map((product) => product.slug))
      .toEqual(['sentricam']);
    expect(engine.getFeaturedProducts('game')).toEqual([]);
  });

  it('gets a product by category and slug', () => {
    expect(engine.getProductByCategoryAndSlug('app', 'sentricam')?.id)
      .toBe('sentricam');
  });

  it('isolates identical lookups by category', () => {
    expect(engine.getProductByCategoryAndSlug('game', 'sentricam')).toBeNull();
    expect(engine.getProductByCategoryAndSlug('program', 'sentricam')).toBeNull();
  });

  it('returns null when a product is missing', () => {
    expect(engine.getProductByCategoryAndSlug('app', 'missing')).toBeNull();
  });
});

describe('validateCatalogProducts', () => {
  it('rejects a duplicate slug within a category', () => {
    const duplicate: CatalogProduct = {
      ...CATALOG_PRODUCT_FIXTURE,
      id: 'duplicate-catalog-fixture'
    };

    expect(() => validateCatalogProducts([CATALOG_PRODUCT_FIXTURE, duplicate]))
      .toThrowError('Duplicate catalog slug detected: app/catalog-fixture');
  });

  it('rejects duplicate product IDs', () => {
    const duplicate: CatalogProduct = {
      ...CATALOG_PRODUCT_FIXTURE,
      slug: 'catalog-fixture-copy'
    };

    expect(() => validateCatalogProducts([CATALOG_PRODUCT_FIXTURE, duplicate]))
      .toThrowError('Duplicate catalog product ID detected: catalog-fixture');
  });

  it('rejects missing localized product names', () => {
    const missingArabicName: CatalogProduct = {
      ...CATALOG_PRODUCT_FIXTURE,
      id: 'missing-arabic-name',
      slug: 'missing-arabic-name',
      name: { en: 'Name', ar: '   ' }
    };

    expect(() => validateCatalogProducts([missingArabicName]))
      .toThrowError('Catalog product name must be localized: missing-arabic-name');
  });
});

describe('CatalogEngine repository state', () => {
  it('reacts to an asynchronous repository without changing selectors', () => {
    const products = new Subject<readonly CatalogProduct[]>();

    TestBed.configureTestingModule({
      providers: [
        {
          provide: CATALOG_REPOSITORY,
          useValue: { products$: products.asObservable() }
        }
      ]
    });

    const engine = TestBed.inject(CatalogEngine);
    expect(engine.isLoading()).toBe(true);
    expect(engine.getProductsByCategory('app')).toEqual([]);

    products.next([CATALOG_PRODUCT_FIXTURE]);

    expect(engine.isLoading()).toBe(false);
    expect(engine.hasLoadError()).toBe(false);
    expect(engine.getProductByCategoryAndSlug('app', 'catalog-fixture'))
      .toEqual(CATALOG_PRODUCT_FIXTURE);
  });

  it('treats an empty asynchronous response as a successful catalog load', () => {
    const products = new Subject<readonly CatalogProduct[]>();

    TestBed.configureTestingModule({
      providers: [
        {
          provide: CATALOG_REPOSITORY,
          useValue: { products$: products.asObservable() }
        }
      ]
    });

    const engine = TestBed.inject(CatalogEngine);
    products.next([]);

    expect(engine.isLoading()).toBe(false);
    expect(engine.hasLoadError()).toBe(false);
    expect(engine.getProductsByCategory('app')).toEqual([]);
  });

  it('exposes a stable empty error state when the repository fails', () => {
    const products = new Subject<readonly CatalogProduct[]>();

    TestBed.configureTestingModule({
      providers: [
        {
          provide: CATALOG_REPOSITORY,
          useValue: { products$: products.asObservable() }
        }
      ]
    });

    const engine = TestBed.inject(CatalogEngine);
    products.error(new Error('Private repository failure'));

    expect(engine.isLoading()).toBe(false);
    expect(engine.hasLoadError()).toBe(true);
    expect(engine.getFeaturedProducts()).toEqual([]);
  });

  it('exposes a stable empty error state when product validation fails', () => {
    const products = new Subject<readonly CatalogProduct[]>();
    const malformedProduct: CatalogProduct = {
      ...CATALOG_PRODUCT_FIXTURE,
      id: 'malformed-catalog-fixture',
      slug: 'malformed-catalog-fixture',
      name: { en: 'Malformed fixture', ar: '   ' }
    };

    TestBed.configureTestingModule({
      providers: [
        {
          provide: CATALOG_REPOSITORY,
          useValue: { products$: products.asObservable() }
        }
      ]
    });

    const engine = TestBed.inject(CatalogEngine);
    products.next([malformedProduct]);

    expect(engine.isLoading()).toBe(false);
    expect(engine.hasLoadError()).toBe(true);
    expect(engine.getProductsByCategory('app')).toEqual([]);
  });
});
