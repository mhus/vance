/** German messages of the simple-auth addon surface. Keys mirror {@code ./en}. */
export default {
  simpleauth: {
    title: 'Berechtigungen',
    subtitle: 'Rollen für Nutzer und Teams erteilen und entziehen.',
    scope: 'Scope',
    project: 'Projekt',
    load: 'Laden',
    currentGrants: 'Aktuelle Berechtigungen',
    noGrants: 'Keine Berechtigungen auf diesem Scope.',
    revoke: 'Entziehen',
    grantHeading: 'Rolle erteilen',
    subjectType: 'Art des Subjekts',
    subjectId: 'Nutzer- / Teamname',
    role: 'Rolle',
    grant: 'Erteilen',
    required: 'Scope und Subjekt sind erforderlich.',
    granted: '{role} an {subject} „{name}“ erteilt.',
    scopeType: {
      TENANT: 'Tenant',
      PROJECT: 'Projekt',
    },
    subject: {
      USER: 'Nutzer',
      TEAM: 'Team',
    },
    roles: {
      READER: 'Leser',
      WRITER: 'Schreiber',
      ADMIN: 'Admin',
    },
  },
};
