// English translations — the master locale.
//
// Every key that exists here MUST exist in every other locale file
// or it falls through to this one (which is the configured
// `fallbackLocale`). New strings always land here first.
//
// Hierarchy mirrors the editor / component the string belongs to.
// Shared atoms live under `common.*`; per-editor blocks under
// the editor name. Add a sub-block when a section grows past ~5
// keys.

export default {
  common: {
    save: 'Save',
    saving: 'Saving…',
    saved: 'Saved.',
    cancel: 'Cancel',
    loading: 'Loading…',
    signOut: 'Sign out',
    signIn: 'Sign in',
    profile: 'Profile',
    home: 'Home',
    backToHome: 'Back to home',
  },

  login: {
    autoLoginNotice: 'You have been signed in',
    invalidCredentials: 'Invalid credentials.',
    loginFailed: 'Login failed.',
    loginFailedWithStatus: 'Login failed with status {status}.',
    autoLoginFailed: 'Auto-login failed. Please sign in again.',
    tenant: 'Tenant',
    username: 'Username',
    password: 'Password',
    rememberUser: 'Remember user',
  },

  index: {
    sectionTitle: 'Editors',
    chat: {
      title: 'Chat',
      description:
        'Live chat with the brain over WebSocket. Pick an existing session or start a new one in any project.',
    },
    documents: {
      title: 'Documents',
      description: 'Browse and edit project documents.',
    },
    inbox: {
      title: 'Inbox',
      description:
        "Read items from your personal inbox and the team-inbox of every team you're in. Reply, archive, delegate.",
    },
    scopes: {
      title: 'Scopes',
      description:
        'Manage the tenant, project groups and projects. Edit settings at tenant or project scope.',
    },
    tools: {
      title: 'Server Tools',
      description:
        'Configure server-side tools per project. Pick a project or _vance for tenant-wide defaults.',
    },
    insights: {
      title: 'Insights',
      description:
        'Inspect sessions, think-processes, chat history, memory and Marvin trees. Read-only diagnostic view.',
    },
    users: {
      title: 'Users & Teams',
      description:
        'Manage tenant users (create, password reset, status) and teams (members, enabled flag).',
    },
    open: 'Open',
  },

  profile: {
    pageTitle: 'Profile',
    loading: 'Loading profile…',
    identity: {
      title: 'Identity',
      displayName: 'Display name',
      displayNamePlaceholder: 'e.g. Wile E. Coyote',
      email: 'Email',
      saved: 'Profile saved.',
    },
    preferences: {
      title: 'Preferences',
      description:
        'Saved on your user-scope. Other clients (foot, mobile) ignore keys with the webui. prefix.',
      language: 'Language',
      languageBrowserDefault: 'Browser default',
      languageSaved: 'Language updated.',
    },
    teams: {
      title: 'Teams',
      empty: "You're not a member of any team in this tenant.",
      memberCountOne: '{count} member',
      memberCountOther: '{count} members',
      disabled: 'disabled',
      disabledTooltip: 'Team disabled by an administrator',
    },
  },
};
