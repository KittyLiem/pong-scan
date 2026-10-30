/** Contracttypes voor organisaties (`/v1/organizations`). */
export interface Organization {
  id: string;
  name: string;
  chamberOfCommerceNumber?: string | null;
  industry?: string | null;
  additionalInformation?: string | null;
  status: string;
  version: number;
}

export interface OrganizationInput {
  name: string;
  chamberOfCommerceNumber?: string;
  industry?: string;
  additionalInformation?: string;
}

export interface OrganizationCollection {
  items: Organization[];
}
