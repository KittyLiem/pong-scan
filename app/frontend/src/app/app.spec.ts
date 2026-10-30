import { describe, it, expect, vi, afterEach } from 'vitest';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { admin, render, session, text } from '../testing/fixtures';
import { App } from './app';
import { AuthService } from './core/auth/auth.service';
import { authInterceptor } from './core/http/auth.interceptor';

async function setup(signedIn: 'none' | 'member' | 'admin') {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [App],
    providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideRouter([])],
  });
  const http = TestBed.inject(HttpTestingController);
  if (signedIn !== 'none') {
    const login = firstValueFrom(TestBed.inject(AuthService).login('x@pong.example', 'geheim123'));
    http.expectOne('/v1/auth/sessions').flush(session(signedIn === 'admin' ? { member: admin } : {}));
    await login;
  }
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  return { fixture, http, root: fixture.nativeElement as HTMLElement };
}

describe('App shell', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('shows only the sign-in link for anonymous visitors', async () => {
    const { fixture } = await setup('none');
    expect(text(fixture)).toContain('Pong data maturity scan');
    expect(text(fixture)).toContain('Aanmelden');
    expect(text(fixture)).not.toContain('Scanoverzicht');
  });

  it('shows the scan overview, e-mail and sign-out for members without admin link or placeholder routes', async () => {
    const { fixture, root } = await setup('member');
    expect(text(fixture)).toContain('Scanoverzicht');
    expect(text(fixture)).toContain('demo@pong.example');
    expect(text(fixture)).not.toContain('Beheer');
    expect(root.innerHTML).not.toContain('/scans/current');
  });

  it('shows the Beheer link for admins', async () => {
    const { root } = await setup('admin');
    expect(root.querySelector('a[href="/admin"]')).toBeTruthy();
  });

  it('signs out via DELETE /v1/auth/sessions/current and clears the session', async () => {
    const { fixture, http, root } = await setup('member');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('Afmelden'))!.click();
    const request = http.expectOne('/v1/auth/sessions/current');
    expect(request.request.method).toBe('DELETE');
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-123');
    request.flush(null, { status: 204, statusText: 'No Content' });
    render(fixture);
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/start']);
    expect(text(fixture)).not.toContain('demo@pong.example');
  });
});
