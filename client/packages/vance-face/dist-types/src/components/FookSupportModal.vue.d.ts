/**
 * Free-form bug / feature / feedback report dialog. Lives in the
 * user-menu of {@code EditorTopbar} so reporters can reach it from
 * every editor. The form is intentionally minimal — one textarea.
 * Fook on the server side derives type, title and severity from
 * the text, so the UI doesn't need to ask.
 *
 * <p>If the current URL carries {@code ?project=…} or
 * {@code ?sessionId=…} query parameters, those get forwarded as
 * origin context. The user-menu can be reached from any route, so
 * either may be absent — Fook accepts both as optional.
 */
interface Props {
    modelValue: boolean;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (open: boolean) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((open: boolean) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=FookSupportModal.vue.d.ts.map