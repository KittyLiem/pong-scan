import { describe, it, expect, vi, afterEach } from 'vitest';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { admin, organization, render, session, text } from '../../testing/fixtures';
import { authInterceptor } from '../core/http/auth.interceptor';
import { AccountAccessComponent } from './account-access.component';

function setup(queryParams: Record<string, string> = {}) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [AccountAccessComponent],
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } } },
    ],
  });
  const fixture = TestBed.createComponent(AccountAccessComponent);
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  fixture.detectChanges();
  return { fixture, navigate, http: TestBed.inject(HttpTestingController) };
}

function fill(fixture: ReturnType<typeof setup>['fixture'], email: string, password: string) {
  const root = fixture.nativeElement as HTMLElement;
  const emailInput = root.querySelector<HTMLInputElement>('#email')!;
  const passwordInput = root.querySelector<HTMLInputElement>('#password')!;
  emailInput.value = email;
  emailInput.dispatchEvent(new Event('input'));
  passwordInput.value = password;
  passwordInput.dispatchEvent(new Event('input'));
  root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
  render(fixture);
}

describe('AccountAccessComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('logs in, restores the desired route from the session and loads organizations', () => {
    const { fixture, navigate, http } = setup({ returnUrl: '/scans/s1/results' });
    expect(text(fixture)).toContain('Meld je (opnieuw) aan');
    fill(fixture, 'demo@pong.example', 'wachtwoord123');

    const login = http.expectOne('/v1/auth/sessions');
    expect(login.request.body).toEqual({ accountIdentifier: 'demo@pong.example', credential: 'wachtwoord123', desiredRoute: '/scans/s1/results' });
    login.flush(session({ desiredRoute: '/scans/s1/results' }));

    const organizations = http.expectOne('/v1/organizations');
    expect(organizations.request.headers.get('Authorization')).toBe('Bearer token-123');
    organizations.flush({ items: [organization] });
    expect(navigate).toHaveBeenCalledWith('/scans/s1/results');
  });

  it('sends an admin without organization straight to the requested admin route', () => {
    const { fixture, navigate, http } = setup({ returnUrl: '/admin' });
    fill(fixture, 'admin@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/sessions').flush(session({ desiredRoute: '/admin', member: admin }));
    http.expectOne('/v1/organizations').flush({ items: [] });
    expect(navigate).toHaveBeenCalledWith('/admin');
  });

  it('keeps a member without organization on the organization form', () => {
    const { fixture, navigate, http } = setup({ returnUrl: '/admin' });
    fill(fixture, 'demo@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/sessions').flush(session({ desiredRoute: '/admin' }));
    http.expectOne('/v1/organizations').flush({ items: [] });
    expect(navigate).not.toHaveBeenCalled();
  });

  it('goes to /scans when no route was requested', () => {
    const { fixture, navigate, http } = setup();
    fill(fixture, 'demo@pong.example', 'wachtwoord123');
    const login = http.expectOne('/v1/auth/sessions');
    expect(login.request.body.desiredRoute).toBeUndefined();
    login.flush(session());
    http.expectOne('/v1/organizations').flush({ items: [organization] });
    expect(navigate).toHaveBeenCalledWith('/scans');
  });

  it('shows the organization form when the member has no organization and continues after creating one', () => {
    const { fixture, navigate, http } = setup();
    fill(fixture, 'nieuw@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/sessions').flush(session());
    http.expectOne('/v1/organizations').flush({ items: [] });
    render(fixture);
    expect(text(fixture)).toContain('Organisatiegegevens vastleggen');
    expect(navigate).not.toHaveBeenCalled();

    const root = fixture.nativeElement as HTMLElement;
    const kvk = root.querySelector<HTMLInputElement>('#org-kvk')!;
    kvk.value = '123';
    kvk.dispatchEvent(new Event('input'));
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    render(fixture);
    expect(text(fixture)).toContain('Dit veld is verplicht.');
    expect(text(fixture)).toContain('Een KvK-nummer bestaat uit precies 8 cijfers.');

    const name = root.querySelector<HTMLInputElement>('#org-name')!;
    name.value = 'Pong Consulting';
    name.dispatchEvent(new Event('input'));
    kvk.value = '12345678';
    kvk.dispatchEvent(new Event('input'));
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const create = http.expectOne('/v1/organizations');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({ name: 'Pong Consulting', chamberOfCommerceNumber: '12345678' });
    create.flush(organization);
    expect(navigate).toHaveBeenCalledWith('/scans');
  });

  it('validates e-mail and password in Dutch before calling the API', () => {
    const { fixture } = setup();
    fill(fixture, 'geen-email', 'kort');
    expect(text(fixture)).toContain('Vul een geldig e-mailadres in.');
    expect(text(fixture)).toContain('Gebruik minimaal 8 tekens.');
  });

  it('rejects registration passwords longer than 72 characters in Dutch', () => {
    const { fixture, http } = setup();
    fixture.componentInstance.setMode('register');
    render(fixture);
    fill(fixture, 'nieuw@pong.example', 'A'.repeat(73));
    expect(text(fixture)).toContain('Een wachtwoord mag maximaal 72 bytes zijn.');
    http.expectNone('/v1/auth/registrations');
  });

  it('registers and then signs in', () => {
    const { fixture, http } = setup();
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="group"] button')[1].click();
    render(fixture);
    fill(fixture, 'nieuw@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/registrations').flush({ id: 'm9', accountIdentifier: 'nieuw@pong.example', role: 'member' });
    http.expectOne('/v1/auth/sessions').flush(session());
    http.expectOne('/v1/organizations').flush({ items: [] });
  });

  it('shows a Dutch message for invalid credentials', () => {
    const { fixture, http } = setup();
    fill(fixture, 'demo@pong.example', 'foutwachtwoord');
    http.expectOne('/v1/auth/sessions').flush({ title: 'Unauthorized', status: 401 }, { status: 401, statusText: 'Unauthorized' });
    render(fixture);
    expect(text(fixture)).toContain('De combinatie van e-mailadres en wachtwoord is onbekend.');
  });

  it('toggles password visibility', () => {
    const { fixture } = setup();
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector<HTMLInputElement>('#password')!.type).toBe('password');
    root.querySelector<HTMLButtonElement>('[aria-controls="password"]')!.click();
    render(fixture);
    expect(root.querySelector<HTMLInputElement>('#password')!.type).toBe('text');
  });
});
