import { brainFetch } from '@vance/shared';
import type { ScribblebookCreatePageRequest } from './generated/scribble/ScribblebookCreatePageRequest';
import type { ScribblebookPageView } from './generated/scribble/ScribblebookPageView';
import type { ScribblebookRebuildResponse } from './generated/scribble/ScribblebookRebuildResponse';
import type { ScribblebookView } from './generated/scribble/ScribblebookView';
import type { ScribbleCreateSheetRequest } from './generated/scribble/ScribbleCreateSheetRequest';
import type { ScribbleOcrResponse } from './generated/scribble/ScribbleOcrResponse';
import type { ScribblePdfResponse } from './generated/scribble/ScribblePdfResponse';
import type { ScribbleSheetDto } from './generated/scribble/ScribbleSheetDto';
import type { ScribbleSheetView } from './generated/scribble/ScribbleSheetView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

// ── Sheet (kind: scribble) ───────────────────────────────────────

export async function getSheet(projectId: string, path: string): Promise<ScribbleSheetView> {
  return brainFetch<ScribbleSheetView>(
    'GET',
    `addon/scribble/sheet?${qs({ projectId, path })}`,
  );
}

export async function putSheet(
  projectId: string,
  path: string,
  sheet: ScribbleSheetDto,
): Promise<ScribbleSheetView> {
  return brainFetch<ScribbleSheetView>(
    'PUT',
    `addon/scribble/sheet?${qs({ projectId, path })}`,
    { body: sheet },
  );
}

export async function createSheet(
  projectId: string,
  request: ScribbleCreateSheetRequest,
): Promise<ScribbleSheetView> {
  return brainFetch<ScribbleSheetView>(
    'POST',
    `addon/scribble/sheet?${qs({ projectId })}`,
    { body: request },
  );
}

// ── Scribblebook (app container) ─────────────────────────────────

export async function scanScribblebook(
  projectId: string,
  folder: string,
): Promise<ScribblebookView> {
  return brainFetch<ScribblebookView>(
    'GET',
    `addon/scribble/scan?${qs({ projectId, folder })}`,
  );
}

export async function createScribblePage(
  projectId: string,
  folder: string,
  request: ScribblebookCreatePageRequest,
): Promise<ScribblebookPageView> {
  return brainFetch<ScribblebookPageView>(
    'POST',
    `addon/scribble/page?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function rebuildScribblebook(
  projectId: string,
  folder: string,
): Promise<ScribblebookRebuildResponse> {
  return brainFetch<ScribblebookRebuildResponse>(
    'POST',
    `addon/scribble/rebuild?${qs({ projectId, folder })}`,
  );
}

// ── OCR + PDF export ─────────────────────────────────────────────

/** Runs a vision transcription; resolves when the sheet is transcribed. */
export async function ocrSheet(projectId: string, path: string): Promise<ScribbleOcrResponse> {
  return brainFetch<ScribbleOcrResponse>(
    'POST',
    `addon/scribble/ocr?${qs({ projectId, path })}`,
  );
}

export async function exportSheetPdf(projectId: string, path: string): Promise<ScribblePdfResponse> {
  return brainFetch<ScribblePdfResponse>(
    'POST',
    `addon/scribble/pdf?${qs({ projectId, path })}`,
  );
}

export async function exportBookPdf(projectId: string, folder: string): Promise<ScribblePdfResponse> {
  return brainFetch<ScribblePdfResponse>(
    'POST',
    `addon/scribble/bookpdf?${qs({ projectId, folder })}`,
  );
}
