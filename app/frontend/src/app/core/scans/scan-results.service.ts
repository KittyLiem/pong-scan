import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { idempotencyHeaders } from '../http/idempotency';
import { mapProblem } from '../http/problem';
import { ActionOverview, DevelopmentPriority, ScanResults } from '../model/results';
import { RevisionMode, Scan } from '../model/scan';

const enc = encodeURIComponent;

/** Resultaten, ontwikkelprioriteiten, PDF-export en revisies van afgeronde scans. */
@Injectable({ providedIn: 'root' })
export class ScanResultsService {
  private readonly http = inject(HttpClient);

  results(scanId: string, prioritySetRevision?: number): Observable<ScanResults> {
    return this.http.get<ScanResults>(`/v1/scans/${enc(scanId)}/results`, { params: prioritySetRevision ? { prioritySetRevision } : {} }).pipe(mapProblem('Resultaten laden mislukt'));
  }

  savePriorities(scanId: string, items: DevelopmentPriority[]): Observable<ActionOverview> {
    return this.http
      .put<ActionOverview>(`/v1/scans/${enc(scanId)}/development-priorities`, { items })
      .pipe(mapProblem('Ontwikkelprioriteiten opslaan mislukt'));
  }

  exportPdf(scanId: string, prioritySetRevision?: number): Observable<Blob> {
    return this.http
      .get(`/v1/scans/${enc(scanId)}/export`, { params: prioritySetRevision ? { prioritySetRevision } : {}, responseType: 'blob', headers: { Accept: 'application/pdf' } })
      .pipe(mapProblem('PDF-export mislukt'));
  }

  createRevision(scanId: string, mode: RevisionMode, idempotencyKey?: string): Observable<Scan> {
    return this.http
      .post<Scan>(`/v1/scans/${enc(scanId)}/revisions`, { mode }, { headers: idempotencyHeaders(idempotencyKey) })
      .pipe(mapProblem('Nieuwe revisie maken mislukt'));
  }
}
