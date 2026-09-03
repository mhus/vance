/**
 * English messages of the simple-auth addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  simpleauth: {
    title: 'Permissions',
    subtitle: 'Grant and revoke roles for users and teams.',
    scope: 'Scope',
    project: 'Project',
    load: 'Load',
    currentGrants: 'Current grants',
    noGrants: 'No grants on this scope.',
    revoke: 'Revoke',
    grantHeading: 'Grant a role',
    subjectType: 'Subject type',
    subjectId: 'User / team name',
    role: 'Role',
    grant: 'Grant',
    required: 'Scope and subject are required.',
    granted: 'Granted {role} to {subject} “{name}”.',
    scopeType: {
      TENANT: 'Tenant',
      PROJECT: 'Project',
    },
    subject: {
      USER: 'User',
      TEAM: 'Team',
    },
    roles: {
      READER: 'Reader',
      WRITER: 'Writer',
      ADMIN: 'Admin',
    },
  },
};
