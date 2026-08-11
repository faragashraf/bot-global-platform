import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AdminCatalogService } from './admin-catalog.service';

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
});
