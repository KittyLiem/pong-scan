/** Unieke sleutel voor `X-Idempotency-Key`. */
export function idempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Eén idempotency-sleutel per logische inzending van een aanmaakformulier.
 *
 * - Een herhaalde poging met dezelfde payload (bv. na een verloren response) hergebruikt de sleutel,
 *   zodat de server geen duplicaat aanmaakt.
 * - Een gewijzigde payload krijgt een nieuwe sleutel.
 * - Na succes (of bij een nieuw geopend formulier) roept de component `reset()` aan.
 */
export class SubmissionKey {
  private key = idempotencyKey();
  private payload: string | null = null;

  /** Sleutel voor deze payload; vernieuwd zodra de payload afwijkt van de vorige poging. */
  for(payload: unknown): string {
    const serialized = JSON.stringify(payload ?? null);
    if (this.payload !== null && this.payload !== serialized) this.key = idempotencyKey();
    this.payload = serialized;
    return this.key;
  }

  reset(): void {
    this.key = idempotencyKey();
    this.payload = null;
  }
}

/** Headers met een expliciete idempotency-sleutel (de interceptor overschrijft die niet). */
export function idempotencyHeaders(key?: string): Record<string, string> {
  return key ? { 'X-Idempotency-Key': key } : {};
}
