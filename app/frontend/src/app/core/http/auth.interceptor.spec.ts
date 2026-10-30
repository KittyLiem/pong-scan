import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { session } from '../../../testing/fixtures';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let auth: AuthService;
  let router: Router;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    const login = firstValueFrom(auth.login('demo@pong.example', 'geheim123'));
    controller.expectOne('/v1/auth/sessions').flush(session());
    await login;
  });

  afterEach(() => controller.verify());

  it('adds the bearer token to /v1 requests', () => {
    http.get('/v1/organizations').subscribe();
    const request = controller.expectOne('/v1/organizations');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-123');
    request.flush({ items: [] });
  });

  it('does not send the token to other URLs', () => {
    http.get('https://example.com/v1/organizations').subscribe();
    http.get('/assets/config.json').subscribe();
    const external = controller.expectOne('https://example.com/v1/organizations');
    const asset = controller.expectOne('/assets/config.json');
    expect(external.request.headers.has('Authorization')).toBe(false);
    expect(asset.request.headers.has('Authorization')).toBe(false);
    external.flush({});
    asset.flush({});
  });

  it('adds an idempotency key to POST requests', () => {
    http.post('/v1/organizations', { name: 'Pong' }).subscribe();
    const request = controller.expectOne('/v1/organizations');
    expect(request.request.headers.get('X-Idempotency-Key')).toBeTruthy();
    request.flush({});
  });

  it('does not overwrite an idempotency key set by the caller', () => {
    http.post('/v1/organizations/o1/scans', {}, { headers: { 'X-Idempotency-Key': 'vaste-sleutel' } }).subscribe();
    const request = controller.expectOne('/v1/organizations/o1/scans');
    expect(request.request.headers.get('X-Idempotency-Key')).toBe('vaste-sleutel');
    request.flush({});
  });

  it('on 401 clears the session and navigates to /start with the current url', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(router, 'url', 'get').mockReturnValue('/scans/s1/assessment');
    const result = firstValueFrom(http.get('/v1/scans/s1')).catch((error: unknown) => error);
    controller.expectOne('/v1/scans/s1').flush({ title: 'Niet aangemeld' }, { status: 401, statusText: 'Unauthorized' });
    await result;
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.member()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/start'], { queryParams: { returnUrl: '/scans/s1/assessment' } });
  });

  it('does not log out on a 401 from the login endpoint', async () => {
    const navigate = vi.spyOn(router, 'navigate');
    const result = firstValueFrom(http.post('/v1/auth/sessions', {})).catch((error: unknown) => error);
    controller.expectOne('/v1/auth/sessions').flush({}, { status: 401, statusText: 'Unauthorized' });
    await result;
    expect(auth.isAuthenticated()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });
});
