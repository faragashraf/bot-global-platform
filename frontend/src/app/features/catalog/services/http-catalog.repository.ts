import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, map, throwError } from 'rxjs';

import { environment } from '../../../../environments/environment';
import {
  CATALOG_CATEGORIES,
  CATALOG_PRODUCT_LINK_TYPES,
  type CatalogMediaReference,
  type CatalogProduct,
  type CatalogProductLink,
  type CatalogProductLinkType,
  type LocalizedText,
  type PublicCatalogCategory
} from '../models/catalog.model';
import type { CatalogRepository } from './catalog.repository';

export type CatalogRepositoryErrorCode =
  | 'bad-request'
  | 'not-found'
  | 'network'
  | 'server'
  | 'unexpected-http'
  | 'malformed-response';

export class CatalogRepositoryError extends Error {
  constructor(
    readonly code: CatalogRepositoryErrorCode,
    readonly status: number | null,
    options?: ErrorOptions
  ) {
    super(repositoryErrorMessage(code), options);
    this.name = 'CatalogRepositoryError';
  }
}

function repositoryErrorMessage(code: CatalogRepositoryErrorCode): string {
  switch (code) {
    case 'bad-request':
      return 'The catalog request was rejected.';
    case 'not-found':
      return 'The requested catalog resource was not found.';
    case 'network':
      return 'The catalog service could not be reached.';
    case 'server':
      return 'The catalog service is temporarily unavailable.';
    case 'unexpected-http':
      return 'The catalog request failed.';
    case 'malformed-response':
      return 'The catalog service returned an invalid response.';
  }
}

function malformedResponse(): never {
  throw new CatalogRepositoryError('malformed-response', null);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function readRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : malformedResponse();
}

function readString(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  return typeof value === 'string' && value.trim() ? value : malformedResponse();
}

function readBoolean(record: Record<string, unknown>, key: string): boolean {
  const value = record[key];
  return typeof value === 'boolean' ? value : malformedResponse();
}

function readArray(record: Record<string, unknown>, key: string): readonly unknown[] {
  const value = record[key];
  return Array.isArray(value) ? value : malformedResponse();
}

function readOptional<T>(
  record: Record<string, unknown>,
  key: string,
  mapper: (value: unknown) => T
): T | undefined {
  const value = record[key];
  return value === undefined ? undefined : mapper(value);
}

function mapLocalizedText(value: unknown): LocalizedText {
  const record = readRecord(value);
  return {
    en: readString(record, 'en'),
    ar: readString(record, 'ar')
  };
}

function mapMedia(value: unknown): CatalogMediaReference {
  const record = readRecord(value);
  const alt = readOptional(record, 'alt', mapLocalizedText);

  return {
    url: readString(record, 'url'),
    ...(alt ? { alt } : {})
  };
}

function isCategory(value: string): value is PublicCatalogCategory {
  return (CATALOG_CATEGORIES as readonly string[]).includes(value);
}

function isLinkType(value: string): value is CatalogProductLinkType {
  return (CATALOG_PRODUCT_LINK_TYPES as readonly string[]).includes(value);
}

function mapLink(value: unknown): CatalogProductLink {
  const record = readRecord(value);
  const type = readString(record, 'type');
  if (!isLinkType(type)) {
    malformedResponse();
  }

  const label = readOptional(record, 'label', mapLocalizedText);
  return {
    type,
    url: readString(record, 'url'),
    ...(label ? { label } : {})
  };
}

function mapProduct(value: unknown): CatalogProduct {
  const record = readRecord(value);
  const id = readString(record, 'id');
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) {
    malformedResponse();
  }

  const category = readString(record, 'category');
  if (!isCategory(category)) {
    malformedResponse();
  }

  const status = readOptional(record, 'status', mapLocalizedText);
  const heroMedia = readOptional(record, 'heroMedia', mapMedia);

  return {
    id,
    slug: readString(record, 'slug'),
    category,
    featured: readBoolean(record, 'featured'),
    name: mapLocalizedText(record['name']),
    shortDescription: mapLocalizedText(record['shortDescription']),
    description: mapLocalizedText(record['description']),
    ...(status ? { status } : {}),
    platforms: readArray(record, 'platforms').map(mapLocalizedText),
    technologies: readArray(record, 'technologies').map(mapLocalizedText),
    ...(heroMedia ? { heroMedia } : {}),
    screenshots: readArray(record, 'screenshots').map(mapMedia),
    links: readArray(record, 'links').map(mapLink)
  };
}

export function mapCatalogProductsResponse(value: unknown): readonly CatalogProduct[] {
  return Array.isArray(value) ? value.map(mapProduct) : malformedResponse();
}

function mapRepositoryError(error: unknown): CatalogRepositoryError {
  if (error instanceof CatalogRepositoryError) {
    return error;
  }

  if (!(error instanceof HttpErrorResponse)) {
    return new CatalogRepositoryError('unexpected-http', null, { cause: error });
  }

  if (error.status === 0) {
    return new CatalogRepositoryError('network', 0, { cause: error });
  }
  if (error.status === 400) {
    return new CatalogRepositoryError('bad-request', 400, { cause: error });
  }
  if (error.status === 404) {
    return new CatalogRepositoryError('not-found', 404, { cause: error });
  }
  if (error.status >= 500) {
    return new CatalogRepositoryError('server', error.status, { cause: error });
  }
  if (error.status >= 200 && error.status < 300) {
    return new CatalogRepositoryError('malformed-response', error.status, { cause: error });
  }
  return new CatalogRepositoryError('unexpected-http', error.status, { cause: error });
}

function catalogProductsUrl(): string {
  const baseUrl = environment.apiBaseUrl.replace(/\/+$/, '');
  return `${baseUrl}/api/catalog/products`;
}

@Injectable({ providedIn: 'root' })
export class HttpCatalogRepository implements CatalogRepository {
  private readonly http = inject(HttpClient);

  readonly products$ = this.http.get<unknown>(catalogProductsUrl()).pipe(
    map(mapCatalogProductsResponse),
    catchError((error: unknown) => throwError(() => mapRepositoryError(error)))
  );
}
