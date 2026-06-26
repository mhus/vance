import type { ChatMessageDto, DocumentDto } from '@vance/generated';
/**
 * Default folder under the project root for chat-export documents.
 * Auto-save artefacts land here so the project root stays curated;
 * the user can move them out via the document tree.
 */
export declare const CONVERSATION_EXPORT_FOLDER = "conversations";
interface ExportTurn {
    role: string;
    content: string;
    createdAt?: Date | string | number;
    /** Login id of the message author — multi-user chats attribute each
     *  USER turn to its sender so the export carries provenance. */
    senderUserId?: string | null;
    /** Display name of the author; preferred over {@code senderUserId}
     *  in the rendered heading. */
    senderDisplayName?: string | null;
}
/**
 * Filesystem-safe timestamp slug for the export filename — local time,
 * no colons (Windows reserves them). Caller passes a Date so tests can
 * inject a fixed clock.
 */
export declare function exportTimestampSlug(now?: Date): string;
export interface ExportFrontmatter {
    sessionId?: string | null;
    project?: string | null;
    /** Wall-clock instant the export was generated. */
    exported?: Date;
}
/**
 * Render the conversation as Markdown. The output starts with a YAML
 * frontmatter block carrying {@code sessionId} / {@code project} /
 * {@code exported} when the caller supplies them — handy for later
 * re-import or for the LLM if it ever sees the file via
 * {@code manual_read}. Each turn then becomes a
 * {@code ## Role · timestamp} heading followed by the raw
 * {@code content} string. Fenced code blocks, tables and other
 * Markdown structure pass through unchanged. SYSTEM messages,
 * tool-call frames and worker side-chatter are filtered out by the
 * caller before reaching this function (the composable does not know
 * which {@code messageId}s belong to sub-processes).
 */
export declare function formatConversationMarkdown(turns: readonly ExportTurn[], frontmatter?: ExportFrontmatter): string;
export interface ConversationExportOptions {
    /** Project the chat session lives in. The export lands in the same project. */
    projectId: string;
    /** Session whose conversation is being exported. Surfaces in the document's
     *  YAML frontmatter so the export remains traceable to its source session. */
    sessionId?: string | null;
    /** Filtered conversation turns — caller pre-filters workers / tool-calls. */
    turns: readonly ChatMessageDto[];
    /** Optional human-readable session title used for the document title field. */
    sessionTitle?: string | null;
    /** Override the wall-clock for the filename slug (testing). */
    now?: Date;
}
/**
 * Reactive helper that wraps the conversation-export flow used by the chat
 * and cortex editors. The composable owns a private {@link useDocuments}
 * instance so the call does not refresh the embedding editor's pagination
 * state as a side effect.
 *
 * <p>The created document is auto-summary-off and RAG-off by default: the
 * chat content is already embedded in the session itself, so re-summarising
 * and re-indexing the export would just burn LLM tokens on duplicate
 * material. The user can flip both flags on later via the document editor.
 */
export declare function useConversationExport(): {
    saveConversationAsDocument: (opts: ConversationExportOptions) => Promise<DocumentDto | null>;
    copyMessageToClipboard: (content: string) => Promise<boolean>;
};
export {};
//# sourceMappingURL=useConversationExport.d.ts.map