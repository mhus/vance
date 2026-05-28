import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
interface Props {
    mode?: 'editor' | 'embedded';
    document?: DocumentDto;
    embedRef?: EmbedRef;
    /** Editor channel — caller passes the id when there's no DTO. */
    documentId?: string;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    mode: "editor" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=XlsxView.vue.d.ts.map