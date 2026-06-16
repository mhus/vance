import type { ChatMessageDto, DocumentDto } from '@vance/generated';
import { useDocuments } from './useDocuments';

/**
 * Default folder under the project root for chat-export documents.
 * Auto-save artefacts land here so the project root stays curated;
 * the user can move them out via the document tree.
 */
export const CONVERSATION_EXPORT_FOLDER = 'conversations';

/** Roles that contribute a turn to the exported markdown. */
const EXPORTABLE_ROLES: ReadonlySet<string> = new Set(['USER', 'ASSISTANT']);

interface ExportTurn {
  role: string;
  content: string;
  createdAt?: Date | string | number;
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

function toDate(value: Date | string | number | undefined): Date | null {
  if (value == null) return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Local-time display string used in the per-turn heading. */
function formatTurnTimestamp(value: Date | string | number | undefined): string | null {
  const d = toDate(value);
  if (!d) return null;
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} `
    + `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/**
 * Filesystem-safe timestamp slug for the export filename — local time,
 * no colons (Windows reserves them). Caller passes a Date so tests can
 * inject a fixed clock.
 */
export function exportTimestampSlug(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`
    + `T${pad2(now.getHours())}-${pad2(now.getMinutes())}-${pad2(now.getSeconds())}`;
}

/** Markdown-friendly cap on the role token — exposes "User"/"Assistant" headings. */
function titleCaseRole(role: string): string {
  const lower = role.toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/**
 * Tokens that force a YAML scalar to be quoted to stay parseable. Most
 * session-ids (UUIDs) and project names (lowercase + underscores) are
 * safe unquoted, but defensive quoting protects against the few legal
 * names that happen to start with a YAML control char.
 */
const YAML_UNSAFE = /[:#\[\]{}&*!|>'"%@`,\n\r\t]/;

function yamlScalar(value: string): string {
  if (!YAML_UNSAFE.test(value) && value.trim() === value && value.length > 0) {
    return value;
  }
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/** ISO-with-offset rendering of a {@link Date} — kept self-contained so
 *  this composable doesn't pull in a date library for one timestamp. */
function isoWithOffset(d: Date): string {
  const offMin = -d.getTimezoneOffset();
  const sign = offMin >= 0 ? '+' : '-';
  const off = Math.abs(offMin);
  const offHH = pad2(Math.floor(off / 60));
  const offMM = pad2(off % 60);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
    + `T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
    + `${sign}${offHH}:${offMM}`;
}

export interface ExportFrontmatter {
  sessionId?: string | null;
  project?: string | null;
  /** Wall-clock instant the export was generated. */
  exported?: Date;
}

function formatFrontmatter(meta: ExportFrontmatter): string {
  const lines: string[] = [];
  if (meta.sessionId && meta.sessionId.length > 0) {
    lines.push(`sessionId: ${yamlScalar(meta.sessionId)}`);
  }
  if (meta.project && meta.project.length > 0) {
    lines.push(`project: ${yamlScalar(meta.project)}`);
  }
  if (meta.exported) {
    lines.push(`exported: ${yamlScalar(isoWithOffset(meta.exported))}`);
  }
  if (lines.length === 0) return '';
  return `---\n${lines.join('\n')}\n---\n\n`;
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
export function formatConversationMarkdown(
  turns: readonly ExportTurn[],
  frontmatter: ExportFrontmatter = {},
): string {
  const blocks: string[] = [];
  for (const turn of turns) {
    if (!EXPORTABLE_ROLES.has(turn.role)) continue;
    const content = (turn.content ?? '').trim();
    if (content.length === 0) continue;
    const ts = formatTurnTimestamp(turn.createdAt);
    const heading = ts
      ? `## ${titleCaseRole(turn.role)} · ${ts}`
      : `## ${titleCaseRole(turn.role)}`;
    blocks.push(`${heading}\n\n${content}`);
  }
  if (blocks.length === 0) return '';
  return `${formatFrontmatter(frontmatter)}${blocks.join('\n\n')}`;
}

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
export function useConversationExport(): {
  saveConversationAsDocument: (opts: ConversationExportOptions) => Promise<DocumentDto | null>;
  copyMessageToClipboard: (content: string) => Promise<boolean>;
} {
  const docs = useDocuments();

  async function saveConversationAsDocument(
    opts: ConversationExportOptions,
  ): Promise<DocumentDto | null> {
    const now = opts.now ?? new Date();
    const markdown = formatConversationMarkdown(opts.turns, {
      sessionId: opts.sessionId,
      project: opts.projectId,
      exported: now,
    });
    if (markdown.length === 0) return null;

    const slug = exportTimestampSlug(now);
    const path = `${CONVERSATION_EXPORT_FOLDER}/chat-${slug}.md`;
    const titleStamp = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())} `
      + `${pad2(now.getHours())}:${pad2(now.getMinutes())}`;
    const title = opts.sessionTitle && opts.sessionTitle.trim().length > 0
      ? opts.sessionTitle.trim()
      : `Chat export · ${titleStamp}`;

    return docs.create(opts.projectId, {
      path,
      title,
      mimeType: 'text/markdown',
      inlineText: markdown,
      autoSummary: false,
      ragEnabled: 'off',
    });
  }

  async function copyMessageToClipboard(content: string): Promise<boolean> {
    if (!content) return false;
    try {
      await navigator.clipboard.writeText(content);
      return true;
    } catch {
      return false;
    }
  }

  return {
    saveConversationAsDocument,
    copyMessageToClipboard,
  };
}
