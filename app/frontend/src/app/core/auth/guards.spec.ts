import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, isObservable } from 'rxjs';
import { admin, organization, session } from '../../../testing/fixtures';
import { authInterceptor } from '../http/auth.interceptor';
import { adminGuard } from './admin.guard';
import { authenticatedGuard } from './auth.guard';
import { AuthService } from './auth.service';

type GuardResult = boolean | UrlTree | Observable<boolean | UrlTree>;

describe('route guards', () => {
  let controller: HttpTestingController;
  let auth: AuthService;
  let router: Router;
  const state = (url: string) => ({ url }) as RouterStateSnapshot;
  const run = (guard: typeof adminGuard, url: string) =>
    TestBed.runInInjectionContext(() => guard({} as ActivatedRouteSnapshot, state(url)) as GuardResult);
  const resolve = async (result: GuardResult) => (isObservable(result) ? firstValueFrom(result) : result);

  async function signIn(memberSession = session()) {
    const login = firstValueFrom(auth.login('x@pong.example', 'geheim123'));
    controller.expectOne('/v1/auth/sessions').flush(memberSession);
    await login;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideRouter([])],
    });
    controller = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  afterEach(() => controller.verify());

  it('authenticatedGuard redirects anonymous users to /start with returnUrl', async () => {
    const result = await resolve(run(authenticatedGuard, '/scans/s1/results'));
    expect(router.serializeUrl(result as UrlTree)).toBe('/start?returnUrl=%2Fscans%2Fs1%2Fresults');
  });

  it('authenticatedGuard restores the organization of a returning user', async () => {
    await signIn();
    const pending = resolve(run(authenticatedGuard, '/scans'));
    controller.expectOne('/v1/organizations').flush({ items: [organization] });
    expect(await pending).toBe(true);
  });

  it('authenticatedGuard sends users without organization to /start', async () => {
    await signIn();
    const pending = resolve(run(authenticatedGuard, '/scans'));
    controller.expectOne('/v1/organizations').flush({ items: [] });
    expect(router.serializeUrl((await pending) as UrlTree)).toBe('/start?returnUrl=%2Fscans');
  });

  it('adminGuard denies non-admin members', async () => {
    await signIn();
    const result = await resolve(run(adminGuard, '/admin'));
    expect(router.serializeUrl(result as UrlTree)).toBe('/scans');
  });

  it('adminGuard allows admins', async () => {
    await signIn(session({ member: admin }));
    expect(await resolve(run(adminGuard, '/admin'))).toBe(true);
  });

  it('adminGuard sends anonymous users to /start', async () => {
    const result = await resolve(run(adminGuard, '/admin'));
    expect(router.serializeUrl(result as UrlTree)).toBe('/start?returnUrl=%2Fadmin');
  });
});
