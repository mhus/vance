import { brainFetch } from '@vance/shared';
import type { JournalView } from './generated/journal/JournalView';
import type { JournalMonthView } from './generated/journal/JournalMonthView';
import type { JournalEntryContentView } from './generated/journal/JournalEntryContentView';
import type { JournalOnThisDayView } from './generated/journal/JournalOnThisDayView';
import type { JournalRebuildResponse } from './generated/journal/JournalRebuildResponse';
import type { JournalSearchResponse } from './generated/journal/JournalSearchResponse';
import type { JournalCreateEntryRequest } from './generated/journal/JournalCreateEntryRequest';

/** Build a query string, skipping undefined / empty values. */
function qs(params: Record<string, string | number | undefined>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === '') continue;
    u.set(k, String(v));
  }
  return u.toString();
}

export async function scanJournal(
  projectId: string,
  folder: string,
): Promise<JournalView> {
  return brainFetch<JournalView>('GET', `addon/journal/scan?${qs({ projectId, folder })}`);
}

export async function journalMonth(
  projectId: string,
  folder: string,
  year: number,
  month: number,
): Promise<JournalMonthView> {
  return brainFetch<JournalMonthView>(
    'GET',
    `addon/journal/month?${qs({ projectId, folder, year, month })}`,
  );
}

export async function getJournalEntry(
  projectId: string,
  folder: string,
  date: string,
): Promise<JournalEntryContentView> {
  return brainFetch<JournalEntryContentView>(
    'GET',
    `addon/journal/entry?${qs({ projectId, folder, date })}`,
  );
}

export async function putJournalEntry(
  projectId: string,
  folder: string,
  request: JournalCreateEntryRequest,
): Promise<JournalEntryContentView> {
  return brainFetch<JournalEntryContentView>(
    'PUT',
    `addon/journal/entry?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function deleteJournalEntry(
  projectId: string,
  folder: string,
  date: string,
): Promise<void> {
  await brainFetch<unknown>(
    'DELETE',
    `addon/journal/entry?${qs({ projectId, folder, date })}`,
  );
}

export async function journalOnThisDay(
  projectId: string,
  folder: string,
  date: string,
): Promise<JournalOnThisDayView> {
  return brainFetch<JournalOnThisDayView>(
    'GET',
    `addon/journal/on-this-day?${qs({ projectId, folder, date })}`,
  );
}

export async function rebuildJournal(
  projectId: string,
  folder: string,
): Promise<JournalRebuildResponse> {
  return brainFetch<JournalRebuildResponse>(
    'POST',
    `addon/journal/rebuild?${qs({ projectId, folder })}`,
  );
}

export async function searchJournal(
  projectId: string,
  folder: string,
  query: string,
  mood?: string,
  tag?: string,
): Promise<JournalSearchResponse> {
  return brainFetch<JournalSearchResponse>(
    'GET',
    `addon/journal/search?${qs({ projectId, folder, q: query, mood, tag })}`,
  );
}
