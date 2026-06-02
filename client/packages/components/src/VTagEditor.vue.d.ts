interface Props {
    modelValue: string[];
    disabled?: boolean;
    label?: string;
    placeholder?: string;
    /** Hard caps mirroring the backend SessionService constants. */
    maxTags?: number;
    maxTagChars?: number;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (value: string[]) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((value: string[]) => any) | undefined;
}>, {
    disabled: boolean;
    maxTags: number;
    maxTagChars: number;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=VTagEditor.vue.d.ts.map