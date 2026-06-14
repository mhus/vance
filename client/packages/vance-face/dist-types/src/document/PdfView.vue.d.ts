import '../polyfills/mapGetOrInsert';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
interface Props {
    mode?: 'editor' | 'embedded';
    document?: DocumentDto;
    embedRef?: EmbedRef;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    mode: "editor" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=PdfView.vue.d.ts.map