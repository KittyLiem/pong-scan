import { ChangeDetectionStrategy, Component, computed, effect, input, signal } from '@angular/core';
import { AbstractControl, ValidationErrors } from '@angular/forms';

/** Toon een fout pas als het veld is aangeraakt of het formulier is verzonden. */
export function showFieldError(control: AbstractControl | null | undefined, submitted = false): boolean {
  return !!control && control.invalid && (control.touched || submitted);
}

/** Nederlandstalige melding voor de eerste validatiefout van een control. */
export function fieldErrorMessage(errors: ValidationErrors | null | undefined, patternMessage?: string): string | null {
  if (!errors) return null;
  if (typeof errors['server'] === 'string') return errors['server'];
  if (errors['required']) return 'Dit veld is verplicht.';
  if (errors['email']) return 'Vul een geldig e-mailadres in.';
  if (errors['minlength']) return `Gebruik minimaal ${errors['minlength'].requiredLength} tekens.`;
  if (errors['passwordTooLong']) return 'Een wachtwoord mag maximaal 72 bytes zijn.';
  if (errors['maxlength']) return `Gebruik maximaal ${errors['maxlength'].requiredLength} tekens.`;
  if (errors['pattern']) return patternMessage ?? 'De invoer heeft niet het juiste formaat.';
  if (errors['min']) return `Vul een waarde van minimaal ${errors['min'].min} in.`;
  if (errors['max']) return `Vul een waarde van maximaal ${errors['max'].max} in.`;
  if (errors['integer']) return 'Vul een geheel getal in.';
  if (typeof errors['message'] === 'string') return errors['message'];
  return 'Controleer dit veld.';
}

/**
 * Herbruikbare veldfout. Koppel het veld met `aria-describedby` aan `fieldId`.
 *
 * `<app-field-error fieldId="name-error" [control]="form.controls.name" [submitted]="submitted()" />`
 *
 * OnPush: de component volgt `control.events` (waarde, status, touched) via een signal.
 */
@Component({
  selector: 'app-field-error',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <p [id]="fieldId()" class="mt-1 text-sm font-medium text-error-text-emphasis" role="alert">{{ message() }}</p>
    }
  `,
})
export class FieldErrorComponent {
  readonly control = input.required<AbstractControl | null>();
  readonly submitted = input(false);
  readonly fieldId = input<string | null>(null);
  readonly patternMessage = input<string | undefined>(undefined);

  private readonly revision = signal(0);

  readonly visible = computed(() => {
    this.revision();
    return showFieldError(this.control(), this.submitted());
  });

  readonly message = computed(() => {
    this.revision();
    return fieldErrorMessage(this.control()?.errors, this.patternMessage());
  });

  constructor() {
    effect(onCleanup => {
      const control = this.control();
      if (!control) return;
      const subscription = control.events.subscribe(() => this.revision.update(value => value + 1));
      onCleanup(() => subscription.unsubscribe());
    });
  }
}
