import type { AttachmentRef, DocumentDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Soft size limits enforced before the upload starts. The brain
 * enforces matching hard limits server-side
 * ({@code vance.ai.attachment.max-bytes-per-file},
 * {@code vance.ai.attachment.max-bytes-per-request}); the client-side
 * checks exist so an oversized drop fails fast with a clear message
 * instead of after a 30 s upload roundtrip.
 *
 * <p>Numbers chosen to match the server-side defaults — adjust both in
 * lockstep when the server settings change.
 */
export const CHATBOX_MAX_BYTES_PER_FILE = 20 * 1024 * 1024;
export const CHATBOX_MAX_BYTES_PER_REQUEST = 32 * 1024 * 1024;

/** Folder convention for chat-dropped files. Lives alongside the
 *  user's regular documents so they remain browsable / re-usable
 *  via the documents editor. No automatic cleanup today. */
export const CHATBOX_FOLDER = '_chatbox';

/**
 * Distinct error type so {@code ChatView}'s catch-block can tell
 * "size limit / network failure during attachment upload" apart from
 * a {@code WebSocketRequestError} on the steer call itself.
 */
export class ChatboxUploadError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ChatboxUploadError';
  }
}

/**
 * Upload {@code files} as fresh {@code DocumentDocument}s under
 * {@link CHATBOX_FOLDER} in the caller's project, then return one
 * {@link AttachmentRef} per file in the same order. All-or-nothing:
 * any failure rejects the returned promise — the caller should abort
 * the send and surface the error rather than send a partial payload.
 *
 * <p>Uploads run in parallel via {@code Promise.all} so dropping
 * three files doesn't serialise into three roundtrips.
 *
 * @throws ChatboxUploadError when a per-file or per-request size
 *         limit is exceeded, or when the brain rejects an upload.
 */
export async function uploadChatboxAttachments(
  projectId: string,
  files: File[],
): Promise<AttachmentRef[]> {
  if (!files.length) {
    return [];
  }
  if (!projectId) {
    throw new ChatboxUploadError('No project bound to this session — cannot upload attachments.');
  }

  // Pre-flight size check — fast fail before any upload starts.
  let total = 0;
  for (const f of files) {
    if (f.size > CHATBOX_MAX_BYTES_PER_FILE) {
      throw new ChatboxUploadError(
        `Attachment "${f.name}" exceeds the per-file limit of `
          + `${formatMb(CHATBOX_MAX_BYTES_PER_FILE)} MB.`);
    }
    total += f.size;
  }
  if (total > CHATBOX_MAX_BYTES_PER_REQUEST) {
    throw new ChatboxUploadError(
      `Combined attachment size (${formatMb(total)} MB) exceeds the per-request `
        + `limit of ${formatMb(CHATBOX_MAX_BYTES_PER_REQUEST)} MB.`);
  }

  return Promise.all(files.map((file) => uploadOne(projectId, file)));
}

async function uploadOne(projectId: string, file: File): Promise<AttachmentRef> {
  const path = `${CHATBOX_FOLDER}/${randomIdPart()}_${sanitiseFilename(file.name)}`;
  const form = new FormData();
  form.append('file', file);
  form.append('path', path);

  const params = new URLSearchParams({ projectId });
  let doc: DocumentDto;
  try {
    doc = await brainFetch<DocumentDto>('POST', `documents/upload?${params}`, { body: form });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new ChatboxUploadError(`Upload failed for "${file.name}": ${msg}`);
  }
  if (!doc?.id) {
    throw new ChatboxUploadError(`Upload returned no document id for "${file.name}".`);
  }
  return { documentId: doc.id };
}

/**
 * 8 hex characters from a fresh UUID v4 — collision-resistant for the
 * chatbox folder while keeping paths short enough to scan visually
 * in the documents editor.
 *
 * <p>Falls back to a Math.random hex on the (very rare) browsers
 * without {@code crypto.randomUUID} so the function never throws.
 */
function randomIdPart(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID().replace(/-/g, '').slice(0, 8);
  }
  let out = '';
  for (let i = 0; i < 8; i++) {
    out += Math.floor(Math.random() * 16).toString(16);
  }
  return out;
}

/**
 * Drop characters the brain's path normaliser would reject or
 * collapse — leading dots, slashes, control chars, ASCII-non-printable.
 * The randomIdPart prefix keeps the result unique even when the
 * sanitisation collapses two source filenames to the same string.
 */
function sanitiseFilename(name: string): string {
  // eslint-disable-next-line no-control-regex -- stripping control characters from filenames is the point
  const trimmed = name.replace(/^\.+/, '').replace(/[/\\\u0000-\u001f]/g, '_').trim();
  return trimmed.length > 0 ? trimmed : 'attachment';
}

function formatMb(bytes: number): string {
  return (bytes / (1024 * 1024)).toFixed(1);
}
