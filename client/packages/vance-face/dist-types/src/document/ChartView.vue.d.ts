import { type ChartDocument } from './chartCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
type __VLS_Props = {
    mode?: 'editor' | 'inline' | 'embedded';
    /** Editor mode — full mutable doc. */
    doc?: ChartDocument;
    /** Inline mode — fence body (JSON or YAML). */
    content?: string;
    meta?: FenceMeta;
    /** Embedded mode — loaded Document. */
    document?: DocumentDto;
    embedRef?: EmbedRef;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": (doc: ChartDocument) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((doc: ChartDocument) => any) | undefined;
}>, {
    mode: "editor" | "inline" | "embedded";
    meta: FenceMeta;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChartView.vue.d.ts.map