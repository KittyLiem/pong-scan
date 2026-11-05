/** Contracttypes voor accounttoegang (`/v1/auth/**`, `/v1/me`). */
export type MemberRole = 'member' | 'admin';
export type MemberPlan = 'full' | 'quickscan';

/** Functies die op deze installatie beschikbaar zijn. */
export interface Features {
  quickscan: boolean;
  /** Handmatige opvolging van quickscan-accounts (alleen voor beheerders, tot de automatische e-mailprocedure draait). */
  quickscanFollowup: boolean;
}

export interface Member {
  id: string;
  accountIdentifier: string;
  role: MemberRole;
  plan: MemberPlan;
  features: Features;
  /** Akkoord met de voorwaarden en het privacybeleid (alleen quickscan-accounts). */
  termsAcceptedAt: string | null;
  termsVersion: string | null;
  /** Optionele toestemming voor marketing per e-mail. */
  marketingConsent: boolean;
  marketingConsentAt: string | null;
}

export interface Session {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  desiredRoute?: string | null;
  member: Member;
}
