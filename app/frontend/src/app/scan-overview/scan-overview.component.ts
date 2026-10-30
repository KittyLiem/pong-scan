import { Component, computed, inject, signal } from '@angular/core';
import { FormGroup, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { SubmissionKey } from '../core/http/idempotency';
import { Problem, applyServerErrors } from '../core/http/problem';
import { Scan } from '../core/model/scan';
import { OrganizationService } from '../core/organization/organization.service';
import { ScanService } from '../core/scans/scan.service';
import { FieldErrorComponent, showFieldError } from '../shared/field-error/field-error.component';
import {
  STATUS_BADGE_CLASSES,
  clampProgress,
  formatDateTime,
  scanActionLabel,
  scanActionLink,
  statusLabel,
} from '../shared/labels';

export const ASSESSMENT_PERIOD_PATTERN = /^(\d{4}|Q[1-4] \d{4}|H[12] \d{4})$/;
const PAGE_SIZE = 20;

@Component({
  selector: 'app-scan-overview',
  imports: [ReactiveFormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './scan-overview.component.html',
})
export class ScanOverviewComponent {
  private readonly scansApi = inject(ScanService);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  readonly organizations = inject(OrganizationService);

  readonly scans = signal<Scan[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly loadingMore = signal(false);
  readonly loaded = signal(false);
  readonly loadProblem = signal<Problem | null>(null);

  readonly showCreate = signal(false);
  readonly creating = signal(false);
  readonly submitted = signal(false);
  readonly createProblem = signal<Problem | null>(null);

  /** Eén idempotency-sleutel per logische inzending van het aanmaakformulier. */
  private readonly createKey = new SubmissionKey();

  readonly completedCount = computed(() => this.scans().filter(scan => scan.status === 'completed').length);

  readonly createForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    assessmentPeriod: ['', [Validators.required, Validators.pattern(ASSESSMENT_PERIOD_PATTERN)]],
  });

  readonly showError = showFieldError;
  readonly statusLabel = statusLabel;
  readonly badgeClass = (scan: Scan) => STATUS_BADGE_CLASSES[scan.status] ?? '';
  readonly actionLink = scanActionLink;
  readonly actionLabel = scanActionLabel;
  readonly formatDateTime = formatDateTime;
  readonly progress = (scan: Scan) => clampProgress(scan.progress);

  constructor() {
    this.load();
  }

  load(): void {
    const organization = this.organizations.current();
    if (!organization) return;
    this.loading.set(true);
    this.loadProblem.set(null);
    this.scansApi
      .list(organization.id, null, PAGE_SIZE)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => {
          this.scans.set(page.items);
          this.nextCursor.set(page.nextCursor ?? null);
          this.loaded.set(true);
        },
        error: (problem: Problem) => this.loadProblem.set(problem),
      });
  }

  loadMore(): void {
    const organization = this.organizations.current();
    const cursor = this.nextCursor();
    if (!organization || !cursor || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.loadProblem.set(null);
    this.scansApi
      .list(organization.id, cursor, PAGE_SIZE)
      .pipe(finalize(() => this.loadingMore.set(false)))
      .subscribe({
        next: page => {
          const known = new Set(this.scans().map(scan => scan.id));
          this.scans.update(items => [...items, ...page.items.filter(scan => !known.has(scan.id))]);
          this.nextCursor.set(page.nextCursor ?? null);
        },
        error: (problem: Problem) => this.loadProblem.set(problem),
      });
  }

  toggleCreate(): void {
    this.showCreate.update(open => !open);
    this.createProblem.set(null);
    if (this.showCreate()) this.createKey.reset();
  }

  createScan(): void {
    this.submitted.set(true);
    this.createProblem.set(null);
    const organization = this.organizations.current();
    if (this.createForm.invalid || !organization || this.creating()) {
      this.createForm.markAllAsTouched();
      return;
    }
    const { name, assessmentPeriod } = this.createForm.getRawValue();
    const input = { name: name.trim(), assessmentPeriod: assessmentPeriod.trim() };
    const key = this.createKey.for({ organizationId: organization.id, ...input });
    this.creating.set(true);
    this.scansApi
      .create(organization.id, input, key)
      .pipe(finalize(() => this.creating.set(false)))
      .subscribe({
        next: scan => {
          this.createKey.reset();
          void this.router.navigate(['/scans', scan.id, 'assessment']);
        },
        error: (problem: Problem) => {
          applyServerErrors(this.createForm as FormGroup, problem);
          this.createProblem.set(problem);
        },
      });
  }
}
