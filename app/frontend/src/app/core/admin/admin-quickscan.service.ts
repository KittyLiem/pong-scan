import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { mapProblem } from '../http/problem';
import { QuickscanAccount, QuickscanAccountCollection, QuickscanFollowupStatus } from '../model/admin-quickscan';

/** Handmatige opvolging van quickscan-accounts (`/v1/admin/quickscan-accounts/**`). */
@Injectable({ providedIn: 'root' })
export class AdminQuickscanService {
  private readonly http = inject(HttpClient);

  list(status: QuickscanFollowupStatus, cursor?: string | null, pageSize = 20): Observable<QuickscanAccountCollection> {
    let params = new HttpParams().set('status', status).set('pageSize', pageSize);
    if (cursor) params = params.set('cursor', cursor);
    return this.http
      .get<QuickscanAccountCollection>('/v1/admin/quickscan-accounts', { params })
      .pipe(mapProblem('Quickscan-accounts laden mislukt'));
  }

  markContacted(memberId: string): Observable<QuickscanAccount> {
    return this.http
      .post<QuickscanAccount>(`/v1/admin/quickscan-accounts/${encodeURIComponent(memberId)}/contacted`, null)
      .pipe(mapProblem('Vastleggen mislukt'));
  }
}
