import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TenantRole } from '@vance/generated';

let current: { tenantRole: TenantRole; settings: Record<string, string> } | null = null;
vi.mock('./me', () => ({
  getMe: () => current,
  meSetting: (key: string) => current?.settings[key] ?? null,
}));

import { getActiveUiLevel, meetsTenantRole } from './webUiSession';

describe('meetsTenantRole', () => {
  beforeEach(() => {
    current = null;
  });

  it('lets everyone through when no role is required', () => {
    expect(meetsTenantRole(undefined)).toBe(true);
  });

  it('hides a gated entry while the role is still unknown', () => {
    expect(meetsTenantRole(TenantRole.ADMIN)).toBe(false);
  });

  it('follows the rank order of the tenant roles', () => {
    current = { tenantRole: TenantRole.CREATOR, settings: {} };
    expect(meetsTenantRole(TenantRole.USER)).toBe(true);
    expect(meetsTenantRole(TenantRole.CREATOR)).toBe(true);
    expect(meetsTenantRole(TenantRole.ADMIN)).toBe(false);
    current = { tenantRole: TenantRole.TENANT_ADMIN, settings: {} };
    expect(meetsTenantRole(TenantRole.ADMIN)).toBe(true);
  });
});

describe('getActiveUiLevel', () => {
  it('reads the retired admin level as expert', () => {
    current = { tenantRole: TenantRole.USER, settings: { 'webui.uiLevel': 'admin' } };
    expect(getActiveUiLevel()).toBe('expert');
  });

  it('defaults to standard', () => {
    current = { tenantRole: TenantRole.USER, settings: {} };
    expect(getActiveUiLevel()).toBe('standard');
  });
});
