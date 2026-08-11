import { Routes } from '@angular/router';

import { adminGuard } from '../../core/auth/admin.guard';
import {
  ADMIN_SECTION_DATA_KEY,
  ADMIN_SECTIONS
} from './admin-navigation';
import { AdminShellComponent } from './admin-shell/admin-shell.component';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: AdminShellComponent,
    canActivate: [adminGuard],
    children: [
      {
        path: ADMIN_SECTIONS.dashboard.path,
        data: {
          [ADMIN_SECTION_DATA_KEY]: ADMIN_SECTIONS.dashboard
        },
        loadComponent: () =>
          import('./admin-home/admin-home.component')
            .then((m) => m.AdminHomeComponent)
      },
      {
        path: ADMIN_SECTIONS.catalog.path,
        data: {
          [ADMIN_SECTION_DATA_KEY]: ADMIN_SECTIONS.catalog
        },
        loadComponent: () =>
          import('./catalog-management/pages/catalog-management-page/catalog-management-page.component')
            .then((m) => m.CatalogManagementPageComponent)
      }
    ]
  }
];
