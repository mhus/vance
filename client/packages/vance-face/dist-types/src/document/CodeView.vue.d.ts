import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
interface Props {
    /** Identifies the code language (kind). */
    kind?: string;
    /** Mode-switch — editor (default) / inline / embedded. */
    mode?: 'editor' | 'inline' | 'embedded';
    /** Fenced-body content — inline mode. */
    content?: string;
    meta?: FenceMeta;
    /** Loaded Document — embedded mode. */
    document?: DocumentDto;
    embedRef?: EmbedRef;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    meta: FenceMeta;
    mode: "editor" | "inline" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CodeView.vue.d.ts.map