import { describe, it, expect, afterEach } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { render, text } from '../../testing/fixtures';
import { AdminDomain } from '../core/model/admin';
import { AdminComponent } from './admin.component';

const domains: AdminDomain[] = [
  {
    id: 'd1',
    required: true,
    name: 'Datastrategie',
    description: 'Strategie',
    order: 1,
    active: true,
    version: 2,
    questions: [
      {
        id: 'q1',
        domainId: 'd1',
        title: 'Visie',
        text: 'Is er een datavisie?',
        explanation: null,
        order: 1,
        active: true,
        version: 1,
        options: [{ id: 'o1', score: 0, label: 'Nee', description: null, order: 1, available: true, version: 1 }],
      },
    ],
  },
];

function setup() {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ imports: [AdminComponent], providers: [provideHttpClient(), provideHttpClientTesting()] });
  const fixture = TestBed.createComponent(AdminComponent);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  http.expectOne('/v1/admin/domains?pageSize=20').flush({ items: domains, nextCursor: 'next' });
  render(fixture);
  return { fixture, http, root: fixture.nativeElement as HTMLElement };
}

function setInput(root: HTMLElement, selector: string, value: string) {
  const input = root.querySelector<HTMLInputElement>(selector)!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

describe('AdminComponent', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('lists domains, questions and options without delete actions and paginates', () => {
    const { fixture, http, root } = setup();
    expect(text(fixture)).toContain('Datastrategie');
    expect(text(fixture)).toContain('Visie');
    expect(Array.from(root.querySelectorAll('button')).some(b => /verwijder/i.test(b.textContent ?? ''))).toBe(false);
    Array.from(root.querySelectorAll('button')).find(b => b.textContent?.includes('Meer laden'))!.click();
    http.expectOne('/v1/admin/domains?pageSize=20&cursor=next').flush({ items: [{ ...domains[0], id: 'd2', name: 'Governance', questions: [] }], nextCursor: null });
    render(fixture);
    expect(text(fixture)).toContain('Governance');
  });

  it('validates the option score between 0 and 5 and creates the option', () => {
    const { fixture, http, root } = setup();
    fixture.componentInstance.editOption(domains[0], domains[0].questions![0]);
    render(fixture);
    setInput(root, '#option-score', '6');
    setInput(root, '#option-label', 'Volledig');
    root.querySelector<HTMLFormElement>('aside form')!.dispatchEvent(new Event('submit'));
    render(fixture);
    expect(text(fixture)).toContain('Vul een waarde van maximaal 5 in.');
    http.expectNone('/v1/admin/questions/q1/options');

    setInput(root, '#option-score', '2.5');
    render(fixture);
    expect(text(fixture)).toContain('Vul een geheel getal in.');

    setInput(root, '#option-score', '5');
    root.querySelector<HTMLFormElement>('aside form')!.dispatchEvent(new Event('submit'));
    const post = http.expectOne('/v1/admin/questions/q1/options');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ score: 5, label: 'Volledig', description: undefined, order: 2, available: true, version: 0 });
    post.flush({ id: 'o2', score: 5, label: 'Volledig', description: null, order: 2, available: true, version: 1 });
    render(fixture);
    expect(text(fixture)).toContain('Scaninhoud opgeslagen');
    expect(fixture.componentInstance.domains()[0].questions![0].options!.map(o => o.id)).toEqual(['o1', 'o2']);
  });

  it('reuses the idempotency key when an option create is retried, and renews it for the next form', () => {
    const { fixture, http } = setup();
    const component = fixture.componentInstance;
    component.editOption(domains[0], domains[0].questions![0]);
    component.optionForm.patchValue({ score: 4, label: 'Beheerst' });
    component.save();
    const first = http.expectOne('/v1/admin/questions/q1/options');
    const firstKey = first.request.headers.get('X-Idempotency-Key');
    expect(firstKey).toBeTruthy();
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });

    component.save();
    const retry = http.expectOne('/v1/admin/questions/q1/options');
    expect(retry.request.headers.get('X-Idempotency-Key')).toBe(firstKey);
    retry.flush({ id: 'o9', score: 4, label: 'Beheerst', description: null, order: 2, available: true, version: 1 });

    component.editOption(domains[0], domains[0].questions![0]);
    component.optionForm.patchValue({ score: 4, label: 'Beheerst' });
    component.save();
    const next = http.expectOne('/v1/admin/questions/q1/options');
    expect(next.request.headers.get('X-Idempotency-Key')).not.toBe(firstKey);
    next.flush({ id: 'o10', score: 4, label: 'Beheerst', description: null, order: 3, available: true, version: 1 });
  });

  it('limits descriptions and question text to the backend maxima', () => {
    const { fixture } = setup();
    const component = fixture.componentInstance;
    component.domainForm.patchValue({ description: 'x'.repeat(2001) });
    component.questionForm.patchValue({ text: 'x'.repeat(2001), explanation: 'x'.repeat(4000) });
    component.optionForm.patchValue({ description: 'x'.repeat(2001) });
    expect(component.domainForm.controls.description.hasError('maxlength')).toBe(true);
    expect(component.questionForm.controls.text.hasError('maxlength')).toBe(true);
    expect(component.questionForm.controls.explanation.valid).toBe(true);
    expect(component.optionForm.controls.description.hasError('maxlength')).toBe(true);
  });

  it('shows 422 problem details next to the form', () => {
    const { fixture, http, root } = setup();
    fixture.componentInstance.editQuestion(domains[0], domains[0].questions![0]);
    render(fixture);
    root.querySelector<HTMLFormElement>('aside form')!.dispatchEvent(new Event('submit'));
    const patch = http.expectOne('/v1/admin/questions/q1');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toMatchObject({ domainId: 'd1', title: 'Visie', active: true, version: 1 });
    patch.flush(
      { title: 'Vraag kan niet actief zijn', status: 422, detail: 'Een actieve vraag heeft een beschikbare optie nodig.', errors: [{ field: 'active', message: 'Minimaal één optie vereist' }] },
      { status: 422, statusText: 'x' },
    );
    render(fixture);
    const alert = root.querySelector('aside [role="alert"]')!;
    expect(alert.textContent).toContain('Vraag kan niet actief zijn');
    expect(alert.textContent).toContain('Minimaal één optie vereist');
  });

  it('edits a domain with its version', () => {
    const { fixture, http, root } = setup();
    fixture.componentInstance.editDomain(domains[0]);
    render(fixture);
    setInput(root, '#domain-name', 'Datastrategie en visie');
    root.querySelector<HTMLFormElement>('aside form')!.dispatchEvent(new Event('submit'));
    const patch = http.expectOne('/v1/admin/domains/d1');
    expect(patch.request.body).toEqual({ name: 'Datastrategie en visie', description: 'Strategie', order: 1, active: true, version: 2 });
    patch.flush({ ...domains[0], name: 'Datastrategie en visie', version: 3, questions: undefined });
    expect(fixture.componentInstance.domains()[0].questions!.length).toBe(1);
  });
  it('shows required domains and disables their deactivate control', () => {
    const { fixture, root } = setup();
    expect(text(fixture)).toContain('Verplicht');
    fixture.componentInstance.editDomain(domains[0]);
    render(fixture);
    expect(root.querySelector<HTMLInputElement>('#domain-active')!.disabled).toBe(true);
    fixture.componentInstance.editDomain();
    render(fixture);
    expect(root.querySelector<HTMLInputElement>('#domain-active')!.disabled).toBe(false);
  });
});
