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
    error: {
      load: 'Designs konnten nicht geladen werden: {message}',
      mint: 'Vorschau-Sitzung konnte nicht geöffnet werden: {message}',
      mutate: 'Die Änderung ist fehlgeschlagen: {message}',
    },
  },
};
