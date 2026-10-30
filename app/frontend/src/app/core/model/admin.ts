/** Contracttypes voor inhoudsbeheer (`/v1/admin/**`). */
export interface AdminOption {
  id: string;
  questionId?: string;
  score: number;
  label: string;
  description?: string | null;
  order: number;
  available: boolean;
  version: number;
}

export interface AdminQuestion {
  id: string;
  domainId: string;
  title: string;
  text: string;
  explanation?: string | null;
  order: number;
  active: boolean;
  version: number;
  options?: AdminOption[];
}

export interface AdminDomain {
  required: boolean;
  id: string;
  name: string;
  description?: string | null;
  order: number;
  active: boolean;
  version: number;
  questions?: AdminQuestion[];
}

export interface AdminDomainCollection {
  items: AdminDomain[];
  nextCursor?: string | null;
}

export interface AdminDomainInput {
  name: string;
  description?: string;
  order: number;
  active: boolean;
  version: number;
}

export interface AdminQuestionInput {
  domainId?: string;
  title: string;
  text: string;
  explanation?: string;
  order: number;
  active: boolean;
  version: number;
}

export interface AdminOptionInput {
  score: number;
  label: string;
  description?: string;
  order: number;
  available: boolean;
  version: number;
}
