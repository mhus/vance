/**
 * Visual marker for a document — picks a glyph from {@code kind} (set
 * in the front-matter of yaml/json/md files), the {@code mimeType},
 * or the path's extension, in that priority. Falls back to a generic
 * page glyph when nothing matches.
 *
 * Rendered as a Unicode glyph rather than an SVG icon set so we
 * don't pull in another runtime dependency. The glyphs render with
 * native emoji on every modern browser.
 */
interface Props {
    path?: string | null;
    mimeType?: string | null;
    /**
     * Document kind from the front matter (e.g. {@code list},
     * {@code mindmap}). Lower-case; unknown values fall through to the
     * mime/extension lookup.
     */
    kind?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=DocumentIcon.vue.d.ts.map