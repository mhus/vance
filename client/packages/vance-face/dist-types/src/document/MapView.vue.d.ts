import 'leaflet/dist/leaflet.css';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
import { type MapDocument } from './mapCodec';
type __VLS_Props = {
    mode?: 'editor' | 'inline' | 'embedded';
    doc?: MapDocument;
    content?: string;
    meta?: FenceMeta;
    document?: DocumentDto;
    embedRef?: EmbedRef;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{}>, {
    meta: FenceMeta;
    mode: "editor" | "inline" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=MapView.vue.d.ts.map