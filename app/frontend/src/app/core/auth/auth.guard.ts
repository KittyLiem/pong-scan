import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { OrganizationService } from '../organization/organization.service';
import { AuthService } from './auth.service';

/**
 * Vereist een geldige sessie én een organisatiecontext. Zonder sessie → `/start?returnUrl=…`;
 * zonder organisatie → `/start` (waar het organisatieformulier staat).
 */
export const authenticatedGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const organizations = inject(OrganizationService);
  const router = inject(Router);
  const toStart = () => router.createUrlTree(['/start'], { queryParams: { returnUrl: state.url } });

  if (!auth.hasValidSession()) {
    auth.clear();
    return toStart();
  }
  if (organizations.current()) return true;
  return organizations.load().pipe(
    map(items => (items.length > 0 ? true : toStart())),
    catchError(() => of(toStart())),
  );
};
