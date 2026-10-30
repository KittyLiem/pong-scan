import { describe, it, expect, vi, afterEach } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { organization, render, scan, text } from '../../testing/fixtures';
import { OrganizationService } from '../core/organization/organization.service';
import { scanActionLink } from '../shared/labels';
import { ScanOverviewComponent } from './scan-overview.component';

const scans = [
  scan({ id: 'c1', name: 'Concept scan', status: 'concept', progress: 0 }),
  scan({ id: 'p1', name: 'Lopende scan', status: 'in-progress', progress: 33.4 }),
  scan({ id: 'k1', name: 'Inhoud klaar', status: 'content-complete', progress: 100 }),
  scan({ id: 'd1', name: 'Afgeronde scan', status: 'completed', progress: 100, revision: 2, completedAt: '2026-05-01T10:00:00Z' }),
];

function setup() {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [ScanOverviewComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: OrganizationService, useValue: { current: signal(organization) } },
    ],
  });
  const fixture = TestBed.createComponent(ScanOverviewComponent);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  return { fixture, http };
}

describe('ScanOverviewComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('links completed scans to results and all other statuses to the assessment', () => {
    const { fixture, http } = setup();
    http.expectOne(req => req.url === '/v1/organizations/o1/scans' && req.params.get('pageSize') === '20').flush({ items: scans, nextCursor: null });
    render(fixture);
    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('[data-testid="scan-row"] a')).map(a => a.getAttribute('href'));
    expect(links).toEqual(['/scans/c1/assessment', '/scans/p1/assessment', '/scans/k1/assessment', '/scans/d1/results']);
    const content = text(fixture);
    expect(content).toContain('Concept');
    expect(content).toContain('In uitvoering');
    expect(content).toContain('Inhoud compleet');
    expect(content).toContain('Afgerond');
    expect(content).toContain('33%');
    expect(content).toContain('Revisie 2');
    expect(content).not.toContain('current');
  });

  it('shows the empty state and loads more pages with the cursor', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({ items: [], nextCursor: null });
    render(fixture);
    expect(text(fixture)).toContain('Nog geen scans binnen de organisatie');

    fixture.componentInstance.load();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({ items: [scans[0]], nextCursor: 'abc' });
    render(fixture);
    const more = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(b => b.textContent?.includes('Meer laden'))!;
    more.click();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20&cursor=abc').flush({ items: [scans[3]], nextCursor: null });
    render(fixture);
    expect(fixture.componentInstance.scans().map(s => s.id)).toEqual(['c1', 'd1']);
    expect(text(fixture)).not.toContain('Meer laden');
  });

  it('shows a retry action when loading fails', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({}, { status: 500, statusText: 'Error' });
    render(fixture);
    expect(text(fixture)).toContain('Er ging iets mis op de server');
    expect(text(fixture)).toContain('Opnieuw proberen');
  });

  it('validates the create-scan form field by field', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({ items: scans, nextCursor: null });
    fixture.componentInstance.toggleCreate();
    render(fixture);
    const root = fixture.nativeElement as HTMLElement;
    expect(text(fixture)).toContain('bijv. 2026, Q2 2026 of H1 2026');

    const period = root.querySelector<HTMLInputElement>('#scan-period')!;
    period.value = 'Q5 2026';
    period.dispatchEvent(new Event('input'));
    root.querySelector<HTMLFormElement>('#nieuwe-scan form')!.dispatchEvent(new Event('submit'));
    render(fixture);
    expect(text(fixture)).toContain('Dit veld is verplicht.');
    expect(text(fixture)).toContain('Gebruik een jaar, kwartaal of halfjaar');
    expect(root.querySelector('#scan-name')!.getAttribute('aria-invalid')).toBe('true');

    const name = root.querySelector<HTMLInputElement>('#scan-name')!;
    name.value = 'x'.repeat(121);
    name.dispatchEvent(new Event('input'));
    render(fixture);
    expect(text(fixture)).toContain('Gebruik maximaal 120 tekens.');
    http.expectNone('/v1/organizations/o1/scans');
  });

  it('creates a scan and navigates to its assessment', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({ items: [], nextCursor: null });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.componentInstance.createForm.setValue({ name: ' Nulmeting ', assessmentPeriod: 'H1 2026' });
    fixture.componentInstance.createScan();
    const request = http.expectOne('/v1/organizations/o1/scans');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ name: 'Nulmeting', assessmentPeriod: 'H1 2026' });
    request.flush(scan({ id: 'new1' }));
    expect(navigate).toHaveBeenCalledWith(['/scans', 'new1', 'assessment']);
  });

  it('reuses the idempotency key when retrying the same scan after a network error', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({ items: [], nextCursor: null });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const component = fixture.componentInstance;
    component.toggleCreate();
    component.createForm.setValue({ name: 'Nulmeting', assessmentPeriod: '2026' });

    component.createScan();
    const first = http.expectOne('/v1/organizations/o1/scans');
    const firstKey = first.request.headers.get('X-Idempotency-Key');
    expect(firstKey).toBeTruthy();
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });

    component.createScan();
    const retry = http.expectOne('/v1/organizations/o1/scans');
    expect(retry.request.headers.get('X-Idempotency-Key')).toBe(firstKey);
    retry.flush(scan({ id: 'new1' }));

    // na succes: een nieuwe (identieke) inzending krijgt een nieuwe sleutel
    component.createScan();
    const next = http.expectOne('/v1/organizations/o1/scans');
    expect(next.request.headers.get('X-Idempotency-Key')).not.toBe(firstKey);
    next.flush(scan({ id: 'new2' }));
  });

  it('uses a new idempotency key when the form values change between attempts', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({ items: [], nextCursor: null });
    const component = fixture.componentInstance;
    component.createForm.setValue({ name: 'Nulmeting', assessmentPeriod: '2026' });
    component.createScan();
    const first = http.expectOne('/v1/organizations/o1/scans');
    const firstKey = first.request.headers.get('X-Idempotency-Key');
    first.flush({ title: 'Fout', status: 500 }, { status: 500, statusText: 'Error' });

    component.createForm.setValue({ name: 'Nulmeting aangepast', assessmentPeriod: '2026' });
    component.createScan();
    const second = http.expectOne('/v1/organizations/o1/scans');
    expect(second.request.headers.get('X-Idempotency-Key')).not.toBe(firstKey);
    second.flush({ title: 'Fout', status: 500 }, { status: 500, statusText: 'Error' });
  });

  it('maps server field errors to the form', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/organizations/o1/scans?pageSize=20').flush({ items: [], nextCursor: null });
    fixture.componentInstance.toggleCreate();
    render(fixture);
    fixture.componentInstance.createForm.setValue({ name: 'Scan', assessmentPeriod: '2026' });
    fixture.componentInstance.createScan();
    http.expectOne('/v1/organizations/o1/scans').flush(
      { title: 'Ongeldige scan', status: 422, errors: [{ field: 'assessmentPeriod', message: 'Periode ligt in de toekomst' }] },
      { status: 422, statusText: 'Unprocessable' },
    );
    render(fixture);
    expect(text(fixture)).toContain('Periode ligt in de toekomst');
    expect(fixture.componentInstance.creating()).toBe(false);
  });
});

describe('scanActionLink', () => {
  it('never sends completed scans to the assessment', () => {
    expect(scanActionLink({ id: 'x', status: 'completed' })).toEqual(['/scans', 'x', 'results']);
    expect(scanActionLink({ id: 'x', status: 'content-complete' })).toEqual(['/scans', 'x', 'assessment']);
    expect(scanActionLink({ id: 'x', status: 'concept' })).toEqual(['/scans', 'x', 'assessment']);
  });
});
