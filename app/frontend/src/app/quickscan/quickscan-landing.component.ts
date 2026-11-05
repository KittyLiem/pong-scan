import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { Problem } from '../core/http/problem';
import { QuickscanDomain, QuickscanService } from '../core/quickscan/quickscan.service';

/** Openbare landingspagina: wat de quickscan beoordeelt, met aanmelden of een account aanmaken. */
@Component({
  selector: 'app-quickscan-landing',
  imports: [RouterLink],
  templateUrl: './quickscan-landing.component.html',
})
export class QuickscanLandingComponent {
  private readonly quickscan = inject(QuickscanService);

  readonly domains = signal<QuickscanDomain[]>([]);
  readonly loading = signal(true);
  readonly problem = signal<Problem | null>(null);

  /**
   * Na aanmelden en het vastleggen van de organisatie gaat de gebruiker door naar de quickscan. Beide links houden
   * de quickscan-context vast, zodat ook wie na een mislukte aanmelding alsnog een account aanmaakt een
   * quickscan-account krijgt.
   */
  readonly registerParams = { entry: 'quickscan', returnUrl: '/quickscan/start' };
  readonly loginParams = { entry: 'quickscan', mode: 'login', returnUrl: '/quickscan/start' };

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.problem.set(null);
    this.quickscan
      .domains()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: domains => this.domains.set([...domains].sort((a, b) => a.order - b.order)),
        error: (problem: Problem) => this.problem.set(problem),
      });
  }
}
