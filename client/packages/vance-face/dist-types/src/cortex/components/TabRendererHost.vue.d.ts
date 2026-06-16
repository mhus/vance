import type { CortexDocument } from '../types';
interface Props {
    document: CortexDocument;
    /** Chat session id — forwarded to language-aware dialogs (Hactar). */
    sessionId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    update: (text: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onUpdate?: ((text: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=TabRendererHost.vue.d.ts.map