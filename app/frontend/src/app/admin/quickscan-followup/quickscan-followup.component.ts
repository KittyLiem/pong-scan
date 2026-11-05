import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AdminQuickscanService } from '../../core/admin/admin-quickscan.service';
import { Problem } from '../../core/http/problem';
import { QuickscanAccount, QuickscanFollowupStatus } from '../../core/model/admin-quickscan';
import { STATUS_BADGE_CLASSES, formatDate, formatDateTime, statusLabel } from '../../shared/labels';

/**
 * Beheerders zien quickscan-accounts met marketingtoestemming en mailen ieder account één keer zelf
 * en leggen dat hier vast. Verdwijnt zodra de automatische e-mailprocedure draait.
 */
@Component({
  selector: 'app-quickscan-followup',
  imports: [RouterLink],
  templateUrl: './quickscan-followup.component.html',
})
export class QuickscanFollowupComponent {
  private readonly api = inject(AdminQuickscanService);

  readonly status = signal<QuickscanFollowupStatus>('pending');
  readonly accounts = signal<QuickscanAccount[]>([]);
  readonly nextCursor = signal<string | null>(null);
  /** Aantal quickscan-accounts dat niet benaderd wil worden; null tot de eerste pagina is geladen. */
  readonly declinedCount = signal<number | null>(null);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly loadProblem = signal<Problem | null>(null);

  /** Account waarvoor de beheerder het vastleggen nog moet bevestigen. */
  readonly confirming = signal<string | null>(null);
  readonly marking = signal<string | null>(null);
  readonly markProblem = signal<{ memberId: string; problem: Problem } | null>(null);
  readonly notice = signal<string | null>(null);

  readonly statusLabel = statusLabel;
  readonly statusBadge = STATUS_BADGE_CLASSES;
  readonly formatDate = formatDate;
  readonly formatDateTime = formatDateTime;

  constructor() {
    this.load();
  }

  show(status: QuickscanFollowupStatus): void {
    if (this.status() === status) return;
    this.status.set(status);
    this.notice.set(null);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loaded.set(false);
    this.loadProblem.set(null);
    this.confirming.set(null);
    this.markProblem.set(null);
    this.api
      .list(this.status())
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => {
          this.accounts.set(page.items);
          this.nextCursor.set(page.nextCursor);
          this.declinedCount.set(page.declinedCount);
          this.loaded.set(true);
        },
        error: (problem: Problem) => this.loadProblem.set(problem),
      });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (!cursor || this.loading()) return;
    this.loading.set(true);
    this.api
      .list(this.status(), cursor)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => {
          this.accounts.update(items => [...items, ...page.items]);
          this.nextCursor.set(page.nextCursor);
          this.declinedCount.set(page.declinedCount);
        },
        error: (problem: Problem) => this.loadProblem.set(problem),
      });
  }

  askConfirm(account: QuickscanAccount): void {
    this.markProblem.set(null);
    this.confirming.set(account.memberId);
  }

  cancelConfirm(): void {
    this.confirming.set(null);
  }

  markContacted(account: QuickscanAccount): void {
    if (this.marking()) return;
    this.marking.set(account.memberId);
    this.markProblem.set(null);
    this.api
      .markContacted(account.memberId)
      .pipe(finalize(() => this.marking.set(null)))
      .subscribe({
        next: () => {
          this.confirming.set(null);
          this.accounts.update(items => items.filter(item => item.memberId !== account.memberId));
          this.notice.set(`${account.accountIdentifier} is vastgelegd als gemaild.`);
        },
        error: (problem: Problem) => {
          this.confirming.set(null);
          // Al benaderd (bijvoorbeeld door een andere beheerder): de rij hoort niet meer bij 'Te benaderen'.
          if (problem.status === 409) {
            this.accounts.update(items => items.filter(item => item.memberId !== account.memberId));
            this.notice.set(`${account.accountIdentifier} was al benaderd.`);
            return;
          }
          this.markProblem.set({ memberId: account.memberId, problem });
        },
      });
  }
}
