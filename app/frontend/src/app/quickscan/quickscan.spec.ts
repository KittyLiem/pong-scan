import { describe, it, expect, vi, afterEach } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { member, render, scan, session, text } from '../../testing/fixtures';
import { AuthService } from '../core/auth/auth.service';
import { fullAccountGuard, quickscanAccountGuard, quickscanLandingGuard } from '../core/auth/quickscan.guards';
import { QuickscanLandingComponent } from './quickscan-landing.component';
import { QuickscanStartComponent } from './quickscan-start.component';

const quickscanMember = { ...member, plan: 'quickscan' as const };

function configure(imports: unknown[] = []) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ imports, providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  return { http: TestBed.inject(HttpTestingController), router: TestBed.inject(Router), auth: TestBed.inject(AuthService) };
}

async function signIn(auth: AuthService, http: HttpTestingController, signedIn = member) {
  const login = firstValueFrom(auth.login('x@pong.example', 'geheim123'));
  http.expectOne('/v1/auth/sessions').flush(session({ member: signedIn }));
  await login;
}

function run(guard: typeof fullAccountGuard) {
  return TestBed.runInInjectionContext(() => guard({} as ActivatedRouteSnapshot, { url: '/' } as RouterStateSnapshot));
}

describe('quickscan routing', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('sends a quickscan account from the scan overview to its quickscan, and lets full accounts through', async () => {
    const { http, router, auth } = configure();
    await signIn(auth, http, quickscanMember);
    expect(router.serializeUrl(run(fullAccountGuard) as UrlTree)).toBe('/quickscan/start');
    expect(run(quickscanAccountGuard)).toBe(true);
    auth.member.set(member);
    expect(run(fullAccountGuard)).toBe(true);
    expect(router.serializeUrl(run(quickscanAccountGuard) as UrlTree)).toBe('/scans');
  });

  it('shows the landing page to visitors and forwards signed-in accounts', async () => {
    const { http, router, auth } = configure();
    expect(run(quickscanLandingGuard)).toBe(true);
    await signIn(auth, http);
    expect(router.serializeUrl(run(quickscanLandingGuard) as UrlTree)).toBe('/scans');
    auth.member.set(quickscanMember);
    expect(router.serializeUrl(run(quickscanLandingGuard) as UrlTree)).toBe('/quickscan/start');
  });
});

describe('QuickscanLandingComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function setup() {
    const { http } = configure([QuickscanLandingComponent]);
    const fixture = TestBed.createComponent(QuickscanLandingComponent);
    fixture.detectChanges();
    return { fixture, http, root: fixture.nativeElement as HTMLElement };
  }

  it('lists the quickscan domains in order and links to sign-up with the quickscan entry', () => {
    const { fixture, http, root } = setup();
    http.expectOne('/v1/quickscan/domains').flush({
      domains: [
        { id: 'd2', name: 'Datakwaliteit', description: null, order: 2, questionCount: 1 },
        { id: 'd1', name: 'Datastrategie', description: 'Richting', order: 1, questionCount: 3 },
      ],
    });
    render(fixture);
    const items = Array.from(root.querySelectorAll('[data-testid="quickscan-domains"] li'));
    expect(items.map(li => li.querySelector('p')!.textContent)).toEqual(['Datastrategie', 'Datakwaliteit']);
    expect(items[0].textContent).toContain('3 vragen');
    expect(items[1].textContent).toContain('1 vraag');
    const links = Array.from(root.querySelectorAll<HTMLAnchorElement>('a')).map(a => a.getAttribute('href'));
    expect(links).toContain('/start?entry=quickscan&returnUrl=%2Fquickscan%2Fstart');
    expect(links).toContain('/start?returnUrl=%2Fquickscan%2Fstart');
  });

  it('says the quickscan is not available on an installation without it', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/quickscan/domains').flush({ title: 'Niet gevonden', status: 404 }, { status: 404, statusText: 'Not Found' });
    render(fixture);
    expect(text(fixture)).toContain('De quickscan is hier niet beschikbaar.');
  });
});

describe('QuickscanStartComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function setup() {
    const { http, router } = configure([QuickscanStartComponent]);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(QuickscanStartComponent);
    fixture.detectChanges();
    return { fixture, http, navigate };
  }

  it('opens an open quickscan in the assessment', () => {
    const { http, navigate } = setup();
    const request = http.expectOne('/v1/quickscan');
    expect(request.request.method).toBe('PUT');
    request.flush(scan({ id: 'q1', kind: 'quick' }));
    expect(navigate).toHaveBeenCalledWith(['/scans', 'q1', 'assessment'], { replaceUrl: true });
  });

  it('opens a completed quickscan in the results', () => {
    const { http, navigate } = setup();
    http.expectOne('/v1/quickscan').flush(scan({ id: 'q1', kind: 'quick', status: 'completed' }));
    expect(navigate).toHaveBeenCalledWith(['/scans', 'q1', 'results'], { replaceUrl: true });
  });

  it('asks for the organization first when there is none', () => {
    const { http, navigate } = setup();
    http.expectOne('/v1/quickscan').flush({ title: 'Organisatie ontbreekt', status: 409 }, { status: 409, statusText: 'Conflict' });
    expect(navigate).toHaveBeenCalledWith(['/start'], { queryParams: { returnUrl: '/quickscan/start' }, replaceUrl: true });
  });

  it('explains when the quickscan cannot start yet and retries', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/quickscan').flush({ title: 'Quickscan niet beschikbaar', status: 422 }, { status: 422, statusText: 'x' });
    render(fixture);
    expect(text(fixture)).toContain('De quickscan is op dit moment niet beschikbaar.');
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(b => b.textContent?.includes('Opnieuw'))!.click();
    http.expectOne('/v1/quickscan').flush(scan({ id: 'q1', kind: 'quick' }));
  });
});
