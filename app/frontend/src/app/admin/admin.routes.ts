import { Routes } from '@angular/router';
import { AdminComponent } from './admin.component';

export const ADMIN_ROUTES: Routes = [
  { path: '', component: AdminComponent, title: 'Beheer scaninhoud · Pong data maturity scan' },
  {
    path: 'quickscan-opvolging',
    loadComponent: () => import('./quickscan-followup/quickscan-followup.component').then(m => m.QuickscanFollowupComponent),
    title: 'Quickscan-opvolging · Pong data maturity scan',
  },
];
