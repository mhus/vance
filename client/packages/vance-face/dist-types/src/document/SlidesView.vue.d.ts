import { type SlidesDocument } from './slidesCodec';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';
interface Props {
    mode?: 'editor' | 'embedded';
    doc?: SlidesDocument;
    document?: DocumentDto;
    embedRef?: EmbedRef;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    mode: "editor" | "embedded";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=SlidesView.vue.d.ts.map