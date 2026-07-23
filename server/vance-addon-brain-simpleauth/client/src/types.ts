// Wire types mirroring the Java DTOs in vance-addon-brain-simpleauth
// (PermissionGrantDto / GrantCreateRequest). Hand-maintained — the addon
// does not run the Java→TS generator.

export type GrantScopeType = 'TENANT' | 'PROJECT';
export type GrantSubjectType = 'USER' | 'TEAM';
export type GrantRole = 'READER' | 'WRITER' | 'ADMIN';

export interface GrantDto {
  id?: string | null;
  tenantId: string;
  scopeType: GrantScopeType;
  scopeId: string;
  subjectType: GrantSubjectType;
  subjectId: string;
  role: GrantRole;
  createdBy?: string | null;
  createdAtMs?: number | null;
}

export interface GrantCreateRequest {
  scopeType: GrantScopeType;
  scopeId: string;
  subjectType: GrantSubjectType;
  subjectId: string;
  role: GrantRole;
}
