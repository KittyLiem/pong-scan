import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormGroup } from '@angular/forms';
import { MonoTypeOperatorFunction, catchError, throwError } from 'rxjs';

export interface FieldProblem {
  field: string;
  message: string;
}

/** Genormaliseerde RFC 9457 ProblemDetails zoals de frontend die overal gebruikt. */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  errors: FieldProblem[];
}

const STATUS_TITLES: Record<number, string> = {
  0: 'Geen verbinding met de server',
  400: 'Ongeldige aanvraag',
  401: 'Aanmelden vereist',
  403: 'Geen toegang',
  404: 'Niet gevonden',
  409: 'Gegevens zijn intussen gewijzigd',
  413: 'Aanvraag te groot',
  422: 'Gegevens zijn niet geldig',
  429: 'Te veel aanvragen',
  500: 'Er ging iets mis op de server',
  502: 'Server tijdelijk niet bereikbaar',
  503: 'Server tijdelijk niet beschikbaar',
};

const STATUS_DETAILS: Record<number, string> = {
  0: 'Controleer je internetverbinding en probeer het opnieuw.',
  401: 'Meld je opnieuw aan om verder te gaan.',
  403: 'Je hebt geen rechten voor deze actie.',
  409: 'Herlaad de gegevens en probeer het opnieuw.',
  500: 'Probeer het later opnieuw.',
  502: 'Probeer het later opnieuw.',
  503: 'Probeer het later opnieuw.',
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

function fieldErrors(value: unknown): FieldProblem[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter(isRecord)
    .map(item => ({ field: str(item['field']) ?? '', message: str(item['message']) ?? 'Ongeldige waarde.' }));
}

/**
 * Zet iedere fout (HttpErrorResponse, ProblemDetails-body, al genormaliseerd Problem of iets onverwachts)
 * om naar één `Problem` met een Nederlandstalige titel.
 */
export function toProblem(error: unknown, fallbackTitle = 'Aanvraag mislukt'): Problem {
  if (isProblem(error)) return error;
  let status = 0;
  let body: unknown = null;
  if (error instanceof HttpErrorResponse) {
    status = error.status;
    body = error.error;
    if (typeof body === 'string') {
      try {
        body = JSON.parse(body);
      } catch {
        body = null;
      }
    }
  } else if (isRecord(error)) {
    body = error;
    status = typeof error['status'] === 'number' ? error['status'] : 0;
  }
  const candidate = isRecord(body) ? body : {};
  const resolvedStatus = typeof candidate['status'] === 'number' ? candidate['status'] : status;
  return {
    type: str(candidate['type']) ?? 'about:blank',
    title: str(candidate['title']) ?? STATUS_TITLES[resolvedStatus] ?? fallbackTitle,
    status: resolvedStatus,
    detail: str(candidate['detail']) ?? STATUS_DETAILS[resolvedStatus],
    instance: str(candidate['instance']),
    errors: fieldErrors(candidate['errors']),
  };
}

export function isProblem(value: unknown): value is Problem {
  return isRecord(value)
    && typeof value['title'] === 'string'
    && typeof value['status'] === 'number'
    && Array.isArray(value['errors'])
    && !(value instanceof HttpErrorResponse);
}

/** RxJS-operator: iedere fout wordt een genormaliseerd `Problem`. */
export function mapProblem<T>(fallbackTitle: string): MonoTypeOperatorFunction<T> {
  return catchError((error: unknown) => throwError(() => toProblem(error, fallbackTitle)));
}

/**
 * Koppelt server-veldfouten (`errors[].field`) aan formuliercontrols met dezelfde naam (ook het laatste
 * geïndexeerde paden zoals `goals[0].name`). Aliassen kunnen ook een hele array naar één tekstveld leiden
 * (bijvoorbeeld `evidenceReferences[0]` → `evidence`). Het laatste padsegment blijft een fallback.
 * Geeft de fouten terug die niet gekoppeld konden worden.
 */
export function applyServerErrors(form: FormGroup, problem: Problem, aliases: Record<string, string> = {}): FieldProblem[] {
  const unmatched: FieldProblem[] = [];
  for (const error of problem.errors) {
    const path = error.field.replace(/\[(\d+)\]/g, '.$1');
    const root = path.split('.')[0];
    const candidates = [aliases[error.field], path, aliases[root], error.field.split('.').pop() ?? ''].filter(Boolean) as string[];
    const control: AbstractControl | null = candidates.map(name => form.get(aliases[name] ?? name)).find(Boolean) ?? null;
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: error.message });
      control.markAsTouched();
    } else {
      unmatched.push(error);
    }
  }
  return unmatched;
}
