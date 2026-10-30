import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormGroup, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Observable, finalize } from 'rxjs';
import { AdminContentService } from '../core/admin/admin-content.service';
import { SubmissionKey } from '../core/http/idempotency';
import { Problem, applyServerErrors } from '../core/http/problem';
import { AdminDomain, AdminOption, AdminQuestion } from '../core/model/admin';
import { FieldErrorComponent, showFieldError } from '../shared/field-error/field-error.component';

type Editor =
  | { kind: 'domain'; domain?: AdminDomain }
  | { kind: 'question'; domainId: string; question?: AdminQuestion }
  | { kind: 'option'; domainId: string; questionId: string; option?: AdminOption };

function integer(control: AbstractControl): ValidationErrors | null {
  const value = control.value;
  if (value === null || value === undefined || value === '') return null;
  return Number.isInteger(Number(value)) ? null : { integer: true };
}

function byOrder<T extends { order: number }>(items: T[] | undefined): T[] {
  return [...(items ?? [])].sort((a, b) => a.order - b.order);
}

@Component({
  selector: 'app-admin',
  imports: [ReactiveFormsModule, NgTemplateOutlet, FieldErrorComponent],
  templateUrl: './admin.component.html',
})
export class AdminComponent {
  private readonly api = inject(AdminContentService);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly domains = signal<AdminDomain[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly loadProblem = signal<Problem | null>(null);

  readonly editor = signal<Editor | null>(null);
  readonly saving = signal(false);
  readonly submitted = signal(false);
  readonly editorProblem = signal<Problem | null>(null);
  readonly notice = signal<string | null>(null);

  /** Eén idempotency-sleutel per geopend aanmaakformulier (hergebruikt bij een herhaalde, ongewijzigde poging). */
  private readonly createKey = new SubmissionKey();

  readonly showError = showFieldError;
  readonly byOrder = byOrder;

  readonly domainForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    description: ['', [Validators.maxLength(2000)]],
    order: [1, [Validators.required, Validators.min(0), Validators.max(10000), integer]],
    active: [true],
  });

  readonly questionForm = this.fb.group({
    domainId: [''],
    title: ['', [Validators.required, Validators.maxLength(255)]],
    text: ['', [Validators.required, Validators.maxLength(2000)]],
    explanation: ['', [Validators.maxLength(4000)]],
    order: [1, [Validators.required, Validators.min(0), Validators.max(10000), integer]],
    active: [true],
  });

  readonly optionForm = this.fb.group({
    score: [0 as number | null, [Validators.required, Validators.min(0), Validators.max(5), integer]],
    label: ['', [Validators.required, Validators.maxLength(255)]],
    description: ['', [Validators.maxLength(2000)]],
    order: [1, [Validators.required, Validators.min(0), Validators.max(10000), integer]],
    available: [true],
  });

  readonly editorTitle = computed(() => {
    const editor = this.editor();
    if (!editor) return '';
    if (editor.kind === 'domain') return editor.domain ? 'Kennisgebied wijzigen' : 'Nieuw kennisgebied';
    if (editor.kind === 'question') return editor.question ? 'Vraag wijzigen' : 'Nieuwe vraag';
    return editor.option ? 'Antwoordoptie wijzigen' : 'Nieuwe antwoordoptie';
  });

  constructor() {
    this.load();
  }

  load(more = false): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.loadProblem.set(null);
    this.api
      .listDomains(more ? this.nextCursor() : null)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => {
          this.domains.update(items => (more ? [...items, ...page.items.filter(d => !items.some(i => i.id === d.id))] : page.items));
          this.nextCursor.set(page.nextCursor ?? null);
          this.loaded.set(true);
        },
        error: (problem: Problem) => this.loadProblem.set(problem),
      });
  }

  // ---------- editor openen ----------

  private open(editor: Editor): void {
    this.editor.set(editor);
    this.createKey.reset();
    this.submitted.set(false);
    this.editorProblem.set(null);
    this.notice.set(null);
    // Op smalle schermen staat het formulier onder de lijst: breng het in beeld en zet de focus erop.
    setTimeout(() => {
      const heading = document.getElementById('editor-heading');
      if (!heading) return;
      if (typeof heading.scrollIntoView === 'function') heading.scrollIntoView({ block: 'start', behavior: 'smooth' });
      heading.focus();
    });
  }

  editDomain(domain?: AdminDomain): void {
    if (domain?.required) this.domainForm.controls.active.disable();
    else this.domainForm.controls.active.enable();
    this.domainForm.reset({
      name: domain?.name ?? '',
      description: domain?.description ?? '',
      order: domain?.order ?? this.domains().length + 1,
      active: domain?.active ?? true,
    });
    this.open({ kind: 'domain', domain });
  }

  editQuestion(domain: AdminDomain, question?: AdminQuestion): void {
    this.questionForm.reset({
      domainId: question?.domainId ?? domain.id,
      title: question?.title ?? '',
      text: question?.text ?? '',
      explanation: question?.explanation ?? '',
      order: question?.order ?? (domain.questions?.length ?? 0) + 1,
      // Nieuwe vragen starten inactief: een actieve vraag vereist minimaal één beschikbare optie.
      active: question?.active ?? false,
    });
    this.open({ kind: 'question', domainId: domain.id, question });
  }

  editOption(domain: AdminDomain, question: AdminQuestion, option?: AdminOption): void {
    this.optionForm.reset({
      score: option?.score ?? 0,
      label: option?.label ?? '',
      description: option?.description ?? '',
      order: option?.order ?? (question.options?.length ?? 0) + 1,
      available: option?.available ?? true,
    });
    this.open({ kind: 'option', domainId: domain.id, questionId: question.id, option });
  }

  cancel(): void {
    this.editor.set(null);
    this.editorProblem.set(null);
  }

  // ---------- opslaan ----------

  save(): void {
    const editor = this.editor();
    if (!editor || this.saving()) return;
    this.submitted.set(true);
    this.editorProblem.set(null);
    const form = (editor.kind === 'domain' ? this.domainForm : editor.kind === 'question' ? this.questionForm : this.optionForm) as FormGroup;
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }
    const optional = (value: string) => (value.trim() ? value.trim() : undefined);
    let request: Observable<unknown>;
    let apply: (result: unknown) => void;

    if (editor.kind === 'domain') {
      const v = this.domainForm.getRawValue();
      const input = { name: v.name.trim(), description: optional(v.description), order: Number(v.order), active: v.active, version: editor.domain?.version ?? 0 };
      request = editor.domain ? this.api.updateDomain(editor.domain.id, input) : this.api.createDomain(input, this.createKey.for(input));
      apply = result => this.mergeDomain(result as AdminDomain);
    } else if (editor.kind === 'question') {
      const v = this.questionForm.getRawValue();
      const base = { title: v.title.trim(), text: v.text.trim(), explanation: optional(v.explanation), order: Number(v.order), active: v.active };
      request = editor.question
        ? this.api.updateQuestion(editor.question.id, { ...base, domainId: v.domainId || editor.domainId, version: editor.question.version })
        : this.api.createQuestion(editor.domainId, { ...base, version: 0 }, this.createKey.for({ domainId: editor.domainId, ...base }));
      apply = result => this.mergeQuestion(editor.domainId, result as AdminQuestion);
    } else {
      const v = this.optionForm.getRawValue();
      const input = { score: Number(v.score), label: v.label.trim(), description: optional(v.description), order: Number(v.order), available: v.available, version: editor.option?.version ?? 0 };
      request = editor.option
        ? this.api.updateOption(editor.option.id, input)
        : this.api.createOption(editor.questionId, input, this.createKey.for({ questionId: editor.questionId, ...input }));
      apply = result => this.mergeOption(editor.domainId, editor.questionId, result as AdminOption);
    }

    this.saving.set(true);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: result => {
        apply(result);
        this.createKey.reset();
        this.notice.set('Scaninhoud opgeslagen. Bestaande scans en resultaten veranderen niet.');
        this.editor.set(null);
      },
      error: (problem: Problem) => {
        applyServerErrors(form, problem);
        this.editorProblem.set(problem);
      },
    });
  }

  private mergeDomain(domain: AdminDomain): void {
    this.domains.update(items => {
      const existing = items.find(item => item.id === domain.id);
      const merged = { ...domain, questions: domain.questions ?? existing?.questions ?? [] };
      return existing ? items.map(item => (item.id === domain.id ? merged : item)) : [...items, merged];
    });
  }

  private mergeQuestion(originalDomainId: string, question: AdminQuestion): void {
    const targetDomainId = question.domainId ?? originalDomainId;
    this.domains.update(items => {
      const previous = items.flatMap(d => d.questions ?? []).find(q => q.id === question.id);
      const merged = { ...question, options: question.options ?? previous?.options ?? [] };
      return items.map(domain => {
        const without = (domain.questions ?? []).filter(q => q.id !== question.id);
        return domain.id === targetDomainId ? { ...domain, questions: [...without, merged] } : { ...domain, questions: without };
      });
    });
  }

  private mergeOption(domainId: string, questionId: string, option: AdminOption): void {
    this.domains.update(items =>
      items.map(domain =>
        domain.id !== domainId
          ? domain
          : {
              ...domain,
              questions: (domain.questions ?? []).map(question => {
                if (question.id !== questionId) return question;
                const options = question.options ?? [];
                const exists = options.some(item => item.id === option.id);
                return { ...question, options: exists ? options.map(item => (item.id === option.id ? option : item)) : [...options, option] };
              }),
            },
      ),
    );
  }
}
