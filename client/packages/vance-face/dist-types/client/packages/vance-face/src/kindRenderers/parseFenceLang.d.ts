/**
 * Fence-language parser for kind-tagged code blocks.
 *
 * Input: the `lang` part of a fenced code block, e.g. `mindmap` or
 * `mindmap theme=dark,direction=right`.
 * Output: `{ kind, meta }` where `kind` is the lowercased first
 * token and `meta` is the parsed `key=value` map of the optional
 * second segment.
 *
 * Spec: specification/inline-and-embedded-content.md §2 + §11.7.
 * v1 format is comma-separated `key=value`; whitespace inside
 * values is preserved as-is.
 */
export type FenceMeta = Record<string, string>;
export interface ParsedFence {
    kind: string;
    meta: FenceMeta;
}
export declare function parseFenceLang(lang: string | undefined | null): ParsedFence;
//# sourceMappingURL=parseFenceLang.d.ts.map