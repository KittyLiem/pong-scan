import { describe, expect, it } from 'vitest';
import { SubmissionKey, idempotencyHeaders } from './idempotency';

describe('SubmissionKey', () => {
  it('reuses the key for an unchanged payload and renews it on change or reset', () => {
    const keys = new SubmissionKey();
    const first = keys.for({ name: 'Scan', period: '2026' });
    expect(keys.for({ name: 'Scan', period: '2026' })).toBe(first);
    const changed = keys.for({ name: 'Scan 2', period: '2026' });
    expect(changed).not.toBe(first);
    keys.reset();
    expect(keys.for({ name: 'Scan 2', period: '2026' })).not.toBe(changed);
  });

  it('builds a header only when a key is given', () => {
    expect(idempotencyHeaders('abc')).toEqual({ 'X-Idempotency-Key': 'abc' });
    expect(idempotencyHeaders()).toEqual({});
  });
});
