import { InjectionToken } from '@angular/core';
import type { Observable } from 'rxjs';

import type { CatalogProduct } from '../models/catalog.model';

export interface CatalogRepository {
  readonly products$: Observable<readonly CatalogProduct[]>;
}

export const CATALOG_REPOSITORY = new InjectionToken<CatalogRepository>('CATALOG_REPOSITORY');
