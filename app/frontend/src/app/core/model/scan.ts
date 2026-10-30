/** Contracttypes voor scans, beoordelingsinhoud en antwoorden (`/v1/scans/**`). */
export type ScanStatus = 'concept' | 'in-progress' | 'content-complete' | 'completed';
export type GoalPriority = 'high' | 'medium' | 'low';

export interface OrganizationGoal {
  name: string;
  priority: GoalPriority;
  customText?: string | null;
}

export interface Scan {
  id: string;
  organizationId: string;
  name: string;
  assessmentPeriod: string;
  status: ScanStatus;
  progress: number;
  revision: number;
  revisionOf?: string | null;
  selectedDomainIds: string[];
  version: number;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  generalComments?: string | null;
  goals: OrganizationGoal[];
}

export interface ScanCollection {
  items: Scan[];
  nextCursor?: string | null;
}

export interface ScanCreateInput {
  name: string;
  assessmentPeriod: string;
  initialSelectedDomainIds?: string[];
}

export interface ScanUpdateInput {
  version: number;
  name?: string;
  assessmentPeriod?: string;
  selectedDomainIds?: string[];
  goals?: OrganizationGoal[];
}

export interface AnswerOption {
  id: string;
  label: string;
  description?: string | null;
  score: number;
  order: number;
}

export interface Answer {
  optionId: string;
  explanation?: string | null;
  evidenceReferences?: string[] | null;
  version: number;
}

export interface ContentQuestion {
  id: string;
  title: string;
  text: string;
  explanation?: string | null;
  order: number;
  options: AnswerOption[];
  answer: Answer | null;
}

export interface ContentDomain {
  id: string;
  name: string;
  description?: string | null;
  order: number;
  selected: boolean;
  questionCount: number;
  answeredCount: number;
  questions: ContentQuestion[];
}

export interface ScanContent {
  scanId: string;
  status: ScanStatus;
  editable: boolean;
  domains: ContentDomain[];
}

export interface AnswerInput {
  optionId: string;
  explanation?: string;
  evidenceReferences?: string[];
  version: number;
}

export interface SavedAnswer {
  questionId: string;
  optionId: string;
  explanation?: string | null;
  evidenceReferences?: string[] | null;
  version: number;
  progress: number;
  scanStatus: ScanStatus;
  /** Versie van de scan na het opslaan; nodig voor optimistische locking van PATCH en afronden. */
  scanVersion: number;
}

export interface CompletionInput {
  version: number;
  generalComments?: string;
}

export type RevisionMode = 'copy' | 'new-revision';
