import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Problem } from '../core/http/problem';
import { QuickscanService } from '../core/quickscan/quickscan.service';

/**
 * Opent de quickscan (of maakt hem aan) en stuurt door: een open scan naar de beoordeling, een afgeronde naar de
 * resultaten. Deze pagina blijft niet in de geschiedenis staan.
 */
@Component({
  selector: 'app-quickscan-start',
  templateUrl: './quickscan-start.component.html',
})
export class QuickscanStartComponent {
  private readonly quickscan = inject(QuickscanService);
  private readonly router = inject(Router);

  readonly problem = signal<Problem | null>(null);

  constructor() {
    this.open();
  }

  open(): void {
    this.problem.set(null);
    this.quickscan.open().subscribe({
      next: scan => {
        const target = scan.status === 'completed' ? 'results' : 'assessment';
        void this.router.navigate(['/scans', scan.id, target], { replaceUrl: true });
      },
      error: (problem: Problem) => {
        // Nog geen organisatie: eerst het organisatieformulier, daarna terug hierheen.
        if (problem.status === 409) {
          void this.router.navigate(['/start'], { queryParams: { returnUrl: '/quickscan/start' }, replaceUrl: true });
          return;
        }
        this.problem.set(problem);
      },
    });
  }
}
