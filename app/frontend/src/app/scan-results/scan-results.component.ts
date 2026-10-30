import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, finalize } from 'rxjs';
import { SubmissionKey } from '../core/http/idempotency';
import { Problem } from '../core/http/problem';
import {
  ActionOverview,
  DevelopmentClassification,
  DevelopmentPoint,
  DevelopmentPriority,
  PriorityRating,
  ScanResults,
} from '../core/model/results';
import { RevisionMode } from '../core/model/scan';
import { ScanResultsService } from '../core/scans/scan-results.service';
import { CLASSIFICATION_LABELS, PRIORITY_LABELS, formatDate, formatScore } from '../shared/labels';
import { radarGeometry } from './radar';

const CLASSIFICATION_ORDER: DevelopmentClassification[] = ['strength', 'development-point', 'gap'];
const RATING_WEIGHT: Record<PriorityRating, number> = { high: 3, medium: 2, low: 1 };

export function pdfFileName(scanName: string): string {
  const slug = scanName
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return `scan-${slug || 'resultaten'}.pdf`;
}

@Component({
  selector: 'app-scan-results',
  imports: [RouterLink],
  templateUrl: './scan-results.component.html',
})
export class ScanResultsComponent {
  private readonly api = inject(ScanResultsService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  private loadSubscription?: Subscription;
  readonly selectedPrioritySetRevision = signal<number | undefined>(undefined);
  readonly scanId = signal('');
  readonly results = signal<ScanResults | null>(null);
  readonly loading = signal(false);
  readonly loadProblem = signal<Problem | null>(null);

  readonly priorities = signal<Record<string, DevelopmentPriority>>({});
  readonly overview = signal<ActionOverview | null>(null);
  readonly savingPriorities = signal(false);
  readonly prioritiesProblem = signal<Problem | null>(null);
  readonly prioritiesNotice = signal<string | null>(null);

  readonly exporting = signal(false);
  readonly exportProblem = signal<Problem | null>(null);
  readonly exportNotice = signal<string | null>(null);

  private readonly revisionKey = new SubmissionKey();
  readonly revisionMode = signal<RevisionMode>('copy');
  readonly creatingRevision = signal(false);
  readonly revisionProblem = signal<Problem | null>(null);

  readonly formatScore = formatScore;
  readonly formatDate = formatDate;
  readonly priorityLabels = PRIORITY_LABELS;
  readonly ratings: PriorityRating[] = ['high', 'medium', 'low'];

  readonly radar = computed(() => radarGeometry(this.results()?.domainScores ?? []));
  readonly radarLabel = computed(() => {
    const scores = this.results()?.domainScores ?? [];
    return `Radargrafiek van de volwassenheidsscore per kennisgebied: ${scores.map(score => `${score.domainName} ${formatScore(score.score)}`).join(', ')}.`;
  });
  readonly groupedPoints = computed(() => {
    const points = this.results()?.developmentPoints ?? [];
    return CLASSIFICATION_ORDER.map(classification => ({
      classification,
      label: CLASSIFICATION_LABELS[classification],
      points: points.filter(point => point.classification === classification),
    })).filter(group => group.points.length > 0);
  });
  /** Ontwikkelpunten die geprioriteerd kunnen worden (sterktes niet). */
  readonly prioritizable = computed(() => (this.results()?.developmentPoints ?? []).filter(point => point.classification !== 'strength'));
  readonly actionItems = computed(() => {
    const byId = new Map((this.results()?.developmentPoints ?? []).map(point => [point.id, point]));
    return (this.overview()?.items ?? [])
      .filter(item => item.selected && byId.has(item.developmentPointId))
      .map(item => ({ ...item, point: byId.get(item.developmentPointId)! }))
      .sort((a, b) => RATING_WEIGHT[b.impact] + RATING_WEIGHT[b.urgency] - (RATING_WEIGHT[a.impact] + RATING_WEIGHT[a.urgency]));
  });

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(params => {
        this.loadSubscription?.unsubscribe();
        this.results.set(null);
        this.applyOverview(null);
        this.selectedPrioritySetRevision.set(undefined);
        this.scanId.set(params.get('scanId') ?? '');
        this.load();
      });
  }

  load(prioritySetRevision?: number): void {
    this.loadSubscription?.unsubscribe();
    this.results.set(null);
    this.applyOverview(null);
    this.selectedPrioritySetRevision.set(prioritySetRevision);
    const id = this.scanId();
    if (!id) return;
    this.loading.set(true);
    this.loadProblem.set(null);
    this.loadSubscription = this.api
      .results(id, prioritySetRevision)
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.loading.set(false)))
      .subscribe({
        next: results => {
          this.results.set(results);
          this.selectedPrioritySetRevision.set(results.priorities?.setRevision || undefined);
          this.applyOverview(results.priorities ?? null);
        },
        error: (problem: Problem) => this.loadProblem.set(problem),
      });
  }

  private applyOverview(overview: ActionOverview | null): void {
    this.overview.set(overview);
    const existing = new Map((overview?.items ?? []).map(item => [item.developmentPointId, item]));
    const state: Record<string, DevelopmentPriority> = {};
    for (const point of this.prioritizable()) {
      state[point.id] = existing.get(point.id) ?? { developmentPointId: point.id, impact: 'medium', urgency: 'medium', selected: false };
    }
    this.priorities.set(state);
  }

  priorityFor(point: DevelopmentPoint): DevelopmentPriority {
    return this.priorities()[point.id] ?? { developmentPointId: point.id, impact: 'medium', urgency: 'medium', selected: false };
  }

  updatePriority(point: DevelopmentPoint, change: Partial<DevelopmentPriority>): void {
    this.prioritiesNotice.set(null);
    this.priorities.update(state => ({ ...state, [point.id]: { ...this.priorityFor(point), ...change } }));
  }

  selectedPriorityCount(): number {
    return Object.values(this.priorities()).filter(item => item.selected).length;
  }

  savePriorities(): void {
    if (this.savingPriorities()) return;
    this.prioritiesProblem.set(null);
    this.prioritiesNotice.set(null);
    const items = this.prioritizable().map(point => this.priorityFor(point));
    this.savingPriorities.set(true);
    this.api
      .savePriorities(this.scanId(), items)
      .pipe(finalize(() => this.savingPriorities.set(false)))
      .subscribe({
        next: overview => {
          this.results.update(results => results && ({ ...results, priorities: overview,
            availablePrioritySetRevisions: [...new Set([...results.availablePrioritySetRevisions, overview.setRevision])].sort((a, b) => a - b) }));
          this.selectedPrioritySetRevision.set(overview.setRevision);
          this.applyOverview(overview);
          this.prioritiesNotice.set(`Ontwikkelprioriteiten opgeslagen (versie ${overview.setRevision}).`);
        },
        error: (problem: Problem) => this.prioritiesProblem.set(problem),
      });
  }

  exportPdf(): void {
    const results = this.results();
    if (!results || this.exporting()) return;
    this.exportProblem.set(null);
    this.exportNotice.set(null);
    this.exporting.set(true);
    this.api
      .exportPdf(this.scanId(), this.selectedPrioritySetRevision())
      .pipe(finalize(() => this.exporting.set(false)))
      .subscribe({
        next: blob => {
          const fileName = pdfFileName(results.scanName);
          if (typeof URL.createObjectURL === 'function') {
            const url = URL.createObjectURL(blob);
            const anchor = document.createElement('a');
            anchor.href = url;
            anchor.download = fileName;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            setTimeout(() => URL.revokeObjectURL(url), 0);
          }
          this.exportNotice.set(`PDF-rapport ${fileName} is gedownload.`);
        },
        error: (problem: Problem) =>
          this.exportProblem.set({ ...problem, detail: problem.detail ?? 'Het rapport kon niet worden samengesteld. Probeer het opnieuw.' }),
      });
  }

  createRevision(): void {
    if (this.creatingRevision()) return;
    this.revisionProblem.set(null);
    this.creatingRevision.set(true);
    const key = this.revisionKey.for({ scanId: this.scanId(), mode: this.revisionMode() });
    this.api
      .createRevision(this.scanId(), this.revisionMode(), key)
      .pipe(finalize(() => this.creatingRevision.set(false)))
      .subscribe({
        next: scan => {
          this.revisionKey.reset();
          void this.router.navigate(['/scans', scan.id, 'assessment']);
        },
        error: (problem: Problem) => this.revisionProblem.set(problem),
      });
  }

  selectPriorityRevision(value: string): void {
    this.load(Number(value));
  }

  asRating(value: string): PriorityRating {
    return (this.ratings as string[]).includes(value) ? (value as PriorityRating) : 'medium';
  }
}
