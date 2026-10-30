import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { idempotencyHeaders } from '../http/idempotency';
import { mapProblem } from '../http/problem';
import {
  AdminDomain,
  AdminDomainCollection,
  AdminDomainInput,
  AdminOption,
  AdminOptionInput,
  AdminQuestion,
  AdminQuestionInput,
} from '../model/admin';

const enc = encodeURIComponent;

/** Beheer van kennisgebieden, vragen en antwoordopties (`/v1/admin/**`). Geen verwijderacties. */
@Injectable({ providedIn: 'root' })
export class AdminContentService {
  private readonly http = inject(HttpClient);

  listDomains(cursor?: string | null, pageSize = 20): Observable<AdminDomainCollection> {
    let params = new HttpParams().set('pageSize', pageSize);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<AdminDomainCollection>('/v1/admin/domains', { params }).pipe(mapProblem('Kennisgebieden laden mislukt'));
  }

  createDomain(input: AdminDomainInput, idempotencyKey?: string): Observable<AdminDomain> {
    return this.http.post<AdminDomain>('/v1/admin/domains', input, { headers: idempotencyHeaders(idempotencyKey) }).pipe(mapProblem('Kennisgebied opslaan mislukt'));
  }

  updateDomain(id: string, input: AdminDomainInput): Observable<AdminDomain> {
    return this.http.patch<AdminDomain>(`/v1/admin/domains/${enc(id)}`, input).pipe(mapProblem('Kennisgebied opslaan mislukt'));
  }

  createQuestion(domainId: string, input: AdminQuestionInput, idempotencyKey?: string): Observable<AdminQuestion> {
    return this.http
      .post<AdminQuestion>(`/v1/admin/domains/${enc(domainId)}/questions`, input, { headers: idempotencyHeaders(idempotencyKey) })
      .pipe(mapProblem('Vraag opslaan mislukt'));
  }

  updateQuestion(id: string, input: AdminQuestionInput): Observable<AdminQuestion> {
    return this.http.patch<AdminQuestion>(`/v1/admin/questions/${enc(id)}`, input).pipe(mapProblem('Vraag opslaan mislukt'));
  }

  createOption(questionId: string, input: AdminOptionInput, idempotencyKey?: string): Observable<AdminOption> {
    return this.http
      .post<AdminOption>(`/v1/admin/questions/${enc(questionId)}/options`, input, { headers: idempotencyHeaders(idempotencyKey) })
      .pipe(mapProblem('Antwoordoptie opslaan mislukt'));
  }

  updateOption(id: string, input: AdminOptionInput): Observable<AdminOption> {
    return this.http.patch<AdminOption>(`/v1/admin/options/${enc(id)}`, input).pipe(mapProblem('Antwoordoptie opslaan mislukt'));
  }
}
