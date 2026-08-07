import { Routes } from '@angular/router';
import type { PublicCatalogCategory } from './features/catalog/models/catalog.model';

interface CatalogRouteData {
  readonly catalogCategory: PublicCatalogCategory;
}

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./layout/public-layout/public-layout.component')
        .then((m) => m.PublicLayoutComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./features/home/home-page.component')
            .then((m) => m.HomePageComponent)
      },
      {
        path: 'portfolio',
        loadComponent: () =>
          import('./features/portfolio/pages/portfolio-page/portfolio-page.component')
            .then((m) => m.PortfolioPageComponent)
      },
      {
        path: 'design-system',
        loadComponent: () =>
          import('./features/design-system/design-system-page.component')
            .then((m) => m.DesignSystemPageComponent)
      },
      {
        path: 'apps',
        loadChildren: () =>
          import('./features/catalog/catalog.routes')
            .then((m) => m.CATALOG_ROUTES),
        data: {
          catalogCategory: 'app'
        } satisfies CatalogRouteData
      },
      {
        path: 'games',
        loadChildren: () =>
          import('./features/catalog/catalog.routes')
            .then((m) => m.CATALOG_ROUTES),
        data: {
          catalogCategory: 'game'
        } satisfies CatalogRouteData
      },
      {
        path: 'programs',
        loadChildren: () =>
          import('./features/catalog/catalog.routes')
            .then((m) => m.CATALOG_ROUTES),
        data: {
          catalogCategory: 'program'
        } satisfies CatalogRouteData
      }
    ]
  },
  {
    path: '**',
    redirectTo: ''
  }
];
