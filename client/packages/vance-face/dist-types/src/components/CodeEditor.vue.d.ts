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
    disabled?: boolean;
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
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CodeEditor.vue.d.ts.map