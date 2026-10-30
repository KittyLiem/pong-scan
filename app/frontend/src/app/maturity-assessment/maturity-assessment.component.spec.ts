import { describe, it, expect, vi, afterEach } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, EMPTY, of } from 'rxjs';
import { content, domain, question, render, scan, text } from '../../testing/fixtures';
import { ScanService } from '../core/scans/scan.service';
import { ScanContent } from '../core/model/scan';
import { MaturityAssessmentComponent } from './maturity-assessment.component';

function setup(initial: ScanContent, scanOverrides = {}, route = new BehaviorSubject(convertToParamMap({ scanId: 's1' }))) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [MaturityAssessmentComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { paramMap: route } },
    ],
  });
  const fixture = TestBed.createComponent(MaturityAssessmentComponent);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  http.expectOne('/v1/scans/s1').flush(scan(scanOverrides));
  http.expectOne('/v1/scans/s1/content').flush(initial);
  render(fixture);
  return { fixture, http, root: fixture.nativeElement as HTMLElement };
}

function button(root: HTMLElement, label: string): HTMLButtonElement {
  const found = Array.from(root.querySelectorAll('button')).find(b => b.textContent?.trim().startsWith(label));
  if (!found) throw new Error(`Knop "${label}" niet gevonden`);
  return found;
}

describe('MaturityAssessmentComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('blocks "Doorgaan" without a selection with the exact message', () => {
    const { fixture, http, root } = setup(content([domain('d1'), domain('d2', { order: 2 })]));
    expect(text(fixture)).toContain('Selecteer kennisgebieden');
    expect(text(fixture)).toContain('Beschrijving d1');
    expect(text(fixture)).toContain('1 vraag');
    const doorgaan = button(root, 'Doorgaan');
    expect(doorgaan.getAttribute('aria-disabled')).toBe('true');
    doorgaan.click();
    render(fixture);
    expect(root.querySelector('#domain-error [role="alert"]')!.textContent!.trim()).toBe('Selecteer minimaal één kennisgebied om verder te gaan.');
    http.expectNone(() => true);
  });

  it('selects all domains, saves selection plus goals and moves to the questions', () => {
    const { fixture, http, root } = setup(content([domain('d1'), domain('d2', { order: 2 })]));
    button(root, 'Selecteer alles').click();
    render(fixture);
    expect(root.querySelector('[data-testid="selection-counter"]')!.textContent).toContain('2 van 2 geselecteerd');

    const firstGoal = root.querySelector<HTMLInputElement>('#goal-0')!;
    firstGoal.click();
    render(fixture);
    const priority = root.querySelector<HTMLSelectElement>('#goal-0-priority')!;
    priority.value = 'high';
    priority.dispatchEvent(new Event('change'));

    button(root, 'Doorgaan').click();
    const patch = http.expectOne(req => req.method === 'PATCH');
    expect(patch.request.url).toBe('/v1/scans/s1');
    expect(patch.request.body).toEqual({
      version: 1,
      selectedDomainIds: ['d1', 'd2'],
      goals: [{ name: 'Betere besluitvorming op basis van data', priority: 'high' }],
    });
    patch.flush(scan({ version: 5, selectedDomainIds: ['d1', 'd2'] }));
    http.expectOne('/v1/scans/s1/content').flush(content([domain('d1', { selected: true }), domain('d2', { order: 2, selected: true })]));
    render(fixture);
    expect(fixture.componentInstance.step()).toBe('questions');
    expect(text(fixture)).toContain('Vraag d1-q1');
  });

  it('requires a custom text when the goal "Anders" is selected', () => {
    const { fixture, http, root } = setup(content([domain('d1', { selected: true })]));
    fixture.componentInstance.goTo('domains');
    render(fixture);
    const other = fixture.componentInstance.goals.controls.findIndex(goal => goal.controls.name.value === 'Anders');
    root.querySelector<HTMLInputElement>(`#goal-${other}`)!.click();
    button(root, 'Doorgaan').click();
    render(fixture);
    expect(text(fixture)).toContain('Vul een eigen doeltekst in.');
    http.expectNone(() => true);
  });

  it('renders every question of the active domain with explanation, options and fields', () => {
    const q1 = question('q1', { explanation: 'Extra uitleg', order: 1 });
    const q2 = question('q2', { order: 2 });
    const { fixture, root } = setup(content([domain('d1', { selected: true, questions: [q1, q2] }), domain('d2', { order: 2 })]));
    expect(root.querySelectorAll('[data-question]').length).toBe(2);
    expect(root.querySelector('details summary')!.textContent).toContain('Uitleg bij deze vraag');
    expect(root.querySelectorAll('details').length).toBe(1);
    expect(text(fixture)).toContain('3 — Gedefinieerd');
    expect(root.querySelector('label[for="expl-q1"]')).toBeTruthy();
    expect(root.querySelector('label[for="evid-q1"]')).toBeTruthy();
    // alleen geselecteerde domeinen in de navigatie
    const nav = root.querySelector('nav[aria-label="Kennisgebieden"]')!;
    expect(nav.textContent).toContain('Domein d1');
    expect(nav.textContent).not.toContain('Domein d2');
    expect(nav.textContent).toContain('0/2');
  });

  it('validates the score and saves answers with the answer version, handling 409', () => {
    const existing = question('q1', { answer: { optionId: 'q1-o0', explanation: 'Oud', evidenceReferences: [], version: 3 } });
    const fresh = question('q2', { order: 2 });
    const { fixture, http, root } = setup(content([domain('d1', { selected: true, questions: [existing, fresh] })]), { status: 'in-progress' });

    // q2 zonder score: validatiemelding, geen request
    const [, saveQ2] = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).filter(b => b.textContent?.includes('Antwoord opslaan'));
    saveQ2.click();
    render(fixture);
    expect(text(fixture)).toContain('Selecteer een score voor deze vraag');
    http.expectNone(() => true);

    // nieuw antwoord: versie 0
    root.querySelector<HTMLInputElement>('#opt-q2-q2-o3')!.click();
    const evidence = root.querySelector<HTMLTextAreaElement>('#evid-q2')!;
    evidence.value = 'beleid.pdf\n\n https://intranet/x ';
    evidence.dispatchEvent(new Event('input'));
    saveQ2.click();
    const put2 = http.expectOne('/v1/scans/s1/answers/q2');
    expect(put2.request.method).toBe('PUT');
    expect(put2.request.body).toEqual({ optionId: 'q2-o3', explanation: '', evidenceReferences: ['beleid.pdf', 'https://intranet/x'], version: 0 });
    put2.flush({ questionId: 'q2', optionId: 'q2-o3', explanation: '', evidenceReferences: ['beleid.pdf', 'https://intranet/x'], version: 1, progress: 100, scanStatus: 'in-progress', scanVersion: 2 });
    render(fixture);
    expect(text(fixture)).toContain('Opgeslagen');
    expect(text(fixture)).toContain('2 van 2 vragen beantwoord');

    // bestaand antwoord: meegegeven versie 3, server meldt conflict
    const explanation = root.querySelector<HTMLTextAreaElement>('#expl-q1')!;
    explanation.value = 'Nieuwe toelichting';
    explanation.dispatchEvent(new Event('input'));
    const [saveQ1] = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).filter(b => b.textContent?.includes('Antwoord opslaan'));
    saveQ1.click();
    const put1 = http.expectOne('/v1/scans/s1/answers/q1');
    expect(put1.request.body.version).toBe(3);
    put1.flush({ title: 'Conflict', status: 409 }, { status: 409, statusText: 'Conflict' });
    render(fixture);
    expect(text(fixture)).toContain('Gegevens zijn intussen gewijzigd — herlaad');

    button(root, 'Herladen').click();
    http.expectOne('/v1/scans/s1').flush(scan({ status: 'in-progress' }));
    http.expectOne('/v1/scans/s1/content').flush(content([domain('d1', { selected: true, questions: [existing, fresh] })]));
    render(fixture);
    expect(text(fixture)).not.toContain('Gegevens zijn intussen gewijzigd');
  });

  it('keeps completion disabled until every selected question has a score and explanation', () => {
    const answered = question('q1', { answer: { optionId: 'q1-o3', explanation: '', evidenceReferences: [], version: 1 } });
    const unselectedDomain = domain('d2', { order: 2, questions: [question('x1')] });
    const { fixture, http, root } = setup(content([domain('d1', { selected: true, questions: [answered] }), unselectedDomain]), { status: 'in-progress' });
    fixture.componentInstance.goTo('complete');
    render(fixture);
    const complete = button(root, 'Beoordeling afronden');
    expect(complete.disabled).toBe(true);
    expect(text(fixture)).toContain('ontbreekt: toelichting');
    expect(text(fixture)).not.toContain('Vraag x1');

    fixture.componentInstance.goTo('questions');
    render(fixture);
    const explanation = root.querySelector<HTMLTextAreaElement>('#expl-q1')!;
    explanation.value = 'Onderbouwd met beleid';
    explanation.dispatchEvent(new Event('input'));
    button(root, 'Antwoord opslaan').click();
    http.expectOne('/v1/scans/s1/answers/q1').flush({ questionId: 'q1', optionId: 'q1-o3', explanation: 'Onderbouwd met beleid', evidenceReferences: [], version: 2, progress: 100, scanStatus: 'content-complete', scanVersion: 7 });

    fixture.componentInstance.goTo('complete');
    render(fixture);
    const enabled = button(root, 'Beoordeling afronden');
    expect(enabled.disabled).toBe(false);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const comments = root.querySelector<HTMLTextAreaElement>('#general-comments')!;
    comments.value = 'Goed gesprek';
    comments.dispatchEvent(new Event('input'));
    enabled.click();
    const post = http.expectOne('/v1/scans/s1/completion');
    expect(post.request.body).toEqual({ version: 7, generalComments: 'Goed gesprek' });
    post.flush(scan({ status: 'completed', version: 8 }));
    expect(navigate).toHaveBeenCalledWith(['/scans', 's1', 'results']);
  });

  it('uses the scan version from saved answers for PATCH and shows the conflict banner on 409', () => {
    const fresh = question('q1');
    const { fixture, http, root } = setup(content([domain('d1', { selected: true, questions: [fresh] })]), { version: 3 });
    root.querySelector<HTMLInputElement>('#opt-q1-q1-o3')!.click();
    button(root, 'Antwoord opslaan').click();
    http.expectOne('/v1/scans/s1/answers/q1').flush({ questionId: 'q1', optionId: 'q1-o3', explanation: '', evidenceReferences: [], version: 1, progress: 100, scanStatus: 'in-progress', scanVersion: 4 });
    expect(fixture.componentInstance.scan()!.version).toBe(4);

    fixture.componentInstance.goTo('domains');
    render(fixture);
    button(root, 'Doorgaan').click();
    http.expectNone(req => req.method === 'GET' && req.url === '/v1/scans/s1');
    const patch = http.expectOne(req => req.method === 'PATCH');
    expect(patch.request.body.version).toBe(4);
    patch.flush({ title: 'Conflict', status: 409 }, { status: 409, statusText: 'Conflict' });
    render(fixture);
    expect(text(fixture)).toContain('Gegevens zijn intussen gewijzigd — herlaad');
    button(root, 'Herladen').click();
    http.expectOne('/v1/scans/s1').flush(scan({ version: 5 }));
    http.expectOne('/v1/scans/s1/content').flush(content([domain('d1', { selected: true, questions: [fresh] })]));
    expect(fixture.componentInstance.scan()!.version).toBe(5);
  });

  it('renders completed scans read-only with a link to the results', () => {
    const answered = question('q1', { answer: { optionId: 'q1-o3', explanation: 'Toelichting', evidenceReferences: ['doc.pdf'], version: 1 } });
    const { fixture, root } = setup(content([domain('d1', { selected: true, questions: [answered] })], { editable: false, status: 'completed' }), { status: 'completed' });
    expect(text(fixture)).toContain('3 — Gedefinieerd');
    expect(text(fixture)).toContain('doc.pdf');
    expect(root.querySelector('a[href="/scans/s1/results"]')).toBeTruthy();
    expect(root.querySelectorAll('input, textarea').length).toBe(0);
    expect(text(fixture)).not.toContain('Antwoord opslaan');
  });

  it('shows an empty state when the scan has no domains and a retry on load errors', () => {
    const { fixture } = setup(content([]));
    expect(text(fixture)).toContain('Geen actieve kennisgebieden beschikbaar');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MaturityAssessmentComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ scanId: 's1' })) } }],
    });
    const failing = TestBed.createComponent(MaturityAssessmentComponent);
    const http = TestBed.inject(HttpTestingController);
    failing.detectChanges();
    http.expectOne('/v1/scans/s1/content').flush(content([]));
    http.expectOne('/v1/scans/s1').flush({ title: 'Niet gevonden', status: 404 }, { status: 404, statusText: 'Not found' });
    render(failing);
    expect(text(failing)).toContain('Niet gevonden');
    expect(text(failing)).toContain('Opnieuw proberen');
  });
  it('maps answer field errors including indexed evidence', () => {
    const q = question('q1');
    const { fixture, http } = setup(content([domain('d1', { selected: true, questions: [q] })]));
    const component = fixture.componentInstance;
    component.formFor(q.id).controls.optionId.setValue('q1-o3');
    component.saveQuestion(q);
    http.expectOne('/v1/scans/s1/answers/q1').flush({ title: 'Ongeldig', errors: [
      { field: 'optionId', message: 'Optie ongeldig' },
      { field: 'explanation', message: 'Toelichting ongeldig' },
      { field: 'evidenceReferences[0]', message: 'Bewijs ongeldig' },
    ] }, { status: 400, statusText: 'Bad request' });
    render(fixture);
    expect(text(fixture)).toContain('Optie ongeldig');
    expect(text(fixture)).toContain('Toelichting ongeldig');
    expect(text(fixture)).toContain('Bewijs ongeldig');
    expect(component.saveState(q.id)).toBe('error');
  });

  it('maps selected goal indices to their actual form rows and domain selection errors', () => {
    const { fixture, http } = setup(content([domain('d1', { selected: true })]));
    const component = fixture.componentInstance;
    component.goTo('domains');
    component.goals.controls.forEach(goal => goal.controls.selected.setValue(false));
    component.goals.at(2).controls.selected.setValue(true);
    render(fixture);
    component.continueToQuestions();
    http.expectOne('/v1/scans/s1').flush({ title: 'Ongeldig', errors: [
      { field: 'goals[0].name', message: 'Doelnaam ongeldig' },
      { field: 'selectedDomainIds', message: 'Selectie ongeldig' },
    ] }, { status: 400, statusText: 'Bad request' });
    render(fixture);
    expect(component.goals.at(0).controls.name.errors).toBeNull();
    expect(component.goals.at(2).controls.name.errors?.['server']).toBe('Doelnaam ongeldig');
    expect(text(fixture)).toContain('Doelnaam ongeldig');
    expect(text(fixture)).toContain('Selectie ongeldig');
  });

  it('maps completion errors to general comments', () => {
    const q = question('q1', { answer: { optionId: 'q1-o3', explanation: 'Onderbouwing', evidenceReferences: [], version: 1 } });
    const { fixture, http } = setup(content([domain('d1', { selected: true, questions: [q] })]));
    const component = fixture.componentInstance;
    component.goTo('complete');
    render(fixture);
    component.complete();
    http.expectOne('/v1/scans/s1/completion').flush({ title: 'Ongeldig', errors: [
      { field: 'generalComments', message: 'Opmerking ongeldig' },
    ] }, { status: 400, statusText: 'Bad request' });
    render(fixture);
    expect(text(fixture)).toContain('Opmerking ongeldig');
  });

  it('clears previous scan content when the next scan fails to load', () => {
    const route = new BehaviorSubject(convertToParamMap({ scanId: 's1' }));
    const { fixture, http } = setup(content([domain('d1', { selected: true })]), {}, route);
    route.next(convertToParamMap({ scanId: 's2' }));
    expect(fixture.componentInstance.scan()).toBeNull();
    expect(fixture.componentInstance.content()).toBeNull();
    http.expectOne('/v1/scans/s2/content').flush(content([]));
    http.expectOne('/v1/scans/s2').flush({ title: 'Niet gevonden' }, { status: 404, statusText: 'Not found' });
    render(fixture);
    expect(text(fixture)).toContain('Niet gevonden');
    expect(text(fixture)).not.toContain('Vraag d1-q1');
  });
  it('resets answer saving via finalize even when the request completes without a value', () => {
    const q = question('q1');
    const { fixture } = setup(content([domain('d1', { selected: true, questions: [q] })]));
    vi.spyOn(TestBed.inject(ScanService), 'saveAnswer').mockReturnValue(EMPTY);
    const component = fixture.componentInstance;
    component.formFor(q.id).controls.optionId.setValue('q1-o3');
    component.saveQuestion(q);
    expect(component.saveState(q.id)).toBe('idle');
  });
});
