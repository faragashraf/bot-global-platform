import { ADMIN_NAVIGATION, ADMIN_SECTIONS } from '../admin-navigation';
import { ADMIN_ROUTES } from '../admin.routes';

describe('notification center routing', () => {
  it('adds the protected lazy admin route and navigation item', () => {
    expect(ADMIN_SECTIONS.notifications.route).toBe('/admin/notifications');
    expect(ADMIN_SECTIONS.notifications.labelKey).toBe(
      'auth.management.nav.notifications'
    );
    expect(ADMIN_NAVIGATION).toContain(ADMIN_SECTIONS.notifications);

    const child = ADMIN_ROUTES[0].children?.find(
      route => route.path === 'notifications'
    );
    expect(ADMIN_ROUTES[0].canActivate).toBeTruthy();
    expect(child?.loadComponent).toBeTypeOf('function');
  });
});
