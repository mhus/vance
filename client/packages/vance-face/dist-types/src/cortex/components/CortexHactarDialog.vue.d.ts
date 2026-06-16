import type { CortexDocument } from '../types';
interface Props {
    document: CortexDocument;
    projectId: string;
    /** Chat-session id from the Cortex shell. The server binds the
     *  Slart process to this session so the user can chase it in chat
     *  later; fallback to a placeholder when missing. */
    sessionId?: string | null;
    /** Operation mode — picks the system-prompt branch on the server
     *  and the dialog title here. */
    mode: 'CREATE' | 'UPDATE';
    /** Optional pre-fill for UPDATE mode — the FAILED-run reason from
     *  the Cortex Run panel. Surfaces as a description hint. */
    failureReason?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    close: any;
    apply: any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onClose?: ((...args: any) => any) | undefined;
    onApply?: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CortexHactarDialog.vue.d.ts.map