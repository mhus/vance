/** German messages of the kit-store addon surface. Keys mirror {@code ./en}. */
export default {
  store: {
    common: {
      cancel: 'Abbrechen',
      save: 'Speichern',
      apply: 'Übernehmen',
      confirm: 'Bestätigen',
      refresh: 'Aktualisieren',
      create: 'Anlegen',
      hide: 'Ausblenden',
      pdf: 'PDF',
      free: 'Kostenlos',
      send: 'Senden',
      reason: 'Grund',
    },
    mode: {
      STORE: 'Store',
      DEVELOPER: 'Entwickler',
      OPERATOR: 'Betreiber',
      MONEY: 'Geld',
    },
    tab: {
      ALL: 'Alle',
      OFFERED: 'Angeboten',
      OWNED: 'Gekauft',
      INSTALLED: 'Installiert',
      UPDATABLE: 'Aktualisierbar',
    },
    state: {
      OFFERED: 'ANGEBOTEN',
      OWNED: 'GEKAUFT',
      INSTALLED: 'INSTALLIERT',
      UPDATABLE: 'AKTUALISIERBAR',
    },
    area: {
      noLibraryHeadline: 'Keine Bibliothek konfiguriert',
      noLibraryBody:
        'Eine Bibliotheks-Quelle in _vance/config/kit-sources.yaml des _tenant-Projekts ergänzen.',
      notReachable: 'Gerade nicht verfügbar — siehe den Store-Tab im Profil.',
      signInToSee: 'Im Profil an diesem Store anmelden, um zu sehen, was du besitzt.',
      ownershipUnknown:
        'Konnte nicht prüfen, was du schon besitzt — Kaufen ist aus, bis dieser Store wieder '
        + 'antwortet. Alles andere funktioniert weiter.',
      searchPlaceholder: 'Kits suchen',
      nothingMatchesHeadline: 'Keine Treffer',
      nothingHereHeadline: 'Nichts hier',
      nothingMatchesBody: 'Kein Kit in dieser Liste passt zu „{query}“.',
      nothingHereBody: 'Noch nichts in dieser Liste.',
      vendorProved: 'Der Anbieter hat nachgewiesen, dass er {domain} kontrolliert',
      containsTag: 'Enthält {tag}',
      installedVersion: '(installiert {version})',
      notRated: 'Noch nicht bewertet',
      reviews: 'Bewertungen',
      hideReviews: 'Bewertungen ausblenden',
      storeEmail: 'Store-E-Mail',
      storePassword: 'Store-Passwort',
      licenceDays: 'Updates für {days} Tage. Was installiert ist, läuft danach weiter.',
      licenceNoLimit: 'Updates ohne Zeitlimit.',
      country: 'Land',
      countryHelp:
        'Dieser Store verkauft in der EU. Anderswo heißt: sich dort erst steuerlich registrieren.',
      vatId: 'USt-IdNr. (optional)',
      vatIdHelp: 'Für gewerbliche Käufer. Wird so erfasst, wie angegeben.',
      withdrawalConsent:
        'Ich verlange, dass der Download sofort beginnt, und weiß, dass ich damit mein '
        + 'Widerrufsrecht verliere.',
      confirmPrice: 'Bestätigen — {price}',
      allVersions: 'alle Versionen',
      noReviews: 'Noch keine Bewertungen.',
      noReviewsForMajor: 'Keine Bewertungen für {major}.x.',
      reviewPlaceholder: 'Optional — ein Text wartet auf Prüfung.',
      sendReview: 'Bewertung senden',
      signInToReview: 'An diesem Store anmelden, um zu bewerten.',
      starsAria: '{count} Sterne',
      buyBlocked:
        'Der Store konnte nicht sagen, was du schon besitzt — jetzt kaufen könnte doppelt zahlen.',
      actionInstall: 'Installieren',
      actionUpdate: 'Aktualisieren',
      actionBuy: 'Kaufen',
      actionGet: 'Holen',
      expiryLapsed: 'Lizenz endete am {date} — installierte Kits laufen weiter',
      expiryUntil: 'Updates bis {date}',
      done: 'Fertig — {name} gehört dir.',
      thanksRating: 'Danke — deine Bewertung zählt jetzt.',
      thanksRatingText: 'Danke — deine Bewertung zählt jetzt, dein Text wartet auf Prüfung.',
      paymentContinue:
        'Die Zahlung im gerade geöffneten Fenster abschließen. Diese Seite aktualisiert sich '
        + 'selbst, sobald sie durch ist.',
      error: {
        load: 'Der Store konnte nicht geladen werden.',
        install: 'Dieses Kit konnte nicht installiert werden.',
        reviews: 'Die Bewertungen konnten nicht geladen werden.',
        sendReview: 'Die Bewertung konnte nicht gesendet werden.',
        terms: 'Die Store-Bedingungen konnten nicht geladen werden.',
        order: 'Die Bestellung konnte nicht abgeschlossen werden.',
        paymentLink: 'Der Store antwortete mit einem Zahlungslink, den dieser Browser nicht öffnet.',
      },
    },
    profile: {
      noStoreHeadline: 'Kein Store konfiguriert',
      noStoreBody:
        'Eine Bibliotheks-Quelle in _vance/config/kit-sources.yaml des _tenant-Projekts ergänzen.',
      signedInAs: 'Angemeldet als',
      notSignedIn: 'Nicht angemeldet',
      signOut: 'Abmelden',
      signIn: 'Anmelden',
      becomeDeveloper: 'Entwickler werden',
      unreachable: 'Dieser Store war nicht erreichbar: {problem}',
      receipts: 'Belege',
      nothingBought: 'Hier noch nichts gekauft.',
      email: 'E-Mail',
      password: 'Passwort',
      brainName: 'Name für dieses Brain',
      brainNameHelp:
        'Wird in deiner Geräteliste im Store gezeigt, damit du deine Maschinen unterscheiden kannst.',
      applyEmailHelp: 'Die Bewerbung meldet erneut an, und das Brain hält kein Passwort.',
      vendorHandle: 'Anbieter-Handle',
      vendorHandleHelp:
        'Kleinbuchstaben, und Teil jeder Kit-Koordinate. Es darf keine Zugehörigkeit behaupten, '
        + 'die du nicht hast — das ist das Einzige, was ein Mensch prüft.',
      displayName: 'Anzeigename',
      passwordAgainHelp:
        'Bedingungen zu akzeptieren ist eine Entscheidung eines Menschen, darum wird erneut gefragt.',
      terms: 'Anbieter-Bedingungen (Version {version})',
      acceptTerms: 'Ich akzeptiere diese Anbieter-Bedingungen.',
      role: {
        developer: 'Entwickler',
        operator: 'Betreiber',
        buyer: 'Käufer',
      },
      signedInNotice: 'An {store} als {account} angemeldet.',
      signedOutNotice:
        'Hier abgemeldet. Die Verknüpfung ist im Store weiterhin unter seinen Geräten gelistet — '
        + 'dort entfernen, wenn sie weg soll.',
      appliedNotice: 'Beworben. Kits kannst du jetzt vorbereiten; das Veröffentlichen wartet auf den Store.',
      credentialsNeeded: 'Store-E-Mail und Passwort sind nötig — die Bewerbung meldet erneut an.',
      error: {
        connections: 'Die Store-Verbindungen konnten nicht gelesen werden.',
        signIn: 'Anmelden fehlgeschlagen.',
        signOut: 'Abmelden fehlgeschlagen.',
        terms: 'Die Anbieter-Bedingungen konnten nicht gelesen werden.',
        apply: 'Bewerbung fehlgeschlagen.',
        receipts: 'Die Belege konnten nicht gelesen werden.',
        receipt: 'Der Beleg konnte nicht erzeugt werden.',
      },
    },
    operator: {
      actingAs: 'Handelt als Store-Konto dieser Installation.',
      vendorsWaiting: 'Wartende Anbieter',
      vendorsHint:
        'Entschieden wird hier das Handle: ob der Name eine Zugehörigkeit behauptet, die der '
        + 'Bewerber nicht hat. Alles andere am Antrag ist schon geprüft.',
      nothingWaiting: 'Nichts in der Warteschlange',
      noVendorApplications: 'Keine Anbieter-Bewerbungen.',
      approve: 'Freigeben',
      refuse: 'Ablehnen',
      releasesWaiting: 'Wartende Releases',
      noReleases: 'Keine eingereichten Releases.',
      publish: 'Veröffentlichen',
      submitted: 'eingereicht',
      terms: 'Bedingungen {version}',
      done: 'Fertig.',
      error: {
        queue: 'Die Warteschlange konnte nicht geöffnet werden.',
        decision: 'Diese Entscheidung konnte nicht angewendet werden.',
      },
    },
    money: {
      owed: 'Anbietern geschuldet',
      owedHint:
        'Geld wartet die Frist ab, in der ein Käufer zurücktreten kann — ein frischer Verkauf '
        + 'ist deshalb noch nicht hier.',
      nothingToPayHeadline: 'Nichts zu zahlen',
      nothingToPayBody: 'Noch kein Anbieter hat etwas verdient.',
      sales: '{count} Verkauf/Verkäufe',
      earned: 'verdient {amount}',
      refunds: 'Rückerstattungen {amount}',
      disputed: 'strittig {amount}',
      pay: 'Auszahlen',
      openPayouts: 'Offene Auszahlungen',
      openPayoutsHint:
        'An den Zahlungsweg übergeben und noch nicht bestätigt — angenommen ist nicht angekommen '
        + '— sowie die fehlgeschlagenen, die eine Entscheidung brauchen.',
      askRail: 'Zahlungsweg fragen',
      nothingOutstandingHeadline: 'Nichts offen',
      nothingOutstandingBody: 'Jede Auszahlung ist angekommen.',
      release: 'Freigeben',
      refundsHeading: 'Rückerstattungen',
      refundsHint:
        'Drei Dinge drehen sich: das Geld, die Berechtigung und der Anbieteranteil. Bei einer '
        + 'Rückbuchung ist das Geld schon zurück — dann sagen, und nur der Rest passiert.',
      nothingToRefundHeadline: 'Nichts zu erstatten',
      nothingToRefundBody: 'Kein abgeschlossener Verkauf.',
      refund: 'Erstatten',
      refundReasonHelp: 'Geht in die Akte, nicht an den Käufer.',
      chargeback: 'Das Geld ist schon beim Käufer (eine Rückbuchung).',
      confirmRefund: 'Erstattung bestätigen',
      classification: 'Braucht Einordnung',
      classificationHint:
        'Verkäufe und Belege, die der Store keiner Steuerregel zuordnen konnte. Im Bericht '
        + 'stehen sie als Anzahl; hier lassen sie sich klären. Ein Verkauf mit schon '
        + 'geschriebenem Beleg ist nicht änderbar — das braucht eine Korrektur, ein eigenes Dokument.',
      noCountry: 'kein Land erfasst',
      classify: 'Einordnen',
      buyerCountry: 'Land des Käufers',
      buyerCountryHelp: 'Zwei Buchstaben, z.B. DE.',
      vatId: 'USt-IdNr.',
      vatIdHelp: 'Nur für gewerbliche Käufer. Leer heißt Verbraucher.',
      rateDerived:
        'Der Satz wird nicht eingegeben — er folgt aus dem Land, nach denselben Regeln, die der '
        + 'Verkauf selbst genutzt hätte.',
      noteNeedsVendor: 'braucht Land und USt-IdNr. des Anbieters',
      writeAgain: 'Neu schreiben',
      tax: 'Steuer',
      taxHint:
        'Gezählt aus dem, was jeder Verkauf am Tag erfasst hat. Drei Abschnitte, weil sie in '
        + 'drei verschiedene Meldungen gehen.',
      from: 'Von',
      to: 'Bis (exklusiv)',
      dateHelp: 'YYYY-MM-DD',
      build: 'Erstellen',
      section: {
        domestic: 'Inland — reguläre Meldung',
        oss: 'Andere Mitgliedstaaten — OSS-Meldung',
        reverse: 'Reverse Charge — zusammenfassende Meldung',
        refunded: 'In diesem Zeitraum erstattet',
      },
      net: 'netto {amount}',
      taxLine: 'Steuer {amount}',
      taxTotal: 'Steuer gesamt {amount}',
      unclear: '{count} Verkauf/Verkäufe ohne Einordnung',
      payoutFailed: '{payout} fehlgeschlagen: {reason}',
      noReasonGiven: 'kein Grund angegeben',
      payoutStatus: '{payout}: {amount} {status}.',
      released: '{payout} freigegeben — seine Verkäufe sind wieder fällig.',
      reconciled:
        'Nach {asked} gefragt: {arrived} angekommen, {failed} fehlgeschlagen, {open} noch offen.',
      refunded: '{order} erstattet — Berechtigung {entitlement}, Anbieteranteil {share}.',
      entitlementRevoked: 'entzogen',
      entitlementGone: 'war schon weg',
      shareClawedBack: 'von der nächsten Auszahlung zurückgehalten',
      shareNeverPaid: 'nie gezahlt',
      classified: '{order} ist jetzt {treatment}.',
      reissued: '{number} geschrieben — der alte wurde vollständig storniert.',
      error: {
        load: 'Die Geld-Ansicht konnte nicht geladen werden.',
        report: 'Der Bericht konnte nicht erstellt werden.',
        render: 'Der Bericht konnte nicht erzeugt werden.',
        generic: 'Das hat nicht funktioniert.',
      },
    },
    developer: {
      feesHeading: 'Was dieser Store behält',
      fees:
        '{percent} % je Verkauf, mindestens {minimum} — bei einem kostenlosen Kit gar nichts. '
        + 'Kleinster berechenbarer Preis: {floor}.',
      vendor: 'Anbieter',
      applyButton: 'Bewerben',
      changeDomain: 'Domain ändern',
      proveDomain: 'Domain nachweisen',
      domain: 'Domain',
      domainHelp:
        'Nur der Name, z.B. example.com — das Abzeichen neben dem Anzeigenamen. Dein Handle '
        + 'ändert sich nicht.',
      claim: 'Beanspruchen',
      checkNow: 'Jetzt prüfen',
      txtHint: 'Das als TXT-Record bei {domain} veröffentlichen, dann prüfen:',
      notVendorHeadline: 'Noch kein Anbieter',
      notVendorBody:
        'Bewirb dich, um hier Kits zu veröffentlichen. Die Bewerbung allein erteilt nichts — '
        + 'Kits kannst du sofort vorbereiten, das Veröffentlichen wartet auf den Store.',
      storeEmail: 'Store-E-Mail',
      storePassword: 'Store-Passwort',
      passwordHelp: 'Einmal genutzt und verworfen, genau wie beim Anmelden.',
      vendorHandle: 'Anbieter-Handle',
      vendorHandleHelp:
        'Kleinbuchstaben, und Teil jeder Kit-Koordinate. Es darf keine Zugehörigkeit behaupten, '
        + 'die du nicht hast — das ist das Einzige, was ein Mensch prüft.',
      displayName: 'Anzeigename',
      homepage: 'Homepage (optional)',
      terms: 'Anbieter-Bedingungen (Version {version})',
      acceptTerms: 'Ich akzeptiere diese Anbieter-Bedingungen.',
      publishing: 'Veröffentlichen',
      publishingHint:
        'Einmal im Jahr erneuert, pro Handle. Ohne: keine neuen Kits und keine Auszahlung — '
        + 'kostenlose Kits bleiben im Katalog und können weiter Versionen bekommen.',
      renew: 'Erneuern — {price}',
      renewPasswordHelp: 'Einmal genutzt und verworfen, genau wie beim Kauf eines Kits.',
      renewCountry: 'Land',
      renewCountryHelp: 'Wo dein Geschäft sitzt. Es entscheidet die Steuer auf dem Beleg.',
      renewVatId: 'USt-IdNr. (optional)',
      renewVatIdHelp:
        'Eine Nummer aus dem eigenen Land verlagert die Umsatzsteuer auf dich (Reverse Charge).',
      yourMoney: 'Dein Geld',
      yourMoneyHint:
        'Auszahlung pro Handle. Ein Verkauf wartet die Käufer-Frist ab, bevor sein Anteil gehen kann.',
      payoutAccount: 'Auszahlungskonto',
      change: 'Ändern',
      paypalAddress: 'PayPal-Adresse',
      paypalHelp: 'Dieser Store zahlt über PayPal aus.',
      accountHolder: 'Kontoinhaber (optional)',
      payoutCountry: 'Land',
      payoutCountryHelp: 'Entscheidet, wie die eigene Rechnung des Stores für deine Arbeit besteuert wird.',
      payoutVatId: 'USt-IdNr. (optional)',
      creditNotes: 'Gutschriften',
      corrects: 'korrigiert {number}',
      myKits: 'Meine Kits',
      newKit: 'Neues Kit',
      published: 'veröffentlicht {version}',
      publishProject: 'Dieses Projekt veröffentlichen',
      projectToExport: 'Zu exportierendes Projekt',
      version: 'Version',
      versionHelp:
        'Eine veröffentlichte Version wird nie überschrieben. Korrekturen vor der Freigabe nutzen '
        + 'sie erneut; was schon live ist, braucht eine neue.',
      vaultPassword: 'Vault-Passwort (nur bei verschlüsselten Settings)',
      exportHint:
        'Das exportiert das gewählte Projekt als Kit und lädt es hoch. Das Projekt muss eine '
        + 'Kit-Quelle sein, also ein Authoring-Manifest tragen.',
      exportSubmit: 'Exportieren und einreichen',
      kitId: 'Kit-Id',
      kitIdHelp: 'Teil der Adresse. Sie kann später nicht geändert werden.',
      kitDescription: 'Wofür dieses Kit ist.',
      topics: 'Themen',
      topicsHelp:
        'Wofür dieses Kit ist — kommagetrennt, z.B. security, onboarding. Was es enthält, wird '
        + 'aus dem Release gelesen; das taggst du nicht.',
      price: 'Preis',
      priceHelp: '0 ist kostenlos. Ein Store ohne Zahlungsanbieter nimmt nur kostenlose Kits.',
      submissions: 'Einreichungen',
      rounds: '{count} Runden',
      notSignedInHeadline: 'Nicht angemeldet',
      notSignedInBody:
        'Zuerst im Store-Tab an diesem Store anmelden. Die Bedingungen und die Gebühren oben '
        + 'sind auch ohne das lesbar.',
      status: {
        pending: 'wartet auf den Store',
        rejected: 'abgelehnt: {reason}',
        approved: 'freigegeben',
      },
      standing: {
        valid: 'darf veröffentlichen bis {until} — {days} Tag(e) übrig',
        grace: 'endete am {until} — Kulanzfrist, vor {days} Tag(en)',
        expired: 'endete am {until}',
        never: 'nie erneuert',
      },
      domainVerified: '{domain} ist bestätigt.',
      domainPending: 'Den TXT-Record veröffentlichen, dann prüfen.',
      accountSaved: 'Auszahlungskonto gespeichert.',
      mayPublishAgain: '{vendor} darf wieder veröffentlichen.',
      paymentContinue: 'Die Zahlung im gerade geöffneten Fenster abschließen.',
      appliedNotice:
        'Beworben. Kits kannst du jetzt vorbereiten; das Veröffentlichen wartet auf den Store.',
      kitCreated: '{name} ist im Katalog. Als nächstes eine Version veröffentlichen.',
      releaseSubmitted: '{kit} {version} eingereicht — wartet darauf, dass der Store es ansieht.',
      error: {
        note: 'Die Gutschrift konnte nicht erzeugt werden.',
        createKit: 'Das Kit konnte nicht angelegt werden.',
        generic: 'Das hat nicht funktioniert.',
        money: 'Deine Auszahlungen konnten nicht geladen werden.',
        account: 'Das Auszahlungskonto konnte nicht gespeichert werden.',
        renew: 'Das Veröffentlichungsrecht konnte nicht erneuert werden.',
        load: 'Die Entwickler-Ansicht konnte nicht geladen werden.',
        apply: 'Bewerbung fehlgeschlagen.',
        paymentLink: 'Der Store antwortete mit einem Zahlungslink, den dieser Browser nicht öffnet.',
      },
    },
  },
};
