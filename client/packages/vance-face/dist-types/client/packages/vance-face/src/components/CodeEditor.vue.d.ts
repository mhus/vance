interface Props {
    modelValue: string;
    /**
     * Selects the syntax-highlighting language. See {@link languageFor}
     * for the full list — covers Markdown, JSON, YAML, JavaScript /
     * TypeScript, Python, Shell / Bash, R, HTML, CSS, XML, SQL, Java.
     * Unknown / blank mime-types fall back to plain text.
     */
    mimeType?: string | null;
    label?: string;
    /** Disabled state — read-only AND visually dimmed (form-disabled look). */
    disabled?: boolean;
    /**
     * Read-only without dimming — used when the same editor surface
     * shows generated / referenced code (rich-content blocks in chat,
     * embedded snippets in documents). Mutually exclusive with
     * {@link disabled} from a UX standpoint, but stacking is safe: both
     * map to the same underlying {@code EditorState.readOnly}.
     */
    readOnly?: boolean;
    /** Approximate visible-line count — drives min-height. */
    rows?: number;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "update:modelValue": (value: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onUpdate:modelValue"?: ((value: string) => any) | undefined;
}>, {
    disabled: boolean;
    rows: number;
    readOnly: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CodeEditor.vue.d.ts.map