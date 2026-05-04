// Deutsche Übersetzung. Fehlende Keys fallen automatisch auf en.ts
// zurück — ein neuer Schlüssel muss nicht sofort in jeder Sprache
// existieren, sondern wird einfach English angezeigt bis er
// übersetzt ist.

export default {
  common: {
    save: 'Speichern',
    saving: 'Speichern…',
    saved: 'Gespeichert.',
    cancel: 'Abbrechen',
    loading: 'Laden…',
    signOut: 'Abmelden',
    signIn: 'Anmelden',
    profile: 'Profil',
    home: 'Startseite',
    backToHome: 'Zurück zur Startseite',
  },

  login: {
    autoLoginNotice: 'Sie wurden eingeloggt',
    invalidCredentials: 'Ungültige Anmeldedaten.',
    loginFailed: 'Anmeldung fehlgeschlagen.',
    loginFailedWithStatus: 'Anmeldung fehlgeschlagen mit Status {status}.',
    autoLoginFailed:
      'Automatische Anmeldung fehlgeschlagen. Bitte melden Sie sich erneut an.',
    tenant: 'Mandant',
    username: 'Benutzername',
    password: 'Passwort',
    rememberUser: 'Benutzer merken',
  },

  index: {
    sectionTitle: 'Editoren',
    chat: {
      title: 'Chat',
      description:
        'Live-Chat mit dem Brain über WebSocket. Bestehende Session auswählen oder eine neue in einem beliebigen Projekt starten.',
    },
    documents: {
      title: 'Dokumente',
      description: 'Projektdokumente durchsuchen und bearbeiten.',
    },
    inbox: {
      title: 'Posteingang',
      description:
        'Einträge aus dem persönlichen Posteingang und dem Team-Posteingang jedes Teams lesen, beantworten, archivieren und delegieren.',
    },
    scopes: {
      title: 'Bereiche',
      description:
        'Mandant, Projektgruppen und Projekte verwalten. Einstellungen auf Mandanten- oder Projektebene bearbeiten.',
    },
    tools: {
      title: 'Server-Tools',
      description:
        'Server-seitige Tools pro Projekt konfigurieren. Projekt oder _vance für mandantenweite Vorgaben wählen.',
    },
    insights: {
      title: 'Insights',
      description:
        'Sessions, Think-Processes, Chat-Verlauf, Memory und Marvin-Bäume inspizieren. Nur-Lesen-Diagnoseansicht.',
    },
    users: {
      title: 'Benutzer & Teams',
      description:
        'Mandanten-Benutzer verwalten (anlegen, Passwort-Reset, Status) und Teams (Mitglieder, Aktiv-Flag).',
    },
    open: 'Öffnen',
  },

  profile: {
    pageTitle: 'Profil',
    loading: 'Profil wird geladen…',
    identity: {
      title: 'Identität',
      displayName: 'Anzeigename',
      displayNamePlaceholder: 'z. B. Wile E. Coyote',
      email: 'E-Mail',
      saved: 'Profil gespeichert.',
    },
    preferences: {
      title: 'Einstellungen',
      description:
        'Werden im Benutzer-Bereich gespeichert. Andere Clients (Foot, Mobile) ignorieren Keys mit dem Präfix webui.',
      language: 'Sprache',
      languageBrowserDefault: 'Browser-Vorgabe',
      languageSaved: 'Sprache aktualisiert.',
    },
    teams: {
      title: 'Teams',
      empty: 'Sie sind in diesem Mandanten in keinem Team Mitglied.',
      memberCountOne: '{count} Mitglied',
      memberCountOther: '{count} Mitglieder',
      disabled: 'deaktiviert',
      disabledTooltip: 'Team von einem Administrator deaktiviert',
    },
  },
};
