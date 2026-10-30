import { DevelopmentClassification, PriorityRating } from '../core/model/results';
import { GoalPriority, Scan, ScanStatus } from '../core/model/scan';

export const STATUS_LABELS: Record<ScanStatus, string> = {
  concept: 'Concept',
  'in-progress': 'In uitvoering',
  'content-complete': 'Inhoud compleet',
  completed: 'Afgerond',
};

export const STATUS_BADGE_CLASSES: Record<ScanStatus, string> = {
  concept: 'bg-warning-bg-subtle text-warning-text-emphasis',
  'in-progress': 'bg-info-bg-subtle text-info-text-emphasis',
  'content-complete': 'bg-primary-bg-subtle text-primary-text-emphasis',
  completed: 'bg-success-bg-subtle text-success-text-emphasis',
};

export const PRIORITY_LABELS: Record<GoalPriority | PriorityRating, string> = {
  high: 'Hoog',
  medium: 'Gemiddeld',
  low: 'Laag',
};

export const CLASSIFICATION_LABELS: Record<DevelopmentClassification, string> = {
  strength: 'Sterktes',
  'development-point': 'Ontwikkelpunten',
  gap: 'Knelpunten',
};

export function statusLabel(status: string): string {
  return STATUS_LABELS[status as ScanStatus] ?? status;
}

/** Afgeronde scans openen in de resultaten, alle andere statussen in de beoordeling. */
export function scanActionLink(scan: Pick<Scan, 'id' | 'status'>): string[] {
  return scan.status === 'completed' ? ['/scans', scan.id, 'results'] : ['/scans', scan.id, 'assessment'];
}

export function scanActionLabel(scan: Pick<Scan, 'status' | 'progress'>): string {
  if (scan.status === 'completed') return 'Resultaten bekijken';
  return scan.status === 'concept' && scan.progress === 0 ? 'Starten' : 'Hervatten';
}

const dateTime = new Intl.DateTimeFormat('nl-NL', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
const dateOnly = new Intl.DateTimeFormat('nl-NL', { day: 'numeric', month: 'long', year: 'numeric' });

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '–';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '–' : dateTime.format(date);
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '–';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '–' : dateOnly.format(date);
}

export function formatScore(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '–';
  return value.toLocaleString('nl-NL', { minimumFractionDigits: 1, maximumFractionDigits: 2 });
}

export function clampProgress(value: number | null | undefined): number {
  return Math.max(0, Math.min(100, Math.round(value ?? 0)));
}
