interface Props {
    sessionId: string;
    /** Show a "Save as document" affordance in the action group. Wired by
     *  the chat host (ChatView) — the export logic lives there. */
    canSave?: boolean;
    /** True while an export is in flight; renders a spinner glyph and
     *  disables the button. */
    exporting?: boolean;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    archived: () => any;
    reactivated: () => any;
    deleted: () => any;
    save: () => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onArchived?: (() => any) | undefined;
    onReactivated?: (() => any) | undefined;
    onDeleted?: (() => any) | undefined;
    onSave?: (() => any) | undefined;
}>, {
    canSave: boolean;
    exporting: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=SessionHeader.vue.d.ts.map