// Browser localStorage keys used by the Vance Web-UI.
// All keys are prefixed `vance.` to avoid collisions with other apps on the
// same origin.

export const StorageKeys = {
  jwt: 'vance.jwt',
  tenantId: 'vance.tenantId',
  username: 'vance.username',
  activeSessionId: 'vance.activeSessionId',
} as const;

export type StorageKey = (typeof StorageKeys)[keyof typeof StorageKeys];
