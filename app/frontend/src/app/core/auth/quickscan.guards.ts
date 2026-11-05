import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Een quickscan-account heeft geen scanoverzicht: het gaat naar zijn quickscan. Na `authenticatedGuard`. */
export const fullAccountGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isQuickscanAccount() ? inject(Router).createUrlTree(['/quickscan/start']) : true;
};

/** Alleen een quickscan-account opent een quickscan; een volledig account of beheerder gaat naar `/scans`. */
export const quickscanAccountGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isQuickscanAccount() ? true : inject(Router).createUrlTree(['/scans']);
};

/** De landingspagina is voor nieuwe bezoekers; wie al is aangemeld gaat meteen door. */
export const quickscanLandingGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  if (!auth.hasValidSession()) return true;
  return inject(Router).createUrlTree([auth.isQuickscanAccount() ? '/quickscan/start' : '/scans']);
};
