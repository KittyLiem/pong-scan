import { describe, it, expect, vi, afterEach } from 'vitest';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { admin, organization, render, session, text } from '../../testing/fixtures';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { authInterceptor } from '../core/http/auth.interceptor';
import { OrganizationService } from '../core/organization/organization.service';
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
    expect(navigate).toHaveBeenCalledWith('/scans/s1/results', { replaceUrl: true });
  });

  it('sends an admin without organization straight to the requested admin route', () => {
    const { fixture, navigate, http } = setup({ returnUrl: '/admin' });
    fill(fixture, 'admin@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/sessions').flush(session({ desiredRoute: '/admin', member: admin }));
    http.expectOne('/v1/organizations').flush({ items: [] });
    expect(navigate).toHaveBeenCalledWith('/admin', { replaceUrl: true });
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
    expect(navigate).toHaveBeenCalledWith('/scans', { replaceUrl: true });
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
    expect(navigate).toHaveBeenCalledWith('/scans', { replaceUrl: true });
  });

  it('sends a quickscan account straight to its quickscan after creating the organization', () => {
    const { fixture, navigate, http } = setup({ entry: 'quickscan', returnUrl: '/quickscan/start' });
    (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#accepted-terms')!.click();
    fill(fixture, 'nieuw@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/registrations').flush({ ...session().member, plan: 'quickscan' });
    http.expectOne('/v1/auth/sessions').flush(session({ desiredRoute: '/quickscan/start', member: { ...session().member, plan: 'quickscan' } }));
    http.expectOne('/v1/organizations').flush({ items: [] });
    render(fixture);

    const root = fixture.nativeElement as HTMLElement;
    const name = root.querySelector<HTMLInputElement>('#org-name')!;
    name.value = 'Klant BV';
    name.dispatchEvent(new Event('input'));
    root.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/v1/organizations').flush(organization);
    render(fixture);

    expect(navigate).toHaveBeenCalledWith('/quickscan/start', { replaceUrl: true });
    expect(text(fixture)).toContain('Je quickscan wordt geopend…');
    expect(text(fixture)).not.toContain('Naar je quickscan');
    expect(root.querySelector('a.btn, button.btn')).toBeNull();
  });

  it('defaults a quickscan account without a requested route to its quickscan', () => {
    const { fixture, navigate, http } = setup();
    fill(fixture, 'quick@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/sessions').flush(session({ member: { ...session().member, plan: 'quickscan' } }));
    http.expectOne('/v1/organizations').flush({ items: [organization] });
    expect(navigate).toHaveBeenCalledWith('/quickscan/start', { replaceUrl: true });
  });

  it('continues immediately when /start is opened while signed in with an organization', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AccountAccessComponent],
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
      ],
    });
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    void firstValueFrom(auth.login('demo@pong.example', 'wachtwoord123'));
    http.expectOne('/v1/auth/sessions').flush(session());
    void firstValueFrom(TestBed.inject(OrganizationService).load());
    http.expectOne('/v1/organizations').flush({ items: [organization] });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    const fixture = TestBed.createComponent(AccountAccessComponent);
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith('/scans', { replaceUrl: true });
    expect(text(fixture)).toContain('Je scanoverzicht wordt geopend…');
    expect(text(fixture)).not.toContain('Naar het scanoverzicht');
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

  it('registers a quickscan account when coming from the quickscan landing page', () => {
    const { fixture, navigate, http } = setup({ entry: 'quickscan', returnUrl: '/quickscan/start' });
    expect(text(fixture)).toContain('Start de quickscan');
    expect(text(fixture)).not.toContain('Meld je (opnieuw) aan');
    (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#accepted-terms')!.click();
    fill(fixture, 'nieuw@pong.example', 'wachtwoord123');

    const registration = http.expectOne('/v1/auth/registrations');
    expect(registration.request.body).toEqual({ accountIdentifier: 'nieuw@pong.example', credential: 'wachtwoord123', entry: 'quickscan', acceptedTerms: true, marketingConsent: false });
    registration.flush({ ...session().member, plan: 'quickscan' });
    http.expectOne('/v1/auth/sessions').flush(session({ desiredRoute: '/quickscan/start' }));
    http.expectOne('/v1/organizations').flush({ items: [organization] });
    expect(navigate).toHaveBeenCalledWith('/quickscan/start', { replaceUrl: true });
  });

  it('requires agreeing to the terms before a quickscan account is created', () => {
    const { fixture, http } = setup({ entry: 'quickscan', returnUrl: '/quickscan/start' });
    const root = fixture.nativeElement as HTMLElement;
    const link = root.querySelector<HTMLAnchorElement>('label[for="accepted-terms"] a')!;
    expect(root.querySelector('label[for="accepted-terms"]')!.textContent!.replace(/\s+/g, ' ').trim())
      .toBe('Ik ga akkoord met de voorwaarden en het privacybeleid.');
    expect(link.textContent).toBe('voorwaarden en het privacybeleid');
    expect(link.getAttribute('href')).toBe('https://www.pong.nl');
    expect(link.getAttribute('target')).toBe('_blank');

    fill(fixture, 'nieuw@pong.example', 'wachtwoord123');
    http.expectNone('/v1/auth/registrations');
    expect(text(fixture)).toContain('Ga akkoord met de voorwaarden en het privacybeleid om de quickscan te starten.');
  });

  it('shows no terms checkbox when signing in or creating a normal account', () => {
    const quickscanLogin = setup({ entry: 'quickscan' });
    quickscanLogin.fixture.componentInstance.setMode('login');
    render(quickscanLogin.fixture);
    expect((quickscanLogin.fixture.nativeElement as HTMLElement).querySelector('#accepted-terms')).toBeNull();

    const { fixture, http } = setup();
    fixture.componentInstance.setMode('register');
    render(fixture);
    expect((fixture.nativeElement as HTMLElement).querySelector('#accepted-terms')).toBeNull();
    fill(fixture, 'gewoon@pong.example', 'wachtwoord123');
    expect(http.expectOne('/v1/auth/registrations').request.body).toEqual({ accountIdentifier: 'gewoon@pong.example', credential: 'wachtwoord123' });
    expect((fixture.nativeElement as HTMLElement).querySelector('#marketing-consent')).toBeNull();
  });

  it('offers an optional marketing consent, unticked by default, and sends the choice', () => {
    const { fixture, http } = setup({ entry: 'quickscan', returnUrl: '/quickscan/start' });
    const root = fixture.nativeElement as HTMLElement;
    const consent = root.querySelector<HTMLInputElement>('#marketing-consent')!;
    expect(consent.checked).toBe(false);
    expect(consent.required).toBe(false);
    expect(root.querySelector('label[for="marketing-consent"]')!.textContent!.trim()).toBe(
      'Ik geef toestemming aan Pong om mij per e-mail op de hoogte te houden van persoonlijke aanbiedingen, acties en nieuwe producten. Ik kan deze toestemming altijd weer intrekken via de link onderaan de e-mails.',
    );
    root.querySelector<HTMLInputElement>('#accepted-terms')!.click();
    consent.click();
    fill(fixture, 'nieuw@pong.example', 'wachtwoord123');
    expect(http.expectOne('/v1/auth/registrations').request.body).toMatchObject({ entry: 'quickscan', acceptedTerms: true, marketingConsent: true });
  });

  it('keeps the quickscan context after a failed sign-in and offers a quickscan account', () => {
    const { fixture, http } = setup({ entry: 'quickscan', mode: 'login', returnUrl: '/quickscan/start' });
    const root = fixture.nativeElement as HTMLElement;
    expect(text(fixture)).toContain('Start de quickscan');
    expect(fixture.componentInstance.mode()).toBe('login');
    expect(root.querySelector('#accepted-terms')).toBeNull();

    fill(fixture, 'vergeten@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/sessions').flush({ title: 'Niet aangemeld', status: 401 }, { status: 401, statusText: 'Unauthorized' });
    render(fixture);
    expect(text(fixture)).toContain('De combinatie van e-mailadres en wachtwoord is onbekend.');
    const hint = root.querySelector('[data-testid="quickscan-register-hint"]')!;
    expect(hint.textContent).toContain('Nog geen account? Maak een quickscan-account aan.');

    hint.querySelector('button')!.click();
    render(fixture);
    expect(fixture.componentInstance.mode()).toBe('register');
    expect(root.querySelector<HTMLInputElement>('#email')!.value).toBe('vergeten@pong.example');
    expect(root.querySelector('#accepted-terms')).not.toBeNull();
    expect(root.querySelector('#marketing-consent')).not.toBeNull();

    root.querySelector<HTMLInputElement>('#accepted-terms')!.click();
    fill(fixture, 'vergeten@pong.example', 'wachtwoord123');
    expect(http.expectOne('/v1/auth/registrations').request.body).toMatchObject({ entry: 'quickscan', acceptedTerms: true });
  });

  it('shows no quickscan hint after a failed sign-in on the normal start page', () => {
    const { fixture, http } = setup();
    fill(fixture, 'demo@pong.example', 'wachtwoord123');
    http.expectOne('/v1/auth/sessions').flush({ title: 'Niet aangemeld', status: 401 }, { status: 401, statusText: 'Unauthorized' });
    render(fixture);
    expect(text(fixture)).toContain('De combinatie van e-mailadres en wachtwoord is onbekend.');
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="quickscan-register-hint"]')).toBeNull();
  });
});
