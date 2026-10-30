import { describe, it, expect } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import { FormArray, FormControl, FormGroup } from '@angular/forms';
import { applyServerErrors, toProblem } from './problem';

describe('toProblem', () => {
  it('normalises a ProblemDetails body including field errors', () => {
    const error = new HttpErrorResponse({
      status: 422,
      error: {
        type: 'urn:problem:validation',
        title: 'Ongeldige invoer',
        status: 422,
        detail: 'Controleer de velden.',
        instance: '/v1/scans',
        errors: [{ field: 'assessmentPeriod', message: 'Ongeldige periode' }],
      },
    });
    expect(toProblem(error)).toEqual({
      type: 'urn:problem:validation',
      title: 'Ongeldige invoer',
      status: 422,
      detail: 'Controleer de velden.',
      instance: '/v1/scans',
      errors: [{ field: 'assessmentPeriod', message: 'Ongeldige periode' }],
    });
  });

  it('falls back to Dutch titles for network errors and non-JSON bodies', () => {
    const offline = toProblem(new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') }), 'Laden mislukt');
    expect(offline.title).toBe('Geen verbinding met de server');
    expect(offline.errors).toEqual([]);

    const html = toProblem(new HttpErrorResponse({ status: 418, error: '<html>oops</html>' }), 'Laden mislukt');
    expect(html.title).toBe('Laden mislukt');
    expect(html.status).toBe(418);
  });

  it('parses a JSON string body and ignores malformed field errors', () => {
    const problem = toProblem(new HttpErrorResponse({ status: 409, error: JSON.stringify({ title: 'Conflict', errors: ['x', { field: 'version' }] }) }));
    expect(problem.title).toBe('Conflict');
    expect(problem.errors).toEqual([{ field: 'version', message: 'Ongeldige waarde.' }]);
  });

  it('returns an already normalised problem unchanged', () => {
    const problem = { type: 'about:blank', title: 'X', status: 400, errors: [] };
    expect(toProblem(problem)).toBe(problem);
  });
});

describe('applyServerErrors', () => {
  it('maps server field errors onto matching controls and returns the rest', () => {
    const form = new FormGroup({ name: new FormControl('a'), assessmentPeriod: new FormControl('b') });
    const rest = applyServerErrors(form, {
      type: 'about:blank',
      title: 'Ongeldig',
      status: 422,
      errors: [
        { field: 'assessmentPeriod', message: 'Gebruik bijv. Q2 2026' },
        { field: 'goals[0].name', message: 'Naam ontbreekt' },
        { field: 'onbekend', message: 'Iets anders' },
      ],
    });
    expect(form.controls.assessmentPeriod.errors).toEqual({ server: 'Gebruik bijv. Q2 2026' });
    expect(form.controls.assessmentPeriod.touched).toBe(true);
    expect(form.controls.name.errors).toEqual({ server: 'Naam ontbreekt' });
    expect(rest).toEqual([{ field: 'onbekend', message: 'Iets anders' }]);
  });
  it('maps indexed paths to the exact array control and indexed evidence to its alias', () => {
    const goals = new FormArray([new FormGroup({ name: new FormControl('a') }), new FormGroup({ name: new FormControl('b') })]);
    const evidence = new FormControl('');
    const form = new FormGroup({ goals, evidence });
    expect(applyServerErrors(form, { type: 'about:blank', title: 'Ongeldig', status: 400, errors: [
      { field: 'goals[1].name', message: 'Doelnaam ongeldig' },
      { field: 'evidenceReferences[0]', message: 'Bewijs ongeldig' },
    ] }, { evidenceReferences: 'evidence' })).toEqual([]);
    expect(goals.at(0).controls.name.errors).toBeNull();
    expect(goals.at(1).controls.name.errors?.['server']).toBe('Doelnaam ongeldig');
    expect(evidence.errors?.['server']).toBe('Bewijs ongeldig');
  });
});
