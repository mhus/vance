import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
interface Props {
    mode?: 'editor' | 'embedded';
    /** Embedded channel (chat fence → vance:-link → loaded doc). */
    document?: DocumentDto;
    embedRef?: EmbedRef;
    /** Editor channel (document-app preview pane) — caller passes
     *  the id directly when there's no full DTO to hand in. */
    documentId?: string;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    mode: "editor" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=DocxView.vue.d.ts.map