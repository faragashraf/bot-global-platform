import { Routes } from '@angular/router';

import { CatalogCategoryPageComponent } from './pages/catalog-category-page/catalog-category-page.component';
import { CatalogProductDetailPageComponent } from './pages/catalog-product-detail-page/catalog-product-detail-page.component';

export const CATALOG_ROUTES: Routes = [
  {
    path: '',
    component: CatalogCategoryPageComponent
  },
  {
    path: ':slug',
    component: CatalogProductDetailPageComponent
  }
];
