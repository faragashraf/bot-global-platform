import { Routes } from '@angular/router';

import { adminGuard } from '../../core/auth/admin.guard';
import { AdminShellComponent } from './admin-shell/admin-shell.component';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: AdminShellComponent,
    canActivate: [adminGuard],
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./admin-home/admin-home.component')
            .then((m) => m.AdminHomeComponent)
      }
    ]
  }
];
