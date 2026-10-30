import { describe, it, expect, vi, afterEach } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { render, scan, text } from '../../testing/fixtures';
import { ScanResults } from '../core/model/results';
import { radarGeometry } from './radar';
import { ScanResultsComponent, pdfFileName } from './scan-results.component';

const results: ScanResults = {
  scanId: 's1',
  scanName: 'Nulmeting 2026',
  assessmentPeriod: 'Q2 2026',
  completedAt: '2026-05-01T10:00:00Z',
  totalScore: 2.67,
  maturityLevel: { level: 2, label: 'Herhaalbaar' },
  domainScores: [
    { domainId: 'd1', domainName: 'Datastrategie', score: 3.5, maturityLevel: { level: 3, label: 'Gedefinieerd' } },
    { domainId: 'd2', domainName: 'Datakwaliteit', score: 2, maturityLevel: { level: 2, label: 'Herhaalbaar' } },
    { domainId: 'd3', domainName: 'Governance', score: 2.5, maturityLevel: { level: 2, label: 'Herhaalbaar' } },
  ],
  developmentPoints: [
    { id: 'p1', domainId: 'd1', domainName: 'Datastrategie', classification: 'strength', text: 'Heldere ambitie', recommendation: 'Borg de ambitie' },
    { id: 'p2', domainId: 'd2', domainName: 'Datakwaliteit', classification: 'gap', text: 'Geen kwaliteitsnormen', recommendation: 'Stel normen op' },
    { id: 'p3', domainId: 'd3', domainName: 'Governance', classification: 'development-point', text: 'Eigenaarschap onduidelijk', recommendation: null },
  ],
  goals: [{ name: 'Anders', priority: 'high', customText: 'Minder handwerk' }],
  availablePrioritySetRevisions: [1],
  priorities: { setRevision: 1, items: [{ developmentPointId: 'p2', impact: 'high', urgency: 'low', selected: true }] },
};

function setup(route = new BehaviorSubject(convertToParamMap({ scanId: 's1' }))) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [ScanResultsComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { paramMap: route } },
    ],
  });
  const fixture = TestBed.createComponent(ScanResultsComponent);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  return { fixture, http, root: fixture.nativeElement as HTMLElement };
}

function loaded() {
  const context = setup();
  context.http.expectOne('/v1/scans/s1/results').flush(results);
  render(context.fixture);
  return context;
}

describe('ScanResultsComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('renders the total score, level, accessible radar and the table fallback', () => {
    const { fixture, root } = loaded();
    expect(root.querySelector('[data-testid="total-score"]')!.textContent).toContain('2,67');
    expect(text(fixture)).toContain('Niveau 2 · Herhaalbaar');

    const svg = root.querySelector('svg[data-testid="radar"]')!;
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toContain('Datastrategie 3,5');
    expect(svg.getAttribute('aria-describedby')).toBe('domain-score-table');
    expect(svg.querySelectorAll('circle').length).toBe(3);

    const rows = root.querySelectorAll('#domain-score-table tbody tr');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('Datastrategie');
    expect(rows[0].textContent).toContain('3 · Gedefinieerd');
  });

  it('shows accessible score bars instead of a radar for fewer than three domains', () => {
    const { fixture, http, root } = setup();
    http.expectOne('/v1/scans/s1/results').flush({ ...results, domainScores: results.domainScores.slice(0, 2) });
    render(fixture);
    expect(root.querySelector('svg[data-testid="radar"]')).toBeNull();
    const bars = root.querySelector('[data-testid="score-bars"]')!;
    expect(bars.getAttribute('role')).toBe('img');
    expect(bars.getAttribute('aria-describedby')).toBe('domain-score-table');
    expect(bars.textContent).toContain('Datakwaliteit');
  });

  it('groups development points and shows goals separately from development priorities', () => {
    const { fixture } = loaded();
    const content = text(fixture);
    expect(content).toContain('Sterktes');
    expect(content).toContain('Knelpunten');
    expect(content).toContain('Ontwikkelpunten');
    expect(content).toContain('Aanbeveling: Stel normen op');
    expect(content).toContain('Organisatiedoelen en prioriteiten');
    expect(content).toContain('Minder handwerk');
    expect(content).toContain('Opgeslagen versie 1');
  });

  it('saves development priorities and shows the new set revision', () => {
    const { fixture, http, root } = loaded();
    const rows = root.querySelectorAll('[data-testid="priority-row"]');
    expect(rows.length).toBe(2); // sterktes worden niet geprioriteerd
    const urgency = root.querySelector<HTMLSelectElement>('#urgency-p3')!;
    urgency.value = 'high';
    urgency.dispatchEvent(new Event('change'));
    root.querySelector<HTMLInputElement>('#selected-p3')!.click();
    render(fixture);

    Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('Prioriteiten opslaan'))!.click();
    const put = http.expectOne('/v1/scans/s1/development-priorities');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({
      items: [
        { developmentPointId: 'p2', impact: 'high', urgency: 'low', selected: true },
        { developmentPointId: 'p3', impact: 'medium', urgency: 'high', selected: true },
      ],
    });
    put.flush({ setRevision: 2, items: put.request.body.items });
    render(fixture);
    expect(text(fixture)).toContain('Ontwikkelprioriteiten opgeslagen (versie 2).');
    expect(fixture.componentInstance.savingPriorities()).toBe(false);
    expect(fixture.componentInstance.actionItems().map(item => item.developmentPointId)).toEqual(['p3', 'p2']); // gesorteerd op impact + urgentie
  });

  it('treats setRevision 0 as not yet saved', () => {
    const { fixture, http } = setup();
    http.expectOne('/v1/scans/s1/results').flush({ ...results, availablePrioritySetRevisions: [],
  priorities: { setRevision: 0, items: [] } });
    render(fixture);
    expect(text(fixture)).toContain('Nog niet opgeslagen');
    expect(fixture.componentInstance.priorityFor(results.developmentPoints[1]).selected).toBe(false);
  });

  it('shows an actionable error when saving priorities fails', () => {
    const { fixture, http, root } = loaded();
    Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('Prioriteiten opslaan'))!.click();
    http.expectOne('/v1/scans/s1/development-priorities').flush({ title: 'Onbekend ontwikkelpunt', status: 422 }, { status: 422, statusText: 'x' });
    render(fixture);
    expect(text(fixture)).toContain('Onbekend ontwikkelpunt');
    expect(fixture.componentInstance.savingPriorities()).toBe(false);
  });

  it('downloads the PDF export as scan-<name>.pdf', () => {
    const { fixture, http, root } = loaded();
    const createObjectURL = vi.fn(() => 'blob:x');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('PDF exporteren'))!.click();
    const request = http.expectOne('/v1/scans/s1/export?prioritySetRevision=1');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['%PDF'], { type: 'application/pdf' }));
    render(fixture);
    expect(createObjectURL).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(text(fixture)).toContain('scan-nulmeting-2026.pdf');
    expect(pdfFileName('Één scan / Q2')).toBe('scan-een-scan-q2.pdf');
  });

  it('creates a new revision and opens its assessment', () => {
    const { http, root } = loaded();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    root.querySelector<HTMLInputElement>('#revision-new')!.click();
    Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('Nieuwe revisie'))!.click();
    const post = http.expectOne('/v1/scans/s1/revisions');
    expect(post.request.body).toEqual({ mode: 'new-revision' });
    post.flush(scan({ id: 's2', revision: 2, revisionOf: 's1' }));
    expect(navigate).toHaveBeenCalledWith(['/scans', 's2', 'assessment']);
  });

  it('reuses the revision idempotency key on retry after a network error', () => {
    const { http, root } = loaded();
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const button = () => Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('Nieuwe revisie'))!;
    button().click();
    const first = http.expectOne('/v1/scans/s1/revisions');
    const key = first.request.headers.get('X-Idempotency-Key');
    expect(key).toBeTruthy();
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    button().click();
    const retry = http.expectOne('/v1/scans/s1/revisions');
    expect(retry.request.headers.get('X-Idempotency-Key')).toBe(key);
    retry.flush(scan({ id: 's2' }));
  });

  it('explains that results are not yet available for unfinished scans', () => {
    const { fixture, http, root } = setup();
    http.expectOne('/v1/scans/s1/results').flush({ title: 'Niet afgerond', status: 409 }, { status: 409, statusText: 'Conflict' });
    render(fixture);
    expect(text(fixture)).toContain('Nog geen definitieve resultaten');
    expect(root.querySelector('a[href="/scans/s1/assessment"]')).toBeTruthy();
  });
  it('selects and exports a historical priority revision', () => {
    const { fixture, http, root } = setup();
    http.expectOne('/v1/scans/s1/results').flush({ ...results, availablePrioritySetRevisions: [1, 2], priorities: { setRevision: 2, items: [] } });
    render(fixture);
    const select = root.querySelector<HTMLSelectElement>('#priority-revision')!;
    expect(select).toBeTruthy();
    expect(select.value).toBe('2');
    select.value = '1';
    select.dispatchEvent(new Event('change'));
    http.expectOne('/v1/scans/s1/results?prioritySetRevision=1').flush({ ...results, availablePrioritySetRevisions: [1, 2] });
    render(fixture);
    expect(fixture.componentInstance.overview()?.setRevision).toBe(1);
    fixture.componentInstance.exportPdf();
    http.expectOne('/v1/scans/s1/export?prioritySetRevision=1').flush(new Blob(['pdf']));
  });

  it('clears previous results when another scan fails to load', () => {
    const route = new BehaviorSubject(convertToParamMap({ scanId: 's1' }));
    const { fixture, http } = setup(route);
    http.expectOne('/v1/scans/s1/results').flush(results);
    render(fixture);
    route.next(convertToParamMap({ scanId: 's2' }));
    expect(fixture.componentInstance.results()).toBeNull();
    http.expectOne('/v1/scans/s2/results').flush({ title: 'Niet gevonden' }, { status: 404, statusText: 'Not found' });
    render(fixture);
    expect(text(fixture)).toContain('Niet gevonden');
    expect(text(fixture)).not.toContain('Nulmeting 2026');
  });
});

describe('radarGeometry', () => {
  it('places scores proportionally on each axis', () => {
    const geometry = radarGeometry(
      [
        { domainId: 'a', domainName: 'A', score: 5, maturityLevel: { level: 5, label: '' } },
        { domainId: 'b', domainName: 'B', score: 0, maturityLevel: { level: 0, label: '' } },
      ],
      200,
      50,
    );
    expect(geometry.axes[0]).toMatchObject({ pointX: 100, pointY: 50, anchor: 'middle' });
    expect(geometry.axes[1]).toMatchObject({ pointX: 100, pointY: 100 });
    expect(geometry.rings.length).toBe(5);
  });
});
