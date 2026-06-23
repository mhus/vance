import { AccentColor } from '@vance/generated';
interface Props {
    modelValue: AccentColor | null | undefined;
    /** When true, an extra "no color" chip is shown so the user can clear the choice. */
    allowClear?: boolean;
    disabled?: boolean;
    /** Optional label rendered above the chip row. */
    label?: string;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (value: AccentColor | null) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((value: AccentColor | null) => any) | undefined;
}>, {
    disabled: boolean;
    allowClear: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=VColorPicker.vue.d.ts.map