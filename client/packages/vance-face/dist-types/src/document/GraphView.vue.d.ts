import '@vue-flow/core/dist/style.css';
import '@vue-flow/core/dist/theme-default.css';
import type { GraphDocument } from './graphCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
type __VLS_Props = {
    mode?: 'editor' | 'inline' | 'embedded';
    doc?: GraphDocument;
    content?: string;
    meta?: FenceMeta;
    document?: DocumentDto;
    embedRef?: EmbedRef;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:doc": (doc: GraphDocument) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onUpdate:doc"?: ((doc: GraphDocument) => any) | undefined;
}>, {
    mode: "editor" | "inline" | "embedded";
    meta: FenceMeta;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=GraphView.vue.d.ts.map