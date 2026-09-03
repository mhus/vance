/** German messages of the bistromath addon surface. Keys mirror {@code ./en}. */
export default {
  bistromath: {
    app: {
      fallbackTitle: 'App',
      loads: 'Lädt ({count})',
      loadsTip: '{count} Dokument(e) werden für diese App geladen',
      rebuild: 'Neu bauen',
      rebuildTip: 'Views neu lesen, Programm neu starten',
      releaseDefault: 'Ein Tenant-Admin entscheidet, welche Anwendungen hier laufen dürfen.',
      sending: 'Sendet…',
      requestRelease: 'Freigabe anfragen',
      loadOrder: 'Lädt, in dieser Reihenfolge',
      noProgram: 'Nichts — diese App hat kein Programm.',
      surfaceWithheldPre: 'Diese View verlangt eine Zeichenfläche (',
      surfaceWithheldMid: '), die dieser Tenant für diese App nicht erlaubt. Ein Tenant-Admin entscheidet das in',
      noViewHeadline: 'Noch keine View',
      noViewBody:
        'Diese App ist durch ihre Dokumente definiert. Eine View ist ein Dokument mit '
        + '`$meta.kind: app-view` — den Chat neben diesem Panel darum bitten oder eines unter {folder} anlegen.',
    },
    view: {
      fallbackTitle: 'View',
      recheck: 'Neu prüfen',
      openApp: 'Die App öffnen ↗',
      openAppTip: 'Die App öffnen, zu der diese View gehört',
      previewPre: 'Vorschau — es läuft kein Programm, alles was an',
      previewMid: 'gebunden ist bleibt leer und',
      previewPost: 'versteckt, was es gatet. Für Daten die App öffnen.',
    },
    widget: {
      selectPlaceholder: '—',
      filterPlaceholder: 'Filter…',
      tableMissingHeadline: 'Nichts zu zeigen',
      tableMissingBody:
        'Noch hat nichts `{key}` geschrieben — das Programm füllt es mit '
        + "vance.state.set('{key}', rows). Wenn das unerwartet ist, den Key prüfen.",
      tableEmptyHeadline: 'Keine Einträge',
      tableEmptyBody: '`{key}` ist leer.',
      filterEmptyHeadline: 'Kein Treffer für den Filter',
      filterEmptyBody: '{count} Zeile(n) sind durch »{filter}« verborgen.',
      sortBy: 'Nach {column} sortieren',
      formEmptyHeadline: 'Nichts ausgewählt',
      formEmptyBody: 'Eine Zeile in der Tabelle anklicken, um sie hier zu bearbeiten.',
      tab: 'Tab {number}',
      repeatEmptyHeadline: 'Noch nichts hier',
      repeatEmptyBody: 'Das Programm hat keine Liste in `{key}` gelegt.',
      embedMissingPre: 'Nichts einzubetten —',
      embedMissingPost: 'enthält keinen Dokumentpfad.',
      embedNoPath: '(kein Pfad)',
      embedNoRendererPre: 'Diese Fläche kann keine eingebetteten Dokumente rendern. Gemeint war:',
      unknownPre: 'Dieser Build kann kein',
      unknownPost: '-Widget rendern.',
    },
    details: {
      emptyHeadline: 'Nichts ausgewählt',
      emptyBody: 'Eine Zeile in der Tabelle anklicken, um sie hier zu sehen.',
      empty: '—',
      yes: 'ja',
      no: 'nein',
    },
  },
};
