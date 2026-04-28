interface Props {
    modelValue: string;
    label?: string;
    type?: 'text' | 'password' | 'email' | 'number' | 'url';
    placeholder?: string;
    help?: string;
    error?: string;
    required?: boolean;
    disabled?: boolean;
    autocomplete?: string;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (value: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((value: string) => any) | undefined;
}>, {
    type: "text" | "password" | "email" | "number" | "url";
    required: boolean;
    disabled: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=VInput.vue.d.ts.map