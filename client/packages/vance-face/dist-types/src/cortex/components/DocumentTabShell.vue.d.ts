import type { CortexDocument } from '../types';
interface Props {
    document: CortexDocument;
    /** Chat session id — Hactar binds its think-process to this session. */
    sessionId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    update: any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onUpdate?: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=DocumentTabShell.vue.d.ts.map