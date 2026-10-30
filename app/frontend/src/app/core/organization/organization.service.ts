import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { idempotencyHeaders } from '../http/idempotency';
import { mapProblem } from '../http/problem';
import { Organization, OrganizationCollection, OrganizationInput } from '../model/organization';

interface OrganizationState {
  memberId: string | null;
  items: Organization[];
}

/** Organisatiecontext van de aangemelde gebruiker; hersteld via `GET /v1/organizations`. */
@Injectable({ providedIn: 'root' })
export class OrganizationService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly state = signal<OrganizationState | null>(null);

  /** Alleen geldig voor het lid waarvoor ze geladen zijn (voorkomt lekken na wisselen van account). */
  readonly organizations = computed(() => {
    const state = this.state();
    const memberId = this.auth.member()?.id ?? null;
    return state && this.auth.isAuthenticated() && state.memberId === memberId ? state.items : [];
  });
  readonly current = computed<Organization | null>(() => this.organizations()[0] ?? null);
  readonly hasOrganization = computed(() => this.current() !== null);

  load(): Observable<Organization[]> {
    return this.http.get<OrganizationCollection>('/v1/organizations').pipe(
      map(collection => collection.items ?? []),
      tap(items => this.state.set({ memberId: this.auth.member()?.id ?? null, items })),
      mapProblem('Organisaties laden mislukt'),
    );
  }

  /** Bouwt de request-body zoals die naar de server gaat (ook bruikbaar voor idempotency-vergelijking). */
  static body(input: OrganizationInput): OrganizationInput {
    const body: OrganizationInput = { name: input.name.trim() };
    if (input.chamberOfCommerceNumber?.trim()) body.chamberOfCommerceNumber = input.chamberOfCommerceNumber.trim();
    if (input.industry?.trim()) body.industry = input.industry.trim();
    if (input.additionalInformation?.trim()) body.additionalInformation = input.additionalInformation.trim();
    return body;
  }

  create(input: OrganizationInput, idempotencyKey?: string): Observable<Organization> {
    const body = OrganizationService.body(input);
    return this.http.post<Organization>('/v1/organizations', body, { headers: idempotencyHeaders(idempotencyKey) }).pipe(
      tap(organization => this.state.update(state => ({
        memberId: this.auth.member()?.id ?? null,
        items: [organization, ...(state?.items ?? []).filter(item => item.id !== organization.id)],
      }))),
      mapProblem('Organisatie vastleggen mislukt'),
    );
  }
}
