/**
 * English messages of the finance addon surface.
 *
 * <p>Self-contained: an addon does not borrow keys from the host's `common.*`
 * namespace. A remote ships and deploys on its own schedule, so a bundle that
 * depends on the host's key layout would break on a rename it cannot see.
 */
export default {
  documents: {
    detail: {
      tabFinance: 'Finance',
    },
  },
  finance: {
    fallbackTitle: 'Finance tree',
    reload: 'Reload',
    unit: 'Unit',
    report: 'Report',
    loading: 'Loading…',
    createRoot: '＋ Create root node',
    pickNode: 'Select a node to edit it.',
    // Save indicator, keyed by state id.
    save: {
      saved: 'saved',
      dirty: 'unsaved',
      saving: 'saving…',
      error: 'save failed',
    },
    fields: {
      heading: 'Fields · {name}',
      title: 'Title',
      icon: 'Icon',
      color: 'Color',
      negative: 'negative (flips subtree)',
      description: 'Description',
      notesRef: 'Notes ref',
    },
    values: {
      heading: 'Value blocks',
      add: '＋ value',
      amount: 'Amount',
      mode: 'Mode',
      modeRecurring: 'recurring',
      modeOneTime: 'one-time',
      per: 'per',
      unit: 'unit',
      date: 'date',
      validFrom: 'valid from',
      validTo: 'valid to',
      interest: 'interest',
      rate: 'rate %',
      compound: 'compound',
    },
    perUnit: {
      day: '/day',
      week: '/week',
      month: '/month',
      year: '/year',
    },
    chartType: {
      line: 'line',
      bar: 'bar',
      area: 'area',
      scatter: 'scatter',
    },
    period: {
      day: 'day',
      week: 'week',
      month: 'month',
      year: 'year',
    },
    reportModal: {
      title: 'Generate report',
      processor: 'Processor',
      from: 'from',
      to: 'to',
      granularity: 'granularity',
      chartType: 'chart type (series)',
      focus: 'focus (assessment, optional)',
      persist: 'save as document',
      outputPath: 'output path',
      outputPathPlaceholder: 'reports/q1.chart.yaml',
      generating: 'Generating…',
      generate: 'Generate',
      running: 'Generating report…',
      savedTo: 'Saved to {path}',
    },
    tree: {
      perUnit: 'per {unit}',
      addChild: 'Add child',
      up: 'Up',
      down: 'Down',
      indent: 'Indent',
      outdent: 'Outdent',
      delete: 'Delete',
    },
    summary: {
      edit: 'Edit',
      perYear: '/ year',
      oneTime: 'one-time: {amount}',
      empty: 'Empty — open to add nodes.',
      modalTitle: 'Finance tree',
      unresolved: 'Cannot resolve the finance document.',
    },
  },
};
