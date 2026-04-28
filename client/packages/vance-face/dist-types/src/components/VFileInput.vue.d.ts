interface Props {
    modelValue: File | null;
    label?: string;
    /** Comma-separated MIME types or extensions, e.g. `"image/*,.pdf"`. */
    accept?: string;
    help?: string;
    error?: string;
    required?: boolean;
    disabled?: boolean;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (file: File | null) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((file: File | null) => any) | undefined;
}>, {
    required: boolean;
    disabled: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=VFileInput.vue.d.ts.map