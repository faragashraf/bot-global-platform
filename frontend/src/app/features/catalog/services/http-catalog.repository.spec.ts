import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import type { CatalogProduct } from '../models/catalog.model';
import {
  CatalogRepositoryError,
  type CatalogRepositoryErrorCode,
  HttpCatalogRepository
} from './http-catalog.repository';

const PRODUCT_ID = '5c6c331c-8e77-4d29-984e-dc0b30c3c96b';

function productResponse(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: PRODUCT_ID,
    slug: 'catalog-product',
    category: 'app',
    featured: true,
    name: { en: 'Catalog product', ar: 'منتج الكتالوج' },
    shortDescription: { en: 'Short description', ar: 'وصف قصير' },
    description: { en: 'Full description', ar: 'الوصف الكامل' },
    status: { en: 'Available', ar: 'متاح' },
    platforms: [{ en: 'Web', ar: 'ويب' }],
    technologies: [{ en: 'Angular', ar: 'أنجولار' }],
    heroMedia: {
      url: '/media/catalog-product/hero.webp',
      alt: { en: 'Product hero', ar: 'صورة المنتج' }
    },
    screenshots: [
      {
        url: '/media/catalog-product/screenshot.webp',
        alt: { en: 'Product screenshot', ar: 'لقطة شاشة للمنتج' }
      }
    ],
    links: [
      {
        type: 'support',
        url: 'https://support.bot.global/catalog-product',
        label: { en: 'Get support', ar: 'احصل على الدعم' }
      }
    ],
    ...overrides
  };
}

describe('HttpCatalogRepository', () => {
  let repository: HttpCatalogRepository;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    repository = TestBed.inject(HttpCatalogRepository);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flushErrorPayload(payload: object): CatalogRepositoryError {
    let repositoryError: unknown;
    repository.products$.subscribe({ error: (error: unknown) => repositoryError = error });

    http.expectOne('/api/catalog/products').flush(payload);

    expect(repositoryError).toBeInstanceOf(CatalogRepositoryError);
    return repositoryError as CatalogRepositoryError;
  }

  function flushHttpError(status: number): CatalogRepositoryError {
    let repositoryError: unknown;
    repository.products$.subscribe({ error: (error: unknown) => repositoryError = error });

    http.expectOne('/api/catalog/products').flush(
      { title: 'Backend detail that must not reach the UI' },
      { status, statusText: 'Request failed' }
    );

    expect(repositoryError).toBeInstanceOf(CatalogRepositoryError);
    return repositoryError as CatalogRepositoryError;
  }

  function expectErrorCode(error: CatalogRepositoryError, code: CatalogRepositoryErrorCode): void {
    expect(error.code).toBe(code);
    expect(error.message).not.toContain('Backend detail');
  }

  it('maps a successful product list', () => {
    let products: readonly CatalogProduct[] | undefined;
    repository.products$.subscribe((value) => products = value);

    http.expectOne('/api/catalog/products').flush([productResponse()]);

    expect(products).toEqual([
      {
        id: PRODUCT_ID,
        slug: 'catalog-product',
        category: 'app',
        featured: true,
        name: { en: 'Catalog product', ar: 'منتج الكتالوج' },
        shortDescription: { en: 'Short description', ar: 'وصف قصير' },
        description: { en: 'Full description', ar: 'الوصف الكامل' },
        status: { en: 'Available', ar: 'متاح' },
        platforms: [{ en: 'Web', ar: 'ويب' }],
        technologies: [{ en: 'Angular', ar: 'أنجولار' }],
        heroMedia: {
          url: '/media/catalog-product/hero.webp',
          alt: { en: 'Product hero', ar: 'صورة المنتج' }
        },
        screenshots: [
          {
            url: '/media/catalog-product/screenshot.webp',
            alt: { en: 'Product screenshot', ar: 'لقطة شاشة للمنتج' }
          }
        ],
        links: [
          {
            type: 'support',
            url: 'https://support.bot.global/catalog-product',
            label: { en: 'Get support', ar: 'احصل على الدعم' }
          }
        ]
      }
    ]);
  });

  it('maps an empty product list', () => {
    let products: readonly unknown[] | undefined;
    repository.products$.subscribe((value) => products = value);

    http.expectOne('/api/catalog/products').flush([]);

    expect(products).toEqual([]);
  });

  it('rejects a malformed product payload', () => {
    const error = flushErrorPayload([productResponse({ screenshots: 'not-an-array' })]);
    expectErrorCode(error, 'malformed-response');
  });

  it('rejects an invalid category value', () => {
    const error = flushErrorPayload([productResponse({ category: 'other' })]);
    expectErrorCode(error, 'malformed-response');
  });

  it('distinguishes HTTP 400 errors', () => {
    const error = flushHttpError(400);
    expectErrorCode(error, 'bad-request');
    expect(error.status).toBe(400);
  });

  it('distinguishes HTTP 404 errors', () => {
    const error = flushHttpError(404);
    expectErrorCode(error, 'not-found');
    expect(error.status).toBe(404);
  });

  it('distinguishes HTTP 500 errors', () => {
    const error = flushHttpError(500);
    expectErrorCode(error, 'server');
    expect(error.status).toBe(500);
  });

  it('distinguishes network failures', () => {
    let repositoryError: unknown;
    repository.products$.subscribe({ error: (error: unknown) => repositoryError = error });

    http.expectOne('/api/catalog/products').error(new ProgressEvent('network failure'));

    expect(repositoryError).toBeInstanceOf(CatalogRepositoryError);
    const error = repositoryError as CatalogRepositoryError;
    expectErrorCode(error, 'network');
    expect(error.status).toBe(0);
  });

  it('maps localized arrays in order', () => {
    let platforms: readonly unknown[] | undefined;
    let technologies: readonly unknown[] | undefined;
    repository.products$.subscribe(([product]) => {
      platforms = product.platforms;
      technologies = product.technologies;
    });

    http.expectOne('/api/catalog/products').flush([
      productResponse({
        platforms: [
          { en: 'Web', ar: 'ويب' },
          { en: 'Windows', ar: 'ويندوز' }
        ],
        technologies: [
          { en: 'Angular', ar: 'أنجولار' },
          { en: '.NET', ar: '.نت' }
        ]
      })
    ]);

    expect(platforms).toEqual([
      { en: 'Web', ar: 'ويب' },
      { en: 'Windows', ar: 'ويندوز' }
    ]);
    expect(technologies).toEqual([
      { en: 'Angular', ar: 'أنجولار' },
      { en: '.NET', ar: '.نت' }
    ]);
  });

  it('allows optional media and status to be absent', () => {
    const response = productResponse();
    delete response['heroMedia'];
    delete response['status'];
    let product: CatalogProduct | undefined;
    repository.products$.subscribe(([value]) => product = value);

    http.expectOne('/api/catalog/products').flush([response]);

    expect(product?.heroMedia).toBeUndefined();
    expect(product?.status).toBeUndefined();
  });

  it('preserves typed links and localized labels', () => {
    let links: readonly unknown[] | undefined;
    repository.products$.subscribe(([product]) => links = product.links);

    http.expectOne('/api/catalog/products').flush([productResponse({
      links: [
        { type: 'download', url: 'https://downloads.bot.global/product' },
        {
          type: 'website',
          url: 'https://bot.global/product',
          label: { en: 'Product site', ar: 'موقع المنتج' }
        }
      ]
    })]);

    expect(links).toEqual([
      { type: 'download', url: 'https://downloads.bot.global/product' },
      {
        type: 'website',
        url: 'https://bot.global/product',
        label: { en: 'Product site', ar: 'موقع المنتج' }
      }
    ]);
  });
});
