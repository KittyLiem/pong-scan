import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { mapProblem } from '../http/problem';
import { Scan } from '../model/scan';

/** Kennisgebied dat in de quickscan zit (openbaar, voor de landingspagina). */
export interface QuickscanDomain {
  id: string;
  name: string;
  description?: string | null;
  order: number;
  questionCount: number;
}

/** Quickscan (`/v1/quickscan/**`): alleen op het centrale platform. */
@Injectable({ providedIn: 'root' })
export class QuickscanService {
  private readonly http = inject(HttpClient);

  domains(): Observable<QuickscanDomain[]> {
    return this.http.get<{ domains: QuickscanDomain[] }>('/v1/quickscan/domains').pipe(
      map(response => response.domains ?? []),
      mapProblem('Quickscan laden mislukt'),
    );
  }

  /** Idempotent: geeft de bestaande quickscan of maakt hem aan. */
  open(): Observable<Scan> {
    return this.http.put<Scan>('/v1/quickscan', null).pipe(mapProblem('Quickscan openen mislukt'));
  }
}
