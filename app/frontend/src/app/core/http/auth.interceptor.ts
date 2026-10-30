import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { idempotencyKey } from './idempotency';

const API_PREFIX = '/v1/';
const PUBLIC_AUTH_ENDPOINTS = ['/v1/auth/registrations', '/v1/auth/sessions'];

function apiPath(url: string): string | null {
  if (url.startsWith(API_PREFIX)) return url;
  try {
    const parsed = new URL(url, globalThis.location?.origin ?? 'http://localhost');
    const sameOrigin = !globalThis.location || parsed.origin === globalThis.location.origin;
    return sameOrigin && parsed.pathname.startsWith(API_PREFIX) ? parsed.pathname : null;
  } catch {
    return null;
  }
}

/**
 * Eén plek voor API-verkeer:
 * - `Authorization: Bearer` op ieder `/v1/`-verzoek (nooit naar andere hosts);
 * - `X-Idempotency-Key` op POST-verzoeken naar `/v1/` die er nog geen hebben;
 * - 401 buiten de aanmeld-endpoints → sessie wissen en naar `/start?returnUrl=…`.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const path = apiPath(request.url);
  if (path === null) return next(request);

  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token();
  let headers = request.headers;
  if (token && !headers.has('Authorization')) headers = headers.set('Authorization', `Bearer ${token}`);
  if (request.method === 'POST' && !headers.has('X-Idempotency-Key')) headers = headers.set('X-Idempotency-Key', idempotencyKey());

  const isPublicAuth = PUBLIC_AUTH_ENDPOINTS.some(endpoint => path === endpoint);
  const isLogout = path === '/v1/auth/sessions/current';

  return next(request.clone({ headers })).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !isPublicAuth) {
        auth.clear();
        if (!isLogout) {
          const current = router.url;
          const onStart = current.startsWith('/start');
          void router.navigate(['/start'], onStart ? {} : { queryParams: { returnUrl: current } });
        }
      }
      return throwError(() => error);
    }),
  );
};
