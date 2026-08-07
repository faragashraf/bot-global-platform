import { inject, InjectionToken } from '@angular/core';
import type { Observable } from 'rxjs';

import type { CatalogProduct } from '../models/catalog.model';
import { InMemoryCatalogRepository } from './in-memory-catalog.repository';

export interface CatalogRepository {
  readonly products$: Observable<readonly CatalogProduct[]>;
}

export const CATALOG_REPOSITORY = new InjectionToken<CatalogRepository>(
  'CATALOG_REPOSITORY',
  {
    providedIn: 'root',
    factory: () => inject(InMemoryCatalogRepository)
  }
);
