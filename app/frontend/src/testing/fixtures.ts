import { ChangeDetectorRef } from '@angular/core';
import { ComponentFixture } from '@angular/core/testing';
import { Member, Session } from '../app/core/model/auth';
import { Organization } from '../app/core/model/organization';
import { ContentDomain, ContentQuestion, Scan, ScanContent } from '../app/core/model/scan';

/** Forceert een render, ook voor OnPush-componenten waarvan alleen formulierstatus is gewijzigd. */
export function render<T>(fixture: ComponentFixture<T>): void {
  fixture.componentRef.injector.get(ChangeDetectorRef).markForCheck();
  fixture.detectChanges();
}

export function text<T>(fixture: ComponentFixture<T>): string {
  return (fixture.nativeElement as HTMLElement).textContent?.replace(/\s+/g, ' ') ?? '';
}

export const member: Member = { id: 'm1', accountIdentifier: 'demo@pong.example', role: 'member' };
export const admin: Member = { id: 'm2', accountIdentifier: 'admin@pong.example', role: 'admin' };

export function session(overrides: Partial<Session> = {}): Session {
  return {
    accessToken: 'token-123',
    tokenType: 'Bearer',
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    desiredRoute: null,
    member,
    ...overrides,
  };
}

export const organization: Organization = { id: 'o1', name: 'Pong Consulting', status: 'active', version: 1 };

export function scan(overrides: Partial<Scan> = {}): Scan {
  return {
    id: 's1',
    organizationId: 'o1',
    name: 'Nulmeting 2026',
    assessmentPeriod: 'Q2 2026',
    status: 'concept',
    progress: 0,
    revision: 1,
    revisionOf: null,
    selectedDomainIds: [],
    version: 1,
    createdAt: '2026-04-01T10:00:00Z',
    updatedAt: '2026-04-02T10:00:00Z',
    completedAt: null,
    generalComments: null,
    goals: [],
    ...overrides,
  };
}

export function question(id: string, overrides: Partial<ContentQuestion> = {}): ContentQuestion {
  return {
    id,
    title: `Vraag ${id}`,
    text: `Tekst van ${id}`,
    explanation: null,
    order: 1,
    options: [
      { id: `${id}-o0`, label: 'Niet ingericht', description: 'Geen aanpak', score: 0, order: 0 },
      { id: `${id}-o3`, label: 'Gedefinieerd', description: 'Vastgelegd', score: 3, order: 3 },
    ],
    answer: null,
    ...overrides,
  };
}

export function domain(id: string, overrides: Partial<ContentDomain> = {}): ContentDomain {
  const questions = overrides.questions ?? [question(`${id}-q1`)];
  return {
    id,
    name: `Domein ${id}`,
    description: `Beschrijving ${id}`,
    order: 1,
    selected: false,
    questionCount: questions.length,
    answeredCount: 0,
    questions,
    ...overrides,
  };
}

export function content(domains: ContentDomain[], overrides: Partial<ScanContent> = {}): ScanContent {
  return { scanId: 's1', status: 'concept', editable: true, domains, ...overrides };
}
