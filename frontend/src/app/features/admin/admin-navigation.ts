export interface AdminSection {
  readonly path: string;
  readonly route: string;
  readonly labelKey: string;
  readonly icon: string;
  readonly exact: boolean;
}

export const ADMIN_SECTION_DATA_KEY = 'adminSection';

export const ADMIN_SECTIONS = {
  dashboard: {
    path: '',
    route: '/admin',
    labelKey: 'auth.management.nav.dashboard',
    icon: 'pi pi-home',
    exact: true
  },
  catalog: {
    path: 'catalog',
    route: '/admin/catalog',
    labelKey: 'auth.management.nav.catalog',
    icon: 'pi pi-th-large',
    exact: false
  }
} as const satisfies Record<string, AdminSection>;

export const ADMIN_NAVIGATION: readonly AdminSection[] = [
  ADMIN_SECTIONS.dashboard,
  ADMIN_SECTIONS.catalog
];
