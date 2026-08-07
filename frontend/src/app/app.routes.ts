import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./features/home/home-page.component')
        .then((m) => m.HomePageComponent)
  },
  {
    path: 'design-system',
    loadComponent: () =>
      import('./features/design-system/design-system-page.component')
        .then((m) => m.DesignSystemPageComponent)
  },
  {
    path: '**',
    redirectTo: ''
  }
];
