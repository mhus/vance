import type { CortexDocument } from '../types';
interface Props {
    document: CortexDocument;
    projectId: string;
    /**
     * Chat-session id from the Cortex shell. The server binds the
     * generation think-process to this session so the user can chase
     * it in chat later; fallback to a placeholder when missing.
     */
    sessionId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    close: () => any;
    apply: (code: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onClose?: (() => any) | undefined;
    onApply?: ((code: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CortexHactarDialog.vue.d.ts.map