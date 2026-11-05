import { describe, it, expect, afterEach } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { render, text } from '../../../testing/fixtures';
import { QuickscanAccount } from '../../core/model/admin-quickscan';
import { QuickscanFollowupComponent } from './quickscan-followup.component';

const PENDING = '/v1/admin/quickscan-accounts?status=pending&pageSize=20';
const CONTACTED = '/v1/admin/quickscan-accounts?status=contacted&pageSize=20';

function account(overrides: Partial<QuickscanAccount> = {}): QuickscanAccount {
  return {
    memberId: 'm1',
    accountIdentifier: 'klant@example.test',
    registeredAt: '2026-11-01T10:00:00Z',
    organizationName: 'Klant BV',
    quickscan: { scanId: 's1', status: 'completed', progress: 100, createdAt: '2026-11-01T10:05:00Z', completedAt: '2026-11-01T11:00:00Z' },
    marketingConsent: true,
    marketingConsentAt: '2026-11-01T10:00:00Z',
    marketingConsentVersion: '2026-11',
    marketingConsentWithdrawnAt: null,
    contact: null,
    ...overrides,
  };
}

function setup(items: QuickscanAccount[] = [account()], declinedCount = 3) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [QuickscanFollowupComponent],
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  const fixture = TestBed.createComponent(QuickscanFollowupComponent);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  http.expectOne(PENDING).flush({ items, nextCursor: null, declinedCount });
  render(fixture);
  return { fixture, http, root: fixture.nativeElement as HTMLElement };
}

function button(root: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes(label))!;
}

describe('QuickscanFollowupComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('lists accounts to contact with consent details and without an e-mail button', () => {
    const { fixture, root } = setup();
    expect(text(fixture)).toContain('klant@example.test');
    expect(text(fixture)).toContain('Klant BV');
    expect(text(fixture)).toContain('Afgerond');
    expect(text(fixture)).toContain('Toestemming gegeven op');
    expect(root.querySelector('a[href^="mailto:"]')).toBeNull();
    expect(text(fixture)).not.toContain('E-mail opstellen');
  });

  it('shows how many quickscan accounts declined to be contacted', () => {
    const many = setup().root;
    expect(many.querySelector('[data-testid="followup-declined"]')!.textContent!.replace(/\s+/g, ' '))
      .toContain('3 quickscan-accounts willen niet benaderd worden');
    const one = setup([], 1).root;
    expect(one.querySelector('[data-testid="followup-declined"]')!.textContent!.replace(/\s+/g, ' '))
      .toContain('1 quickscan-account wil niet benaderd worden');
  });

  it('asks for confirmation and then records the account as e-mailed once', () => {
    const { fixture, http, root } = setup();
    button(root, 'Markeer als gemaild').click();
    render(fixture);
    expect(text(fixture)).toContain('Heeft u dit account gemaild?');

    button(root, 'Ja, vastleggen').click();
    const request = http.expectOne('/v1/admin/quickscan-accounts/m1/contacted');
    expect(request.request.method).toBe('POST');
    request.flush(account({ contact: { channel: 'manual', status: 'sent', contactedAt: '2026-11-05T09:00:00Z', contactedBy: 'admin@pong.example' } }));
    render(fixture);

    expect(root.querySelectorAll('[data-testid="followup-account"]').length).toBe(0);
    expect(text(fixture)).toContain('klant@example.test is vastgelegd als gemaild.');
  });

  it('cancelling the confirmation sends nothing', () => {
    const { fixture, root } = setup();
    button(root, 'Markeer als gemaild').click();
    render(fixture);
    button(root, 'Annuleren').click();
    render(fixture);
    expect(button(root, 'Markeer als gemaild')).toBeTruthy();
  });

  it('removes the row when another admin already contacted the account', () => {
    const { fixture, http, root } = setup();
    button(root, 'Markeer als gemaild').click();
    render(fixture);
    button(root, 'Ja, vastleggen').click();
    http.expectOne('/v1/admin/quickscan-accounts/m1/contacted')
      .flush({ type: 'about:blank', title: 'Al benaderd', status: 409 }, { status: 409, statusText: 'Conflict' });
    render(fixture);
    expect(root.querySelectorAll('[data-testid="followup-account"]').length).toBe(0);
    expect(text(fixture)).toContain('was al benaderd');
  });

  it('shows contacted accounts without contact actions', () => {
    const { fixture, http, root } = setup([]);
    expect(text(fixture)).toContain('Er zijn geen accounts om te benaderen.');
    button(root, 'Benaderd').click();
    http.expectOne(CONTACTED).flush({
      items: [account({ contact: { channel: 'manual', status: 'sent', contactedAt: '2026-11-05T09:00:00Z', contactedBy: 'admin@pong.example' } })],
      nextCursor: null,
      declinedCount: 3,
    });
    render(fixture);
    expect(text(fixture)).toContain('door admin@pong.example');
    expect(button(root, 'Markeer als gemaild')).toBeUndefined();
    expect(root.querySelector('a[href^="mailto:"]')).toBeNull();
  });
});
