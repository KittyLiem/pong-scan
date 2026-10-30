import { Component, computed, inject, signal } from '@angular/core';
import { FormGroup, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, finalize, switchMap } from 'rxjs';
import { AuthService, safeInternalRoute } from '../core/auth/auth.service';
import { SubmissionKey } from '../core/http/idempotency';
import { Problem, applyServerErrors } from '../core/http/problem';
import { Session } from '../core/model/auth';
import { OrganizationService } from '../core/organization/organization.service';
import { FieldErrorComponent, showFieldError } from '../shared/field-error/field-error.component';

type Mode = 'login' | 'register';

@Component({
  selector: 'app-account-access',
  imports: [ReactiveFormsModule, RouterLink, FieldErrorComponent],
  templateUrl: './account-access.component.html',
})
export class AccountAccessComponent {
  readonly auth = inject(AuthService);
  readonly organizations = inject(OrganizationService);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly returnUrl = safeInternalRoute(inject(ActivatedRoute).snapshot.queryParamMap.get('returnUrl'));

  readonly mode = signal<Mode>('login');
  readonly showPassword = signal(false);
  readonly busy = signal(false);
  readonly submitted = signal(false);
  readonly problem = signal<Problem | null>(null);
  readonly notice = signal<string | null>(this.returnUrl && this.returnUrl !== '/scans' ? 'Meld je (opnieuw) aan om verder te gaan waar je gebleven was.' : null);

  readonly loadingOrganizations = signal(false);
  readonly organizationsProblem = signal<Problem | null>(null);
  readonly orgBusy = signal(false);
  readonly orgSubmitted = signal(false);
  readonly orgProblem = signal<Problem | null>(null);
  /** Eén idempotency-sleutel per logische inzending van het organisatieformulier. */
  private readonly organizationKey = new SubmissionKey();
  private readonly pendingRoute = signal<string | null>(this.returnUrl);

  readonly stage = computed(() => {
    if (!this.auth.isAuthenticated()) return 'account' as const;
    if (this.loadingOrganizations()) return 'loading' as const;
    if (this.organizationsProblem()) return 'organizations-error' as const;
    return this.organizations.hasOrganization() ? ('ready' as const) : ('organization' as const);
  });

  readonly form = this.fb.group({
    accountIdentifier: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    credential: ['', [Validators.required, Validators.minLength(8), control => (control.value?.length ?? 0) > 72 ? { passwordTooLong: true } : null]],
  });

  readonly organizationForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    chamberOfCommerceNumber: ['', [Validators.pattern(/^\d{8}$/)]],
    industry: ['', [Validators.maxLength(255)]],
    additionalInformation: ['', [Validators.maxLength(4000)]],
  });

  readonly showError = showFieldError;

  constructor() {
    // Aangemeld maar organisatie nog onbekend (bv. na een 401-redirect binnen dezelfde sessie): opnieuw ophalen.
    if (this.auth.isAuthenticated() && !this.organizations.hasOrganization()) this.loadOrganizations();
  }

  setMode(mode: Mode): void {
    this.mode.set(mode);
    this.problem.set(null);
    this.submitted.set(false);
  }

  submit(): void {
    this.submitted.set(true);
    this.problem.set(null);
    if (this.form.invalid || this.busy()) {
      this.form.markAllAsTouched();
      return;
    }
    const { accountIdentifier, credential } = this.form.getRawValue();
    const email = accountIdentifier.trim();
    const login$: Observable<Session> = this.mode() === 'register'
      ? this.auth.register(email, credential).pipe(switchMap(() => this.auth.login(email, credential, this.returnUrl)))
      : this.auth.login(email, credential, this.returnUrl);

    this.busy.set(true);
    login$.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: session => {
        this.form.controls.credential.reset('');
        this.submitted.set(false);
        this.notice.set(null);
        this.pendingRoute.set(safeInternalRoute(session.desiredRoute) ?? this.returnUrl);
        this.loadOrganizations();
      },
      error: (problem: Problem) => {
        applyServerErrors(this.form as FormGroup, problem);
        this.problem.set(problem.status === 401
          ? { ...problem, title: 'Aanmelden mislukt', detail: 'De combinatie van e-mailadres en wachtwoord is onbekend.' }
          : problem);
      },
    });
  }

  loadOrganizations(): void {
    this.loadingOrganizations.set(true);
    this.organizationsProblem.set(null);
    this.organizations
      .load()
      .pipe(finalize(() => this.loadingOrganizations.set(false)))
      .subscribe({
        next: items => {
          // Beheer vereist geen organisatie: een beheerder gaat direct door naar de gevraagde beheerroute.
          const adminRoute = this.auth.isAdmin() && (this.pendingRoute() ?? '').startsWith('/admin');
          if (items.length > 0 || adminRoute) this.continue();
        },
        error: (problem: Problem) => this.organizationsProblem.set(problem),
      });
  }

  submitOrganization(): void {
    this.orgSubmitted.set(true);
    this.orgProblem.set(null);
    if (this.organizationForm.invalid || this.orgBusy()) {
      this.organizationForm.markAllAsTouched();
      return;
    }
    this.orgBusy.set(true);
    const input = this.organizationForm.getRawValue();
    const key = this.organizationKey.for(OrganizationService.body(input));
    this.organizations
      .create(input, key)
      .pipe(finalize(() => this.orgBusy.set(false)))
      .subscribe({
        next: () => {
          this.organizationKey.reset();
          this.continue();
        },
        error: (problem: Problem) => {
          applyServerErrors(this.organizationForm as FormGroup, problem);
          this.orgProblem.set(problem);
        },
      });
  }

  continue(): void {
    void this.router.navigateByUrl(this.pendingRoute() ?? '/scans');
  }
}
