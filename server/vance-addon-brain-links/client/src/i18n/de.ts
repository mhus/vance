/** German messages of the links addon surface. Keys mirror {@code ./en}. */
export default {
  links: {
    common: {
      cancel: 'Abbrechen',
      save: 'Speichern',
      create: 'Anlegen',
      close: 'Schließen',
    },
    app: {
      pastePlaceholder: 'Link einfügen — oder mehrere, einen pro Zeile',
      groupPlaceholder: 'Gruppe (optional)',
      add: 'Hinzufügen',
      newGroup: 'Neue Gruppe',
      newGroupButton: '+ Gruppe',
      rebuildTip: 'Die Linkliste in _index.md neu erzeugen',
      captureTip: 'Capture-Zugriff — Tokens für Browser-Erweiterungen und Skripte',
      backToList: 'Zurück zur kuratierten Liste',
      readPile: 'Der Reihe nach durchgehen, nach Datum',
      list: '☰ Liste',
      stack: '▤ Stapel {count}',
      newestFirst: 'Neueste zuerst',
      oldestFirst: 'Älteste zuerst',
      newest: '↓ Neueste',
      oldest: '↑ Älteste',
      showSeen: 'Gesehene zeigen',
      filterPlaceholder: 'Filter…',
      all: 'Alle {count}',
      ungrouped: 'Ohne Gruppe {count}',
      noGroup: 'ohne Gruppe',
      renameGroupTip: 'Diese Gruppe umbenennen oder auflösen',
      emptyHeadline: 'Noch keine Links',
      emptyBody:
        'Oben eine URL einfügen. Der Titel kommt von der Seite; Teaser und Bild werden '
        + 'live von ihr gelesen — ein Link ist also komplett, sobald er drin ist.',
      nothingMatchesHeadline: 'Keine Treffer',
      nothingMatchesBody: 'Kein Link in dieser Liste passt zum Filter.',
      pileDoneHeadline: 'Nichts mehr zu lesen',
      pileDoneBody:
        'Alle Links hier sind als gesehen markiert. „Gesehene zeigen“ einschalten, um '
        + 'zurückzublättern, oder in die Liste wechseln, um zu kuratieren.',
      clearFilter: 'Filter aufheben',
      filterByTag: 'Nach {tag} filtern',
      putBack: 'Zurück auf den Stapel',
      markSeen: 'Als gesehen markieren',
      copied: 'Kopiert',
      copyLink: 'Link kopieren',
      actions: 'Aktionen',
      share: 'Teilen…',
      edit: 'Bearbeiten…',
      refreshPreview: 'Vorschau erneuern',
      remove: 'Entfernen',
      seenOn: 'gesehen {date}',
      confirmRemove: '„{label}“ aus dieser Liste entfernen?',
      promptNewGroup: 'Neue Gruppe:',
      promptRenameGroup: '„{group}“ umbenennen (leer löst die Gruppe auf):',
      clipboardUnavailable: 'Die Zwischenablage ist in diesem Browser-Kontext nicht verfügbar.',
    },
    edit: {
      title: 'Link bearbeiten',
      fieldTitle: 'Titel',
      titlePlaceholder: 'der eigene Titel der Seite',
      titleHelp: 'Leer holt den Titel erneut von der Seite.',
      teaser: 'Teaser',
      teaserPlaceholder: 'die Seite hat keine Beschreibung',
      teaserHelpLive: 'Leer zeigt die Beschreibung der Seite (der graue Text oben) und hält sie aktuell.',
      teaserHelpNone: 'Die Seite bietet keine Beschreibung — hier eine schreiben gibt der Karte einen Untertitel.',
      group: 'Gruppe',
      groupPlaceholder: '(keine Gruppe)',
      groupHelp: 'Ein Name, den es noch nicht gibt, wird eine neue Gruppe.',
      tags: 'Tags',
      tagsPlaceholder: 'Tag hinzufügen…',
      note: 'Notiz',
      notePlaceholder: 'warum dieser Link in der Liste ist',
      noteHelp: 'Deine Anmerkung. Der Teaser beschreibt die Seite, das hier warum du sie behalten hast.',
      image: 'Bild-URL',
      imagePlaceholder: 'die Seite bietet kein Bild',
      imageHelp: 'Leer nutzt das Vorschaubild der Seite.',
    },
    capture: {
      title: 'Capture-Zugriff',
      intro:
        'Ein Capture-Token lässt ein Werkzeug von außen — eine Browser-Erweiterung, ein '
        + 'Shell-Alias — ohne Login arbeiten. Auswählen, was es darf; jede Fähigkeit öffnet '
        + 'genau die daneben genannten Routen und nichts sonst. Link-Capture etwa kann eine '
        + 'URL auflösen, die Gruppennamen lesen und speichern — es kann diese Liste nicht '
        + 'lesen, keinen Eintrag ändern und keinen löschen.',
      scopePre: 'Das Token ist auf Projekt',
      scopePost:
        'reist als Ziel mit, nicht als Grenze — ein Werkzeug, das ihn ändert, erreicht eine '
        + 'andere Linkliste im selben Projekt.',
      scopeFolder: 'Der Ordner',
      existing: 'Tokens für dieses Projekt',
      none: 'Noch keine.',
      noCapability: 'keine Fähigkeit',
      created: 'erstellt {date}',
      expires: 'läuft ab {date}',
      lastUsed: 'zuletzt genutzt {date}',
      never: 'nie',
      revoked: 'widerrufen {date}',
      revoke: 'Widerrufen',
      newToken: 'Neues Token',
      label: 'Bezeichnung',
      labelPlaceholder: 'Browser-Erweiterung',
      defaultLabel: 'Browser-Erweiterung',
      days: 'Tage',
      liveTokens: '{n} aktives Token bereits — ein neues ersetzt es nicht.'
        + ' | {n} aktive Tokens bereits — ein neues ersetzt sie nicht.',
      connectionString: 'Verbindungs-String',
      copiedButton: '✓ Kopiert',
      copyButton: '⧉ Kopieren',
      copiedHint:
        'In die Zwischenablage kopiert. Er wird einmal gezeigt und nicht gespeichert — '
        + 'vor dem nächsten Kopieren einfügen.',
      onceHint:
        'Wird einmal gezeigt. Der Server behält keine Kopie — bei Verlust dieses Token '
        + 'widerrufen und ein neues anlegen.',
      blobHint:
        'Enthält Brain-URL, Tenant, Projekt, Ordner, das Token und eine Prüfsumme. '
        + 'Als einen Wert in die Erweiterung einfügen.',
      error: {
        days: 'Die Laufzeit muss eine Anzahl von Tagen sein.',
        capability: 'Mindestens eine Fähigkeit auswählen.',
        clipboard: 'Zwischenablage nicht erreichbar — den Text markieren und manuell kopieren.',
      },
      confirmRevoke: '„{label}“ widerrufen? Was es noch nutzt, hört innerhalb von Sekunden auf zu funktionieren.',
    },
  },
};
