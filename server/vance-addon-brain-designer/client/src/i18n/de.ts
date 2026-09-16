/** German messages of the designer addon surface. Keys mirror {@code ./en}. */
export default {
  designer: {
    loading: 'Designs werden geladen…',
    designs: '{count} Design(s)',
    files: '{count} Datei(en)',
    create: 'Neues Design',
    createTitle: 'Neues Design',
    nameLabel: 'Name',
    namePlaceholder: 'landing-page',
    nameHelp: 'Ordnername — Buchstaben, Ziffern, Bindestriche. Wird zu <name>/index.html.',
    titleLabel: 'Titel',
    titlePlaceholder: 'Steht auf der Karte',
    descriptionLabel: 'Beschreibung',
    editMeta: 'Titel & Beschreibung bearbeiten',
    editTitle: 'Design bearbeiten',
    save: 'Speichern',
    cancel: 'Abbrechen',
    deleteDesign: 'Löschen',
    deleteTitle: 'Design löschen',
    deleteConfirm: 'Design „{name}" mit allen Dateien löschen?',
    deleteTrashNote: 'Die Dateien wandern in den Papierkorb — von dort wiederherstellbar.',
    dragHandle: 'Ziehen zum Umsortieren · Klick für Vorschau',
    theme: {
      auto: 'Wie System (Tag/Nacht)',
      light: 'Tag erzwingen (hell)',
      dark: 'Nacht erzwingen (dunkel)',
    },
    device: {
      desktop: 'Desktop-Breite',
      tablet: 'Tablet-Breite (820px)',
      phone: 'Phone-Breite (390px)',
    },
    rotate: 'Drehen (Hoch-/Querformat)',
    reload: 'Vorschau neu laden',
    refresh: 'Katalog aktualisieren',
    previewOf: 'Vorschau — {name}',
    emptyHeadline: 'Noch keine Designs',
    emptyBody:
      '„Neues Design" anlegen — oder den Chat bitten ' +
      '(„erstelle ein Design landing-page in dieser App").',
    skills: {
      button: 'Design-Skills',
      title: 'Design-Skills',
      refresh: 'Skill-Liste neu laden',
      activeBadge: 'aktiv',
      chatHint: 'Aktivierungen leben im offenen Chat — aktivieren mit /skill <name> im Composer.',
      noChatHint:
        'Öffne einen Chat, um zu sehen, welche Design-Skills dort aktiv sind — ohne Chat hat ein Skill keinen Aktivierungsstatus.',
      emptyHeadline: 'Keine Design-Skills',
      emptyBody:
        'Skills mit dem Tag „design" erscheinen hier. Mit einer style.css neben der SKILL.md zeigen sie zusätzlich eine Live-Stilvorschau.',
      previewOf: 'Stil-Vorschau — {name}',
      noStyle: 'Keine style.css — keine Vorschau',
      play: 'Im Chat aktivieren — schreibt „/skill <name>" in den Composer',
      clear: 'Im Chat deaktivieren',
      recipeBound: 'Vom Recipe gebunden — diese Aktivierung gehört der Chat-Konfiguration',
      error: {
        load: 'Design-Skills konnten nicht geladen werden: {message}',
        clear: 'Der Skill konnte nicht deaktiviert werden: {message}',
      },
    },
    error: {
      load: 'Designs konnten nicht geladen werden: {message}',
      mint: 'Vorschau-Sitzung konnte nicht geöffnet werden: {message}',
      mutate: 'Die Änderung ist fehlgeschlagen: {message}',
    },
  },
};
