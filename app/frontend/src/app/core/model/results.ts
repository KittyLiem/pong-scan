import { OrganizationGoal } from './scan';

/** Contracttypes voor resultaten en ontwikkelprioriteiten (`/v1/scans/{id}/results`, `/development-priorities`). */
export interface MaturityLevel {
  level: number;
  label: string;
}

export interface DomainScore {
  domainId: string;
  domainName: string;
  score: number;
  maturityLevel: MaturityLevel;
}

export type DevelopmentClassification = 'strength' | 'development-point' | 'gap';

export interface DevelopmentPoint {
  id: string;
  domainId: string;
  domainName: string;
  classification: DevelopmentClassification;
  text: string;
  recommendation?: string | null;
}

/** Aanname: impact en urgentie gebruiken dezelfde schaal als doelprioriteiten. */
export type PriorityRating = 'high' | 'medium' | 'low';

export interface DevelopmentPriority {
  developmentPointId: string;
  impact: PriorityRating;
  urgency: PriorityRating;
  selected: boolean;
}

export interface ActionOverview {
  setRevision: number;
  items: DevelopmentPriority[];
}

export interface ScanResults {
  scanId: string;
  scanName: string;
  assessmentPeriod: string;
  completedAt: string;
  totalScore: number;
  maturityLevel: MaturityLevel;
  domainScores: DomainScore[];
  developmentPoints: DevelopmentPoint[];
  goals: OrganizationGoal[];
  /** `{setRevision: 0, items: []}` vóór de eerste opslag; `null` wordt ook getolereerd. */
  priorities: ActionOverview | null;
  availablePrioritySetRevisions: number[];
}
