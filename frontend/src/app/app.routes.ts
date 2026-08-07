import { Routes } from '@angular/router';

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
      }
    ]
  },
  {
    path: '**',
    redirectTo: ''
  }
];
