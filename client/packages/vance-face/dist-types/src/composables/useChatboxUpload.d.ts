import type { AttachmentRef } from '@vance/generated';
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
export declare const CHATBOX_MAX_BYTES_PER_FILE: number;
export declare const CHATBOX_MAX_BYTES_PER_REQUEST: number;
/** Folder convention for chat-dropped files. Lives alongside the
 *  user's regular documents so they remain browsable / re-usable
 *  via the documents editor. No automatic cleanup today. */
export declare const CHATBOX_FOLDER = "_chatbox";
/**
 * Distinct error type so {@code ChatView}'s catch-block can tell
 * "size limit / network failure during attachment upload" apart from
 * a {@code WebSocketRequestError} on the steer call itself.
 */
export declare class ChatboxUploadError extends Error {
    constructor(message: string);
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
export declare function uploadChatboxAttachments(projectId: string, files: File[]): Promise<AttachmentRef[]>;
//# sourceMappingURL=useChatboxUpload.d.ts.map