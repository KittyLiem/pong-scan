import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { idempotencyHeaders } from '../http/idempotency';
import { mapProblem } from '../http/problem';
import {
  AnswerInput,
  CompletionInput,
  SavedAnswer,
  Scan,
  ScanCollection,
  ScanContent,
  ScanCreateInput,
  ScanUpdateInput,
} from '../model/scan';

const enc = encodeURIComponent;

/** Scanportfolio en beoordeling. Stateless: componenten houden hun eigen toestand bij. */
@Injectable({ providedIn: 'root' })
export class ScanService {
  private readonly http = inject(HttpClient);

  list(organizationId: string, cursor?: string | null, pageSize = 20): Observable<ScanCollection> {
    let params = new HttpParams().set('pageSize', Math.min(100, Math.max(1, pageSize)));
    if (cursor) params = params.set('cursor', cursor);
    return this.http
      .get<ScanCollection>(`/v1/organizations/${enc(organizationId)}/scans`, { params })
      .pipe(mapProblem('Scanoverzicht laden mislukt'));
  }

  create(organizationId: string, input: ScanCreateInput, idempotencyKey?: string): Observable<Scan> {
    return this.http
      .post<Scan>(`/v1/organizations/${enc(organizationId)}/scans`, input, { headers: idempotencyHeaders(idempotencyKey) })
      .pipe(mapProblem('Scan aanmaken mislukt'));
  }

  get(scanId: string): Observable<Scan> {
    return this.http.get<Scan>(`/v1/scans/${enc(scanId)}`).pipe(mapProblem('Scan laden mislukt'));
  }

  update(scanId: string, input: ScanUpdateInput): Observable<Scan> {
    return this.http.patch<Scan>(`/v1/scans/${enc(scanId)}`, input).pipe(mapProblem('Scan opslaan mislukt'));
  }

  content(scanId: string): Observable<ScanContent> {
    return this.http
      .get<ScanContent>(`/v1/scans/${enc(scanId)}/content`)
      .pipe(mapProblem('Beoordelingsinhoud laden mislukt'));
  }

  saveAnswer(scanId: string, questionId: string, input: AnswerInput): Observable<SavedAnswer> {
    return this.http
      .put<SavedAnswer>(`/v1/scans/${enc(scanId)}/answers/${enc(questionId)}`, input)
      .pipe(mapProblem('Antwoord opslaan mislukt'));
  }

  complete(scanId: string, input: CompletionInput, idempotencyKey?: string): Observable<Scan> {
    return this.http
      .post<Scan>(`/v1/scans/${enc(scanId)}/completion`, input, { headers: idempotencyHeaders(idempotencyKey) })
      .pipe(mapProblem('Beoordeling afronden mislukt'));
  }
}
