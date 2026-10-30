import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Alleen beheerders (`member.role === 'admin'`); anders naar `/scans` (of `/start` zonder sessie). */
export const adminGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.hasValidSession()) return router.createUrlTree(['/start'], { queryParams: { returnUrl: state.url } });
  return auth.isAdmin() ? true : router.createUrlTree(['/scans']);
};
