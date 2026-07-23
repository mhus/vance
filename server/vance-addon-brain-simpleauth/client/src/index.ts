// Barrel for the simpleauth addon's client surface.

export { default as PermissionsArea } from './PermissionsArea.vue';
export {
  listGrants,
  setGrant,
  removeGrant,
} from './api';
export type { GrantDto, GrantScopeType, GrantSubjectType, GrantRole } from './types';
