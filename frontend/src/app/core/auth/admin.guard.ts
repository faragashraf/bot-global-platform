import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

export const adminGuard: CanActivateFn = async (_, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  try {
    await auth.restoreSession();

    if (!auth.isAuthenticated()) {
      return router.createUrlTree(
        ['/login'],
        { queryParams: { returnUrl: state.url } }
      );
    }

    if (!auth.isAdministrator()) {
      return router.createUrlTree(['/']);
    }

    return true;
  } catch {
    return router.createUrlTree(
      ['/login'],
      { queryParams: { returnUrl: state.url } }
    );
  }
};
