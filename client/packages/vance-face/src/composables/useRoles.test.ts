import { describe, expect, it } from 'vitest';
import { TenantRole } from '@vance/generated';
import { TENANT_ROLES, tenantRoleAtLeast } from './useRoles';

describe('tenantRoleAtLeast', () => {
  it('follows the declaration order of the Java enum', () => {
    expect(TENANT_ROLES).toEqual([
      TenantRole.USER,
      TenantRole.CREATOR,
      TenantRole.ADMIN,
      TenantRole.TENANT_ADMIN,
    ]);
  });

  it('compares by rank', () => {
    expect(tenantRoleAtLeast(TenantRole.ADMIN, TenantRole.CREATOR)).toBe(true);
    expect(tenantRoleAtLeast(TenantRole.ADMIN, TenantRole.ADMIN)).toBe(true);
    expect(tenantRoleAtLeast(TenantRole.CREATOR, TenantRole.ADMIN)).toBe(false);
  });

  it('treats an unknown role as no role', () => {
    expect(tenantRoleAtLeast(null, TenantRole.USER)).toBe(false);
    expect(tenantRoleAtLeast(undefined, TenantRole.USER)).toBe(false);
  });
});
