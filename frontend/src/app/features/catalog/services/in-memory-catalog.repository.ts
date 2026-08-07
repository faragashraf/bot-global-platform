import { Injectable } from '@angular/core';
import { of } from 'rxjs';

import { CATALOG_PRODUCTS } from '../data/catalog.data';
import type { CatalogRepository } from './catalog.repository';

@Injectable({ providedIn: 'root' })
export class InMemoryCatalogRepository implements CatalogRepository {
  readonly products$ = of(CATALOG_PRODUCTS);
}
