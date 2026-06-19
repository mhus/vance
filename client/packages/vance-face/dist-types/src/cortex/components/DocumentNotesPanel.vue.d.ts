import type { DocumentNoteDto } from '@vance/generated';
interface Props {
    notes: DocumentNoteDto[];
    /** Note ids that should pulse / scroll-into-view (set by parent on gutter click). */
    highlightedNoteId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    add: () => any;
    delete: (noteId: string) => any;
    update: (noteId: string, patch: {
        text?: string;
        done?: boolean;
    }) => any;
    "jump-to-line": (line: number) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onAdd?: (() => any) | undefined;
    onDelete?: ((noteId: string) => any) | undefined;
    onUpdate?: ((noteId: string, patch: {
        text?: string;
        done?: boolean;
    }) => any) | undefined;
    "onJump-to-line"?: ((line: number) => any) | undefined;
}>, {
    highlightedNoteId: string | null;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=DocumentNotesPanel.vue.d.ts.map