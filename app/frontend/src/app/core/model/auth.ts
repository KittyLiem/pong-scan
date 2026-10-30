/** Contracttypes voor accounttoegang (`/v1/auth/**`, `/v1/me`). */
export type MemberRole = 'member' | 'admin';

export interface Member {
  id: string;
  accountIdentifier: string;
  role: MemberRole;
}

export interface Session {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  desiredRoute?: string | null;
  member: Member;
}
