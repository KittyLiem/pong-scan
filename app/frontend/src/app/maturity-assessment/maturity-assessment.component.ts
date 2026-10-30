import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, finalize, forkJoin, switchMap } from 'rxjs';
import { SubmissionKey } from '../core/http/idempotency';
import { Problem, applyServerErrors } from '../core/http/problem';
import { ContentDomain, ContentQuestion, GoalPriority, OrganizationGoal, Scan, ScanContent } from '../core/model/scan';
import { ScanService } from '../core/scans/scan.service';
import { FieldErrorComponent, showFieldError } from '../shared/field-error/field-error.component';
import { PRIORITY_LABELS, STATUS_BADGE_CLASSES, clampProgress, statusLabel } from '../shared/labels';

export type AssessmentStep = 'domains' | 'questions' | 'complete';
type SaveState = 'idle' | 'saving' | 'saved' | 'error';

export const DOMAIN_SELECTION_REQUIRED = 'Selecteer minimaal één kennisgebied om verder te gaan.';
export const SCORE_REQUIRED = 'Selecteer een score voor deze vraag';
export const CONFLICT_MESSAGE = 'Gegevens zijn intussen gewijzigd — herlaad de beoordeling om verder te gaan.';
export const OTHER_GOAL = 'Anders';
export const GOAL_SUGGESTIONS = [
  'Betere besluitvorming op basis van data',
  'Hogere datakwaliteit',
  'Efficiëntere bedrijfsprocessen',
  'Voldoen aan wet- en regelgeving',
  'Betere klantbediening',
  'Innovatie en nieuwe diensten',
];
const MAX_GOALS = 10;
const MAX_EVIDENCE = 10;

type QuestionForm = FormGroup<{
  optionId: FormControl<string>;
  explanation: FormControl<string>;
  evidence: FormControl<string>;
}>;

type GoalForm = FormGroup<{
  name: FormControl<string>;
  selected: FormControl<boolean>;
  priority: FormControl<GoalPriority>;
  customText: FormControl<string>;
}>;

/** Eigen doeltekst is verplicht wanneer "Anders" is geselecteerd. */
function customTextRequiredForOther(control: AbstractControl): ValidationErrors | null {
  const group = control.parent;
  if (!group) return null;
  const selected = group.get('selected')?.value === true;
  const isOther = group.get('name')?.value === OTHER_GOAL;
  return selected && isOther && !String(control.value ?? '').trim() ? { server: 'Vul een eigen doeltekst in.' } : null;
}

function evidenceLines(value: string): string[] {
  return value.split('\n').map(line => line.trim()).filter(line => line.length > 0);
}

function evidenceValidator(control: AbstractControl): ValidationErrors | null {
  const lines = evidenceLines(String(control.value ?? ''));
  if (lines.length > MAX_EVIDENCE) return { server: `Gebruik maximaal ${MAX_EVIDENCE} bewijsverwijzingen (één per regel).` };
  if (lines.some(line => line.length > 2048)) return { server: 'Een bewijsverwijzing mag maximaal 2048 tekens bevatten.' };
  return null;
}

function isComplete(question: ContentQuestion): boolean {
  return !!question.answer?.optionId && !!question.answer.explanation?.trim();
}

function byOrder<T extends { order: number }>(items: T[]): T[] {
  return [...items].sort((a, b) => a.order - b.order);
}

@Component({
  selector: 'app-maturity-assessment',
  imports: [ReactiveFormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './maturity-assessment.component.html',
})
export class MaturityAssessmentComponent {
  private readonly scansApi = inject(ScanService);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  private loadSubscription?: Subscription;
  readonly selectedDomainIds = new FormControl<string[]>([], { nonNullable: true });
  readonly scanId = signal<string>('');
  readonly scan = signal<Scan | null>(null);
  readonly content = signal<ScanContent | null>(null);
  readonly loading = signal(false);
  readonly loadProblem = signal<Problem | null>(null);
  readonly conflict = signal<string | null>(null);
  readonly step = signal<AssessmentStep>('domains');

  // Stap 1: kennisgebieden en doelen
  readonly selection = signal<string[]>([]);
  readonly domainError = signal<string | null>(null);
  readonly savingSelection = signal(false);
  readonly selectionProblem = signal<Problem | null>(null);
  readonly goalsSubmitted = signal(false);
  readonly goals = new FormArray<GoalForm>([]);
  private readonly selectionForm = new FormGroup({ goals: this.goals, selectedDomainIds: this.selectedDomainIds });

  // Stap 2: vragen
  readonly activeDomainId = signal<string | null>(null);
  private questionForms = new Map<string, QuestionForm>();
  readonly saveStates = signal<Record<string, SaveState>>({});
  readonly saveMessages = signal<Record<string, string>>({});
  readonly questionSubmitted = signal<Record<string, boolean>>({});

  // Stap 3: afronden
  readonly generalComments = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(4000)] });
  private readonly completionForm = new FormGroup({ generalComments: this.generalComments });
  readonly completing = signal(false);
  private readonly completionKey = new SubmissionKey();
  readonly completionProblem = signal<Problem | null>(null);

  readonly showError = showFieldError;
  readonly priorityLabels = PRIORITY_LABELS;
  readonly priorities: GoalPriority[] = ['high', 'medium', 'low'];
  readonly statusLabel = statusLabel;
  readonly statusBadge = STATUS_BADGE_CLASSES;
  readonly selectionMessage = DOMAIN_SELECTION_REQUIRED;
  readonly scoreRequired = SCORE_REQUIRED;

  readonly editable = computed(() => this.content()?.editable === true && this.scan()?.status !== 'completed');
  readonly allDomains = computed(() => byOrder(this.content()?.domains ?? []));
  /** Kennisgebieden die op de server als geselecteerd zijn opgeslagen. */
  readonly selectedDomains = computed(() => this.allDomains().filter(domain => domain.selected));
  readonly hasSavedSelection = computed(() => this.selectedDomains().length > 0);
  readonly activeDomain = computed(() => {
    const domains = this.selectedDomains();
    return domains.find(domain => domain.id === this.activeDomainId()) ?? domains[0] ?? null;
  });
  readonly allSelected = computed(() => {
    const domains = this.allDomains();
    return domains.length > 0 && domains.every(domain => this.selection().includes(domain.id));
  });
  readonly selectedQuestions = computed(() => this.selectedDomains().flatMap(domain => domain.questions));
  readonly totalQuestions = computed(() => this.selectedQuestions().length);
  readonly answeredQuestions = computed(() => this.selectedQuestions().filter(question => !!question.answer?.optionId).length);
  readonly completeQuestions = computed(() => this.selectedQuestions().filter(isComplete).length);
  readonly overallProgress = computed(() => {
    const total = this.totalQuestions();
    return total === 0 ? 0 : clampProgress((this.answeredQuestions() / total) * 100);
  });
  readonly incomplete = computed(() =>
    this.selectedDomains().flatMap(domain =>
      byOrder(domain.questions)
        .filter(question => !isComplete(question))
        .map(question => ({
          domainId: domain.id,
          domainName: domain.name,
          questionId: question.id,
          title: question.title,
          missing: !question.answer?.optionId ? 'score en toelichting' : 'toelichting',
        })),
    ),
  );
  readonly canComplete = computed(() => this.editable() && this.totalQuestions() > 0 && this.completeQuestions() === this.totalQuestions());

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(params => {
        this.loadSubscription?.unsubscribe();
        this.scan.set(null);
        this.content.set(null);
        this.selection.set([]);
        this.goals.clear();
        this.questionForms.clear();
        this.conflict.set(null);
        this.scanId.set(params.get('scanId') ?? '');
        this.load(true);
      });
  }

  // ---------- laden ----------

  load(resetStep = false): void {
    this.loadSubscription?.unsubscribe();
    const id = this.scanId();
    if (!id) return;
    this.loading.set(true);
    this.loadProblem.set(null);
    this.loadSubscription = forkJoin({ scan: this.scansApi.get(id), content: this.scansApi.content(id) })
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.loading.set(false)))
      .subscribe({
        next: ({ scan, content }) => {
          this.conflict.set(null);
          this.applyLoaded(scan, content, resetStep);
        },
        error: (problem: Problem) => this.loadProblem.set(problem),
      });
  }

  private applyLoaded(scan: Scan, content: ScanContent, resetStep: boolean): void {
    this.scan.set(scan);
    this.content.set(content);
    this.selection.set(content.domains.filter(domain => domain.selected).map(domain => domain.id));
    this.domainError.set(null);
    this.buildGoalForms(scan.goals ?? []);
    this.generalComments.setValue(scan.generalComments ?? '');
    this.questionForms = new Map();
    this.saveStates.set({});
    this.saveMessages.set({});
    this.questionSubmitted.set({});
    for (const domain of content.domains) {
      for (const question of domain.questions) this.questionForms.set(question.id, this.buildQuestionForm(question));
    }
    const selected = this.selectedDomains();
    if (!selected.some(domain => domain.id === this.activeDomainId())) this.activeDomainId.set(selected[0]?.id ?? null);
    if (resetStep || (this.step() !== 'domains' && selected.length === 0)) {
      this.step.set(selected.length > 0 ? 'questions' : 'domains');
    }
  }

  private buildQuestionForm(question: ContentQuestion): QuestionForm {
    return this.fb.group({
      optionId: [question.answer?.optionId ?? '', Validators.required],
      explanation: [question.answer?.explanation ?? '', Validators.maxLength(4000)],
      evidence: [(question.answer?.evidenceReferences ?? []).join('\n'), evidenceValidator],
    });
  }

  private buildGoalForms(saved: OrganizationGoal[]): void {
    this.goals.clear();
    const names = [...GOAL_SUGGESTIONS, ...saved.map(goal => goal.name).filter(name => name !== OTHER_GOAL && !GOAL_SUGGESTIONS.includes(name)), OTHER_GOAL];
    for (const name of names) {
      const existing = saved.find(goal => goal.name === name);
      const group: GoalForm = this.fb.group({
        name: [name],
        selected: [!!existing],
        priority: [existing?.priority ?? ('medium' as GoalPriority)],
        customText: [existing?.customText ?? '', [Validators.maxLength(2000), customTextRequiredForOther]],
      });
      group.controls.selected.valueChanges
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(() => group.controls.customText.updateValueAndValidity());
      this.goals.push(group);
    }
    this.goals.controls.forEach(group => group.controls.customText.updateValueAndValidity());
  }

  formFor(questionId: string): QuestionForm {
    let form = this.questionForms.get(questionId);
    if (!form) {
      form = this.fb.group({ optionId: ['', Validators.required], explanation: [''], evidence: [''] });
      this.questionForms.set(questionId, form);
    }
    return form;
  }

  // ---------- stappen ----------

  goTo(step: AssessmentStep): void {
    if (step !== 'domains' && !this.hasSavedSelection()) {
      this.domainError.set(DOMAIN_SELECTION_REQUIRED);
      return;
    }
    this.step.set(step);
    this.scrollToTop();
  }

  private scrollToTop(): void {
    if (typeof window === 'undefined' || /jsdom/i.test(navigator.userAgent)) return;
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // ---------- stap 1: kennisgebieden + doelen ----------

  isSelected(domainId: string): boolean {
    return this.selection().includes(domainId);
  }

  toggleDomain(domainId: string, checked: boolean): void {
    this.selection.update(ids => (checked ? [...new Set([...ids, domainId])] : ids.filter(id => id !== domainId)));
    if (checked) this.domainError.set(null);
  }

  toggleAll(): void {
    if (this.allSelected()) {
      this.selection.set([]);
    } else {
      this.selection.set(this.allDomains().map(domain => domain.id));
      this.domainError.set(null);
    }
  }

  selectedGoalCount(): number {
    return this.goals.controls.filter(group => group.controls.selected.value).length;
  }

  private goalPayload(): OrganizationGoal[] {
    return this.goals.controls
      .filter(group => group.controls.selected.value)
      .map(group => {
        const { name, priority, customText } = group.getRawValue();
        const goal: OrganizationGoal = { name, priority };
        if (customText.trim()) goal.customText = customText.trim();
        return goal;
      });
  }

  continueToQuestions(): void {
    this.selectionProblem.set(null);
    this.selectedDomainIds.setErrors(null);
    this.goalsSubmitted.set(true);
    if (this.selection().length === 0) {
      this.domainError.set(DOMAIN_SELECTION_REQUIRED);
      return;
    }
    this.goals.controls.forEach(group => group.controls.customText.updateValueAndValidity());
    if (this.goals.invalid || this.selectedGoalCount() > MAX_GOALS) {
      this.goals.markAllAsTouched();
      return;
    }
    if (this.savingSelection()) return;
    const id = this.scanId();
    const selectedDomainIds = this.allDomains().map(domain => domain.id).filter(domainId => this.selection().includes(domainId));
    const goals = this.goalPayload();
    this.savingSelection.set(true);
    const version = this.scan()?.version ?? 0;
    this.scansApi
      .update(id, { version, selectedDomainIds, goals })
      .pipe(
        switchMap(scan => forkJoin({ scan: [scan], content: this.scansApi.content(id) })),
        finalize(() => this.savingSelection.set(false)),
      )
      .subscribe({
        next: ({ scan, content }) => {
          this.applyLoaded(scan, content, false);
          this.goalsSubmitted.set(false);
          this.step.set('questions');
          this.scrollToTop();
        },
        error: (problem: Problem) => {
          if (problem.status === 409) this.conflict.set(CONFLICT_MESSAGE);
          // De request bevat alleen geselecteerde doelen; de formulierlijst bevat ook de suggesties.
          const selectedIndices = this.goals.controls.map((group, index) => group.controls.selected.value ? index : -1).filter(index => index >= 0);
          const aliases: Record<string, string> = {};
          for (const error of problem.errors) {
            const match = /^goals\[(\d+)\](.*)$/.exec(error.field);
            if (match && selectedIndices[Number(match[1])] !== undefined) {
              aliases[error.field] = `goals.${selectedIndices[Number(match[1])]}${match[2]}`;
            }
          }
          applyServerErrors(this.selectionForm, problem, aliases);
          this.selectionProblem.set(problem);
        },
      });
  }

  // ---------- stap 2: vragen ----------

  orderedQuestions(domain: ContentDomain): ContentQuestion[] {
    return byOrder(domain.questions);
  }

  orderedOptions(question: ContentQuestion) {
    return byOrder(question.options);
  }

  answeredIn(domain: ContentDomain): number {
    return domain.questions.filter(question => !!question.answer?.optionId).length;
  }

  selectDomain(domainId: string): void {
    this.activeDomainId.set(domainId);
    this.step.set('questions');
    this.scrollToTop();
  }

  saveState(questionId: string): SaveState {
    return this.saveStates()[questionId] ?? 'idle';
  }

  saveMessage(questionId: string): string | null {
    return this.saveMessages()[questionId] ?? null;
  }

  wasSubmitted(questionId: string): boolean {
    return this.questionSubmitted()[questionId] === true;
  }

  scoreMissing(questionId: string): boolean {
    const control = this.formFor(questionId).controls.optionId;
    return control.invalid && (control.touched || this.wasSubmitted(questionId));
  }

  hasUnsavedChanges(): boolean {
    return [...this.questionForms.values()].some(form => form.dirty);
  }

  saveQuestion(question: ContentQuestion): void {
    const id = this.scanId();
    const form = this.formFor(question.id);
    this.questionSubmitted.update(state => ({ ...state, [question.id]: true }));
    this.saveMessages.update(state => ({ ...state, [question.id]: '' }));
    if (form.invalid) {
      form.markAllAsTouched();
      return;
    }
    if (this.saveState(question.id) === 'saving') return;
    const { optionId, explanation, evidence } = form.getRawValue();
    const version = question.answer?.version ?? 0;
    this.saveStates.update(state => ({ ...state, [question.id]: 'saving' }));
    this.scansApi
      .saveAnswer(id, question.id, {
        optionId,
        explanation: explanation.trim(),
        evidenceReferences: evidenceLines(evidence),
        version,
      })
      .pipe(finalize(() => {
        if (this.saveState(question.id) === 'saving') {
          this.saveStates.update(state => ({ ...state, [question.id]: 'idle' }));
        }
      }))
      .subscribe({
        next: saved => {
          this.content.update(content =>
            content && {
              ...content,
              status: saved.scanStatus,
              domains: content.domains.map(domain => {
                if (!domain.questions.some(item => item.id === question.id)) return domain;
                const questions = domain.questions.map(item =>
                  item.id === question.id
                    ? {
                        ...item,
                        answer: {
                          optionId: saved.optionId,
                          explanation: saved.explanation ?? '',
                          evidenceReferences: saved.evidenceReferences ?? [],
                          version: saved.version,
                        },
                      }
                    : item,
                );
                return { ...domain, questions, answeredCount: questions.filter(item => !!item.answer?.optionId).length };
              }),
            },
          );
          this.scan.update(
            scan =>
              scan && {
                ...scan,
                progress: saved.progress,
                status: saved.scanStatus,
                version: typeof saved.scanVersion === 'number' ? saved.scanVersion : scan.version,
              },
          );
          form.markAsPristine();
          this.saveStates.update(state => ({ ...state, [question.id]: 'saved' }));
          this.saveMessages.update(state => ({ ...state, [question.id]: 'Opgeslagen' }));
        },
        error: (problem: Problem) => {
          this.saveStates.update(state => ({ ...state, [question.id]: 'error' }));
          if (problem.status === 409) {
            this.conflict.set(CONFLICT_MESSAGE);
            this.saveMessages.update(state => ({ ...state, [question.id]: CONFLICT_MESSAGE }));
            return;
          }
          applyServerErrors(form as unknown as FormGroup, problem, { evidenceReferences: 'evidence' });
          const message = [problem.title, problem.detail].filter(Boolean).join(': ');
          this.saveMessages.update(state => ({ ...state, [question.id]: message || 'Antwoord niet opgeslagen. Probeer het opnieuw.' }));
        },
      });
  }

  optionLabel(question: ContentQuestion): string {
    const option = question.options.find(item => item.id === question.answer?.optionId);
    return option ? `${option.score} — ${option.label}` : 'Niet beantwoord';
  }

  // ---------- stap 3: afronden ----------

  complete(): void {
    this.completionProblem.set(null);
    if (!this.canComplete() || this.completing() || this.generalComments.invalid) {
      this.generalComments.markAsTouched();
      return;
    }
    const id = this.scanId();
    const generalComments = this.generalComments.value.trim();
    this.completing.set(true);
    const version = this.scan()?.version ?? 0;
    const input = generalComments ? { version, generalComments } : { version };
    const key = this.completionKey.for({ scanId: id, ...input });
    this.scansApi
      .complete(id, input, key)
      .pipe(finalize(() => this.completing.set(false)))
      .subscribe({
        next: scan => {
          this.completionKey.reset();
          this.scan.set(scan);
          void this.router.navigate(['/scans', id, 'results']);
        },
        error: (problem: Problem) => {
          if (problem.status === 409) this.conflict.set(CONFLICT_MESSAGE);
          applyServerErrors(this.completionForm, problem);
          this.completionProblem.set(problem);
        },
      });
  }
}
