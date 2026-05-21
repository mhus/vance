import { type TreeDocument } from './treeItemsCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
interface Props {
    /** Mode-switch: editor (default), inline, or embedded. */
    mode?: 'editor' | 'inline' | 'embedded';
    /** Parsed TreeDocument — required in editor mode. */
    doc?: TreeDocument;
    /** Raw fence-body — required in inline mode. */
    content?: string;
    /** Fence-meta from the parser — inline mode only. */
    meta?: FenceMeta;
    /** Loaded Document — required in embedded mode. */
    document?: DocumentDto;
    /** Reference info (path, project, kindHint) — embedded mode. */
    embedRef?: EmbedRef;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    mode: "editor" | "inline" | "embedded";
    meta: FenceMeta;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=MindmapView.vue.d.ts.map