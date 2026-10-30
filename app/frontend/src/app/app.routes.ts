import { Routes } from '@angular/router';
import { AccountAccessComponent } from './account-access/account-access.component';
import { adminGuard } from './core/auth/admin.guard';
import { authenticatedGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  { path: 'start', component: AccountAccessComponent, title: 'Aanmelden en organisatie starten · Pong data maturity scan' },
  {
    path: 'scans',
    canActivate: [authenticatedGuard],
    loadComponent: () => import('./scan-overview/scan-overview.component').then(m => m.ScanOverviewComponent),
    title: 'Scanoverzicht · Pong data maturity scan',
  },
  {
    path: 'scans/:scanId/assessment',
    canActivate: [authenticatedGuard],
    loadComponent: () => import('./maturity-assessment/maturity-assessment.component').then(m => m.MaturityAssessmentComponent),
    title: 'Volwassenheidsbeoordeling · Pong data maturity scan',
  },
  {
    path: 'scans/:scanId/results',
    canActivate: [authenticatedGuard],
    loadComponent: () => import('./scan-results/scan-results.component').then(m => m.ScanResultsComponent),
    title: 'Scanresultaten en ontwikkelprioriteiten · Pong data maturity scan',
  },
  {
    path: 'admin',
    canActivate: [adminGuard],
    loadChildren: () => import('./admin/admin.routes').then(m => m.ADMIN_ROUTES),
  },
  { path: '', pathMatch: 'full', redirectTo: 'scans' },
  { path: '**', redirectTo: 'scans' },
];
