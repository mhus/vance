import type { DocumentNoteDto } from '@vance/generated';
interface Props {
    notes: DocumentNoteDto[];
    /** Note ids that should pulse / scroll-into-view (set by parent on gutter click). */
    highlightedNoteId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    delete: any;
    add: any;
    update: any;
    "jump-to-line": any;
    reorder: any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onDelete?: ((...args: any) => any) | undefined;
    onAdd?: ((...args: any) => any) | undefined;
    onUpdate?: ((...args: any) => any) | undefined;
    "onJump-to-line"?: ((...args: any) => any) | undefined;
    onReorder?: ((...args: any) => any) | undefined;
}>, {
    highlightedNoteId: string | null;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=DocumentNotesPanel.vue.d.ts.map