/**
 * English messages of the kit-store addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 *
 * <p>Currency amounts and country codes stay unlocalised on purpose — the
 * store states them the way it invoices them.
 */
export default {
  store: {
    common: {
      cancel: 'Cancel',
      save: 'Save',
      apply: 'Apply',
      confirm: 'Confirm',
      refresh: 'Refresh',
      create: 'Create',
      hide: 'Hide',
      pdf: 'PDF',
      free: 'Free',
      send: 'Send',
      reason: 'Reason',
    },
    mode: {
      STORE: 'Store',
      DEVELOPER: 'Developer',
      OPERATOR: 'Operator',
      MONEY: 'Money',
    },
    tab: {
      ALL: 'All',
      OFFERED: 'Offered',
      OWNED: 'Purchased',
      INSTALLED: 'Installed',
      UPDATABLE: 'Updatable',
    },
    state: {
      OFFERED: 'OFFERED',
      OWNED: 'OWNED',
      INSTALLED: 'INSTALLED',
      UPDATABLE: 'UPDATABLE',
    },
    area: {
      noLibraryHeadline: 'No library configured',
      noLibraryBody: 'Add a library source in _vance/config/kit-sources.yaml of the _tenant project.',
      notReachable: 'Not available right now — see the Store tab of your profile.',
      signInToSee: 'Sign in to this store in your profile to see what you own.',
      ownershipUnknown:
        'Could not check what you already own — buying is off until this store answers again. '
        + 'Everything else still works.',
      searchPlaceholder: 'Search kits',
      nothingMatchesHeadline: 'Nothing matches',
      nothingHereHeadline: 'Nothing here',
      nothingMatchesBody: 'No kit in this list matches “{query}”.',
      nothingHereBody: 'Nothing in this list yet.',
      vendorProved: 'The vendor proved they control {domain}',
      containsTag: 'Contains {tag}',
      installedVersion: '(installed {version})',
      notRated: 'Not rated yet',
      reviews: 'Reviews',
      hideReviews: 'Hide reviews',
      storeEmail: 'Store email',
      storePassword: 'Store password',
      licenceDays: 'Updates for {days} days. What you install keeps working after that.',
      licenceNoLimit: 'Updates without a time limit.',
      country: 'Country',
      countryHelp: 'This store sells in the EU. Elsewhere means registering for tax there first.',
      vatId: 'VAT id (optional)',
      vatIdHelp: 'For a business buyer. Recorded as given.',
      withdrawalConsent:
        'I ask for the download to start immediately and I understand that I thereby lose '
        + 'my right of withdrawal.',
      confirmPrice: 'Confirm — {price}',
      allVersions: 'all versions',
      noReviews: 'No reviews yet.',
      noReviewsForMajor: 'No reviews for {major}.x.',
      reviewPlaceholder: 'Optional — a text waits for review.',
      sendReview: 'Send review',
      signInToReview: 'Sign in to this store to leave a review.',
      starsAria: '{count} stars',
      buyBlocked: 'The store could not say what you already own — buying now could pay twice.',
      actionInstall: 'Install',
      actionUpdate: 'Update',
      actionBuy: 'Buy',
      actionGet: 'Get',
      expiryLapsed: 'Licence lapsed on {date} — installed kits keep working',
      expiryUntil: 'Updates until {date}',
      done: 'Done — {name} is yours.',
      thanksRating: 'Thanks — your rating counts now.',
      thanksRatingText: 'Thanks — your rating counts now, your text is waiting to be reviewed.',
      paymentContinue:
        'Continue the payment in the window that just opened. This page updates by itself '
        + 'once it goes through.',
      error: {
        load: 'Could not load the store.',
        install: 'Could not install this kit.',
        reviews: 'Could not load the reviews.',
        sendReview: 'Could not send the review.',
        terms: 'Could not load the store terms.',
        order: 'Could not complete the order.',
        paymentLink: 'The store answered with a payment link this browser will not open.',
      },
    },
    profile: {
      noStoreHeadline: 'No store configured',
      noStoreBody: 'Add a library source in _vance/config/kit-sources.yaml of the _tenant project.',
      signedInAs: 'Signed in as',
      notSignedIn: 'Not signed in',
      signOut: 'Sign out',
      signIn: 'Sign in',
      becomeDeveloper: 'Become a developer',
      unreachable: 'This store could not be reached: {problem}',
      receipts: 'Receipts',
      nothingBought: 'Nothing bought here yet.',
      email: 'Email',
      password: 'Password',
      brainName: 'Name for this brain',
      brainNameHelp: 'Shown in your device list at the store, so you can tell your machines apart.',
      applyEmailHelp: 'Applying signs in again, and the brain holds no password.',
      vendorHandle: 'Vendor handle',
      vendorHandleHelp:
        'Lowercase, and part of every kit coordinate. It must not claim an affiliation you '
        + 'do not have — that is the one thing a person checks.',
      displayName: 'Display name',
      passwordAgainHelp: 'Accepting terms is a decision by a person, so this asks again.',
      terms: 'Vendor terms (version {version})',
      acceptTerms: 'I accept these vendor terms.',
      role: {
        developer: 'developer',
        operator: 'operator',
        buyer: 'buyer',
      },
      signedInNotice: 'Signed in to {store} as {account}.',
      signedOutNotice:
        'Signed out here. The link at the store is still listed among its devices — '
        + 'remove it there if you want it gone.',
      appliedNotice: 'Applied. You can prepare kits now; publishing waits for the store.',
      credentialsNeeded: 'Store email and password are needed — applying signs in again.',
      error: {
        connections: 'Could not read the store connections.',
        signIn: 'Could not sign in.',
        signOut: 'Could not sign out.',
        terms: 'Could not read the vendor terms.',
        apply: 'Could not apply.',
        receipts: 'Could not read the receipts.',
        receipt: 'Could not render the receipt.',
      },
    },
    operator: {
      actingAs: "Acting as this installation's store account.",
      vendorsWaiting: 'Vendors waiting',
      vendorsHint:
        'What is being decided here is the handle: whether the name claims an affiliation the '
        + 'applicant does not have. Everything else in the intake is already checked.',
      nothingWaiting: 'Nothing waiting',
      noVendorApplications: 'No vendor applications.',
      approve: 'Approve',
      refuse: 'Refuse',
      releasesWaiting: 'Releases waiting',
      noReleases: 'No submitted releases.',
      publish: 'Publish',
      submitted: 'submitted',
      terms: 'terms {version}',
      done: 'Done.',
      error: {
        queue: 'Could not open the queue.',
        decision: 'Could not apply that decision.',
      },
    },
    money: {
      owed: 'Owed to vendors',
      owedHint:
        'Money waits out the window in which a buyer can take it back, so a fresh sale is not '
        + 'here yet.',
      nothingToPayHeadline: 'Nothing to pay',
      nothingToPayBody: 'No vendor has earned anything yet.',
      sales: '{count} sale(s)',
      earned: 'earned {amount}',
      refunds: 'refunds {amount}',
      disputed: 'disputed {amount}',
      pay: 'Pay',
      openPayouts: 'Unfinished payouts',
      openPayoutsHint:
        'Handed to the rail and not yet confirmed — accepted is not arrived — and the ones that '
        + 'failed, which are the ones needing a decision.',
      askRail: 'Ask the rail',
      nothingOutstandingHeadline: 'Nothing outstanding',
      nothingOutstandingBody: 'Every payout has arrived.',
      release: 'Release',
      refundsHeading: 'Refunds',
      refundsHint:
        "Three things turn round: the money, the entitlement, and the vendor's share. A "
        + 'chargeback has already moved the money — say so, and only the rest happens.',
      nothingToRefundHeadline: 'Nothing to refund',
      nothingToRefundBody: 'No settled sale.',
      refund: 'Refund',
      refundReasonHelp: 'Goes on the record, not to the buyer.',
      chargeback: 'The money is already back with the buyer (a chargeback).',
      confirmRefund: 'Confirm refund',
      classification: 'Needs classification',
      classificationHint:
        'Sales and notes the store could not place under a tax rule. They are in the report as '
        + 'a count; here they can be resolved. A sale whose receipt is already written cannot be '
        + 'changed — that needs a correction, which is a document of its own.',
      noCountry: 'no country on record',
      classify: 'Classify',
      buyerCountry: "Buyer's country",
      buyerCountryHelp: 'Two letters, e.g. DE.',
      vatId: 'VAT id',
      vatIdHelp: 'Only for a business buyer. Empty means a consumer.',
      rateDerived:
        'The rate is not entered — it follows from the country, by the same rules the sale '
        + 'itself would have used.',
      noteNeedsVendor: "needs the vendor's country and VAT id",
      writeAgain: 'Write again',
      tax: 'Tax',
      taxHint:
        'Counted from what each sale recorded on the day. Three sections because they go in '
        + 'three different returns.',
      from: 'From',
      to: 'To (exclusive)',
      dateHelp: 'YYYY-MM-DD',
      build: 'Build',
      section: {
        domestic: 'Domestic — ordinary return',
        oss: 'Other member states — OSS return',
        reverse: 'Reverse charge — recapitulative statement',
        refunded: 'Refunded in this period',
      },
      net: 'net {amount}',
      taxLine: 'tax {amount}',
      taxTotal: 'Tax total {amount}',
      unclear: '{count} sale(s) carry no classification',
      payoutFailed: '{payout} failed: {reason}',
      noReasonGiven: 'no reason given',
      payoutStatus: '{payout}: {amount} {status}.',
      released: '{payout} released — its sales are due again.',
      reconciled: 'Asked about {asked}: {arrived} arrived, {failed} failed, {open} still open.',
      refunded:
        '{order} refunded — entitlement {entitlement}, vendor share {share}.',
      entitlementRevoked: 'revoked',
      entitlementGone: 'was already gone',
      shareClawedBack: 'held back from their next payout',
      shareNeverPaid: 'never paid',
      classified: '{order} is now {treatment}.',
      reissued: '{number} written — the old one was reversed in full.',
      error: {
        load: 'Could not load the money view.',
        report: 'Could not build the report.',
        render: 'Could not render the report.',
        generic: 'That did not work.',
      },
    },
    developer: {
      feesHeading: 'What this store keeps',
      fees:
        '{percent} % of each sale, at least {minimum} — nothing at all on a free kit. '
        + 'Smallest price that can be charged: {floor}.',
      vendor: 'Vendor',
      applyButton: 'Apply',
      changeDomain: 'Change domain',
      proveDomain: 'Prove a domain',
      domain: 'Domain',
      domainHelp:
        'Just the name, e.g. example.com — the badge next to your display name. Your handle '
        + 'does not change.',
      claim: 'Claim',
      checkNow: 'Check now',
      txtHint: 'Publish this as a TXT record at {domain}, then check:',
      notVendorHeadline: 'Not a vendor yet',
      notVendorBody:
        'Apply to publish kits here. Applying grants nothing on its own — you can prepare kits '
        + 'straight away, and publishing waits for the store.',
      storeEmail: 'Store email',
      storePassword: 'Store password',
      passwordHelp: 'Used once and discarded, exactly as when signing in.',
      vendorHandle: 'Vendor handle',
      vendorHandleHelp:
        'Lowercase, and part of every kit coordinate. It must not claim an affiliation you do '
        + 'not have — that is the one thing a person checks.',
      displayName: 'Display name',
      homepage: 'Homepage (optional)',
      terms: 'Vendor terms (version {version})',
      acceptTerms: 'I accept these vendor terms.',
      publishing: 'Publishing',
      publishingHint:
        'Renewed once a year, per handle. Without it: no new kits and nothing paid — free kits '
        + 'stay in the catalogue and may still receive versions.',
      renew: 'Renew — {price}',
      renewPasswordHelp: 'Used once and discarded, exactly as when buying a kit.',
      renewCountry: 'Country',
      renewCountryHelp: 'Where your business is. It decides the tax on the receipt.',
      renewVatId: 'VAT id (optional)',
      renewVatIdHelp: 'A id from your own country shifts the VAT to you (reverse charge).',
      yourMoney: 'Your money',
      yourMoneyHint:
        "Paid out per handle. A sale waits out the buyer's window before its share can leave.",
      payoutAccount: 'Payout account',
      change: 'Change',
      paypalAddress: 'PayPal address',
      paypalHelp: 'This store pays out via PayPal.',
      accountHolder: 'Account holder (optional)',
      payoutCountry: 'Country',
      payoutCountryHelp: "Decides how the store's own invoice for your work is taxed.",
      payoutVatId: 'VAT id (optional)',
      creditNotes: 'Credit notes',
      corrects: 'corrects {number}',
      myKits: 'My kits',
      newKit: 'New kit',
      published: 'published {version}',
      publishProject: 'Publish this project',
      projectToExport: 'Project to export',
      version: 'Version',
      versionHelp:
        'A published version is never overwritten. Corrections before approval reuse it; '
        + 'anything already live needs a new one.',
      vaultPassword: 'Vault password (only with encrypted settings)',
      exportHint:
        'This exports the chosen project as a kit and uploads it. The project has to be a kit '
        + 'source — that is, carry an authoring manifest.',
      exportSubmit: 'Export and submit',
      kitId: 'Kit id',
      kitIdHelp: 'Part of the address. It cannot change later.',
      kitDescription: 'What this kit is for.',
      topics: 'Topics',
      topicsHelp:
        'What this kit is for — comma separated, e.g. security, onboarding. What it contains is '
        + 'read off the release; you do not tag that.',
      price: 'Price',
      priceHelp: '0 is free. A store with no payment provider takes free kits only.',
      submissions: 'Submissions',
      rounds: '{count} rounds',
      notSignedInHeadline: 'Not signed in',
      notSignedInBody:
        'Sign in to this store on the Store tab first. The terms and the fees above are '
        + 'readable without it.',
      status: {
        pending: 'waiting for the store',
        rejected: 'refused: {reason}',
        approved: 'approved',
      },
      standing: {
        valid: 'may publish until {until} — {days} day(s) left',
        grace: 'ran out on {until} — grace period, {days} day(s) ago',
        expired: 'ran out on {until}',
        never: 'never renewed',
      },
      domainVerified: '{domain} is verified.',
      domainPending: 'Publish the TXT record, then check.',
      accountSaved: 'Payout account saved.',
      mayPublishAgain: '{vendor} may publish again.',
      paymentContinue: 'Continue the payment in the window that just opened.',
      appliedNotice: 'Applied. You can prepare kits now; publishing waits for the store.',
      kitCreated: '{name} is in the catalogue. Publish a version next.',
      releaseSubmitted: '{kit} {version} submitted — it waits for the store to look at it.',
      error: {
        note: 'Could not render the credit note.',
        createKit: 'Could not create the kit.',
        generic: 'That did not work.',
        money: 'Could not load your payouts.',
        account: 'Could not save the payout account.',
        renew: 'Could not renew publishing.',
        load: 'Could not load the developer view.',
        apply: 'Could not apply.',
        paymentLink: 'The store answered with a payment link this browser will not open.',
      },
    },
  },
};
