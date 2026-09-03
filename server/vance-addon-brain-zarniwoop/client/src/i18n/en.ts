/**
 * English messages of the search (zarniwoop) addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  search: {
    queryPlaceholder: 'What are you looking for?',
    search: 'Search',
    investigate: 'Investigate',
    investigateTip:
      'Plans several searches and has a model rank the results — costs tokens as well as quota',
    saveSearch: 'Save search',
    saveSearchTip: 'Keep this query, modality and tier under Settings',
    back: 'Back',
    settings: 'Settings',
    // Keyed by the server's modality id.
    modality: {
      web: 'Web',
      news: 'News',
      image: 'Images',
      video: 'Videos',
      pdf: 'PDFs',
      academic: 'Papers',
      encyclopedia: 'Encyclopedia',
      book: 'Books',
      code: 'Code',
      internal_doc: 'Documents',
      map: 'Maps',
      rag: 'Knowledge base',
    },
    filters: 'Filters',
    filtersTip: 'Filters declared by the endpoints serving this modality',
    expert: 'Expert',
    expertTip: 'Expert tier — pin an endpoint and pass filters',
    anyEndpoint: 'Any endpoint',
    any: 'Any',
    clear: 'Clear',
    facetHint: 'Applies to the next search — an endpoint that does not offer a selected filter is skipped.',
    noProviderHeadline: 'No search provider configured',
    noProviderBody:
      'Set research.endpoint.<id>.protocol and its key in the settings first. '
      + 'Already done? The inventory is cached for five minutes — reload it under Settings.',
    providers: 'Providers',
    noProviders: 'Nothing configured in this project.',
    reloadProviders: 'Reload providers',
    defaults: 'Defaults',
    saveDefaults: 'Save defaults',
    savedSearches: 'Saved searches',
    noSavedSearches: 'None yet — run a search and press “Save search”.',
    run: 'Run',
    remove: 'Remove',
    searching: 'Searching…',
    nothingSearchedHeadline: 'Nothing searched yet',
    nothingSearchedBody: 'Type a query and press Search.',
    nothingFoundHeadline: 'Nothing found',
    withheld: '{count} result(s) were withheld by the source.',
    noResults: 'The provider returned no results for this query.',
    fullTextIncluded: 'full text included',
    fullTextOnRequest: 'full text on request',
    curatedFor: 'Curated answer for: {query}',
    gaps: 'Gaps:',
    curatedCounts: '{kept} kept, {dropped} rejected · sources: {sources}',
    citedPrefix: 'cited ',
    detail: {
      loading: 'Loading…',
      loadFullText: 'Load full text',
      noFullText: 'This provider ships no full text — the page behind the link is the answer.',
      openSource: 'Open source page ↗',
      openImage: 'Open image file ↗',
      watchVideo: 'Watch video ↗',
      openPdf: 'Open PDF ↗',
      openDiscussion: 'Open discussion ↗',
    },
  },
};
