export interface Props {
    documentId: string;
    mimeType?: string | null;
    /** True when the source is editable inline-text — preview steps
     *  out of the way for those (the editor handles them). */
    inline?: boolean;
}
declare function attachCanvas(host: HTMLElement | null, canvas: HTMLCanvasElement): void;
export { attachCanvas };
declare const _default: import("vue").DefineComponent<Props, {
    downloadUrl: import("vue").ComputedRef<string>;
}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=DocumentPreview.vue.d.ts.map