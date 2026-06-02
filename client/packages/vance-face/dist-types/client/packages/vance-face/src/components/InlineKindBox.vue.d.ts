import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
interface Props {
    kind: string;
    /** Fence-body content. */
    content: string;
    /** Parsed fence meta (key=value pairs). */
    meta?: FenceMeta;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    meta: FenceMeta;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=InlineKindBox.vue.d.ts.map