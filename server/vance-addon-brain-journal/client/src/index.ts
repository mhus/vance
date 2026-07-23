// Barrel for the journal addon's client surface — the mount wrapper for
// the application:journal kind plus the journal REST helpers.

export { default as JournalAppKind } from './JournalAppKind.vue';
export {
  scanJournal,
  journalMonth,
  getJournalEntry,
  putJournalEntry,
  deleteJournalEntry,
  journalOnThisDay,
  rebuildJournal,
  searchJournal,
} from './api';
export type { JournalView } from './generated/journal/JournalView';
export type { JournalEntryView } from './generated/journal/JournalEntryView';
export type { JournalEntryContentView } from './generated/journal/JournalEntryContentView';
export type { JournalStatsView } from './generated/journal/JournalStatsView';
