import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, tap } from 'rxjs';
import { mapProblem } from '../http/problem';
import { Member, Session } from '../model/auth';

/** Een interne route die veilig na aanmelden hersteld kan worden. */
export function safeInternalRoute(route: string | null | undefined): string | null {
  if (!route || route.length > 2048) return null;
  if (!route.startsWith('/') || route.startsWith('//') || route.includes('\\')) return null;
  if (route === '/' || route.startsWith('/start')) return null;
  return route;
}

/**
 * Accounttoegang. Het token staat uitsluitend in geheugen (signal), nooit in localStorage of cookies:
 * na herladen van de pagina meldt de gebruiker zich opnieuw aan.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly accessToken = signal<string | null>(null);
  private readonly expiresAt = signal<number | null>(null);
  readonly member = signal<Member | null>(null);

  readonly token = this.accessToken.asReadonly();
  readonly isAuthenticated = computed(() => this.accessToken() !== null);
  readonly isAdmin = computed(() => this.member()?.role === 'admin');

  /** Token aanwezig en (voor zover bekend) niet verlopen. */
  hasValidSession(now = Date.now()): boolean {
    const expiresAt = this.expiresAt();
    return this.accessToken() !== null && (expiresAt === null || expiresAt > now);
  }

  register(accountIdentifier: string, credential: string): Observable<Member> {
    return this.http
      .post<Member>('/v1/auth/registrations', { accountIdentifier, credential })
      .pipe(mapProblem('Account aanmaken mislukt'));
  }

  login(accountIdentifier: string, credential: string, desiredRoute?: string | null): Observable<Session> {
    const route = safeInternalRoute(desiredRoute);
    const body = route ? { accountIdentifier, credential, desiredRoute: route } : { accountIdentifier, credential };
    return this.http.post<Session>('/v1/auth/sessions', body).pipe(
      tap(session => {
        this.accessToken.set(session.accessToken);
        const expiry = Date.parse(session.expiresAt);
        this.expiresAt.set(Number.isNaN(expiry) ? null : expiry);
        this.member.set(session.member);
      }),
      mapProblem('Aanmelden mislukt'),
    );
  }

  /** Trekt de sessie server-side in en wist daarna altijd de lokale toestand. */
  logout(): Observable<void> {
    if (!this.accessToken()) return of(undefined);
    return this.http.delete<void>('/v1/auth/sessions/current').pipe(
      map(() => undefined),
      catchError(() => of(undefined)),
      finalize(() => this.clear()),
    );
  }

  clear(): void {
    this.accessToken.set(null);
    this.expiresAt.set(null);
    this.member.set(null);
  }
}
