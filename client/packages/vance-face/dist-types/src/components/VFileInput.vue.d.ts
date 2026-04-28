interface Props {
    /**
     * Currently picked files. Always an array — single-mode just keeps it
     * 1-element. Empty array means nothing picked yet.
     */
    modelValue: File[];
    label?: string;
    /** Comma-separated MIME types or extensions, e.g. `"image/*,.pdf"`. */
    accept?: string;
    /** Allow multiple files via the picker and via drop. */
    multiple?: boolean;
    help?: string;
    error?: string;
    required?: boolean;
    disabled?: boolean;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (files: File[]) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((files: File[]) => any) | undefined;
}>, {
    multiple: boolean;
    required: boolean;
    disabled: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=VFileInput.vue.d.ts.map