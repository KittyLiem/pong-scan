/** Contracttypes voor accounttoegang (`/v1/auth/**`, `/v1/me`). */
export type MemberRole = 'member' | 'admin';
export type MemberPlan = 'full' | 'quickscan';

/** Functies die op deze installatie beschikbaar zijn. */
export interface Features {
  quickscan: boolean;
}

export interface Member {
  id: string;
  accountIdentifier: string;
  role: MemberRole;
  plan: MemberPlan;
  features: Features;
}

export interface Session {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  desiredRoute?: string | null;
  member: Member;
}
