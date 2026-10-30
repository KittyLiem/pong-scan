import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from './core/auth/auth.service';
import { OrganizationService } from './core/organization/organization.service';

@Component({
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App {
  readonly auth = inject(AuthService);
  readonly organizations = inject(OrganizationService);
  private readonly router = inject(Router);
  readonly signingOut = signal(false);

  signOut(): void {
    if (this.signingOut()) return;
    this.signingOut.set(true);
    this.auth
      .logout()
      .pipe(finalize(() => this.signingOut.set(false)))
      .subscribe(() => void this.router.navigate(['/start']));
  }
}
