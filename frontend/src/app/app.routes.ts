import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'design-system'
  },
  {
    path: 'design-system',
    loadComponent: () =>
      import('./features/design-system/design-system-page.component')
        .then((m) => m.DesignSystemPageComponent)
  },
  {
    path: '**',
    redirectTo: 'design-system'
  }
];
