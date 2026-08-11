import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AdminCatalogFilters,
  AdminCatalogProductDetail,
  AdminCatalogProductWriteRequest,
  AdminCatalogProductsResponse
} from '../models/admin-catalog.model';

@Injectable({ providedIn: 'root' })
export class AdminCatalogService {
  private readonly http = inject(HttpClient);

  getProducts(
    filters: AdminCatalogFilters = {}
  ): Observable<AdminCatalogProductsResponse> {
    let params = new HttpParams();

    if (filters.search?.trim()) {
      params = params.set('search', filters.search.trim());
    }

    if (filters.category) {
      params = params.set('category', filters.category);
    }

    if (filters.status) {
      params = params.set('status', filters.status);
    }

    if (filters.featured !== undefined) {
      params = params.set('featured', filters.featured);
    }

    return this.http.get<AdminCatalogProductsResponse>(
      '/api/admin/catalog/products',
      {
        params,
        withCredentials: true
      }
    );
  }

  getProduct(id: string): Observable<AdminCatalogProductDetail> {
    return this.http.get<AdminCatalogProductDetail>(
      `/api/admin/catalog/products/${encodeURIComponent(id)}`,
      { withCredentials: true }
    );
  }

  createProduct(
    request: AdminCatalogProductWriteRequest
  ): Observable<AdminCatalogProductDetail> {
    return this.http.post<AdminCatalogProductDetail>(
      '/api/admin/catalog/products',
      request,
      { withCredentials: true }
    );
  }

  updateProduct(
    id: string,
    request: AdminCatalogProductWriteRequest
  ): Observable<AdminCatalogProductDetail> {
    return this.http.put<AdminCatalogProductDetail>(
      `/api/admin/catalog/products/${encodeURIComponent(id)}`,
      request,
      { withCredentials: true }
    );
  }
}
