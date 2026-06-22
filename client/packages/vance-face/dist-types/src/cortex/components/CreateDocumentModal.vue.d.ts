interface Props {
    /** v-model:open — two-way visibility flag. */
    open: boolean;
    /** Required to enable the submit button (also serves as the
     *  parent's pre-flight check). */
    projectId: string | null;
    /** Directory the new document lands in. Trailing slash is
     *  normalised. Empty string = project root. */
    initialPath?: string;
    /** When set, the modal calls {@code consumeDocumentDraft()} on
     *  every open and pre-fills name / title / mime / content from
     *  the stored draft (Inbox "To Document" flow). One-shot. */
    consumeDraft?: boolean;
}
export type CreateModalResult = {
    kind: 'inline';
    fullPath: string;
    title: string | undefined;
    tags: string[] | undefined;
    mimeType: string;
    inlineText: string;
} | {
    kind: 'upload';
    files: File[];
    targetFolder: string;
    title: string | undefined;
    tags: string[] | undefined;
};
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:open": (open: boolean) => any;
    confirm: (result: CreateModalResult) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:open"?: ((open: boolean) => any) | undefined;
    onConfirm?: ((result: CreateModalResult) => any) | undefined;
}>, {
    initialPath: string;
    consumeDraft: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CreateDocumentModal.vue.d.ts.map