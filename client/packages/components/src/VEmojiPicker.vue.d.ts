interface Props {
    modelValue: string | null | undefined;
    disabled?: boolean;
    label?: string;
    /** Optional placeholder when no emoji is set. Defaults to '💬'. */
    placeholder?: string;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (value: string | null) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((value: string | null) => any) | undefined;
}>, {
    disabled: boolean;
    placeholder: string;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=VEmojiPicker.vue.d.ts.map