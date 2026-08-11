import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AdminCatalogService } from './admin-catalog.service';
import type { AdminCatalogProductWriteRequest } from '../models/admin-catalog.model';

const WRITE_REQUEST: AdminCatalogProductWriteRequest = {
  slug: 'draft-product',
  category: 'app',
  featured: false,
  sortOrder: 2,
  localizations: {
    en: {
      name: 'Draft product',
      shortDescription: 'Short description',
      description: 'Description',
      displayStatus: null,
      platforms: ['Web'],
      technologies: ['Angular']
    },
    ar: {
      name: 'منتج مسودة',
      shortDescription: 'وصف مختصر',
      description: 'الوصف',
      displayStatus: null,
      platforms: ['الويب'],
      technologies: ['أنجولار']
    }
  },
  links: []
};

describe('AdminCatalogService', () => {
  let service: AdminCatalogService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });

    service = TestBed.inject(AdminCatalogService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends trimmed read-only catalog filters with credentials', () => {
    service.getProducts({
      search: '  SentriCam  ',
      category: 'app',
      status: 'Published',
      featured: false
    }).subscribe();

    const request = http.expectOne(
      '/api/admin/catalog/products?search=SentriCam&category=app&status=Published&featured=false'
    );

    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    request.flush({ items: [], total: 0 });
  });

  it('loads one product for draft editing', () => {
    service.getProduct('draft-id').subscribe();

    const request = http.expectOne('/api/admin/catalog/products/draft-id');
    expect(request.request.method).toBe('GET');
    expect(request.request.withCredentials).toBe(true);
    request.flush({});
  });

  it('posts the create draft request without lifecycle fields', () => {
    service.createProduct(WRITE_REQUEST).subscribe();

    const request = http.expectOne('/api/admin/catalog/products');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(WRITE_REQUEST);
    expect(request.request.body.publicationStatus).toBeUndefined();
    expect(request.request.body.publishedAtUtc).toBeUndefined();
    expect(request.request.withCredentials).toBe(true);
    request.flush({});
  });

  it('puts the same write shape when editing a draft', () => {
    service.updateProduct('draft-id', WRITE_REQUEST).subscribe();

    const request = http.expectOne('/api/admin/catalog/products/draft-id');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(WRITE_REQUEST);
    expect(request.request.withCredentials).toBe(true);
    request.flush({});
  });
});
