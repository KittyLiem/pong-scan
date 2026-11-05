/** Contracttypes voor de handmatige opvolging van quickscan-accounts (`/v1/admin/quickscan-accounts/**`). */
import { ScanStatus } from './scan';

export type QuickscanFollowupStatus = 'pending' | 'contacted';

export interface QuickscanAccountScan {
  scanId: string;
  status: ScanStatus;
  progress: number;
  createdAt: string;
  completedAt: string | null;
}

export interface QuickscanAccountContact {
  channel: 'manual' | 'automatic';
  status: 'queued' | 'sent' | 'failed' | 'bounced';
  contactedAt: string;
  /** E-mailadres van de beheerder; leeg bij automatische verzending. */
  contactedBy: string | null;
}

export interface QuickscanAccount {
  memberId: string;
  /** E-mailadres van het account. */
  accountIdentifier: string;
  registeredAt: string;
  organizationName: string | null;
  quickscan: QuickscanAccountScan | null;
  marketingConsent: boolean;
  marketingConsentAt: string | null;
  marketingConsentVersion: string | null;
  marketingConsentWithdrawnAt: string | null;
  contact: QuickscanAccountContact | null;
}

export interface QuickscanAccountCollection {
  items: QuickscanAccount[];
  nextCursor: string | null;
  /** Quickscan-accounts met een quickscan die niet benaderd willen worden (geen of ingetrokken toestemming). */
  declinedCount: number;
}
