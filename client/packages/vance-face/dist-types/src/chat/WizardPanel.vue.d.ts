interface Props {
    projectId?: string;
    /** Active session — only used to decide when to refresh the listing. */
    sessionKey?: string;
}
declare function openWizard(name: string, prefill?: Record<string, string>): Promise<void>;
declare const _default: import("vue").DefineComponent<Props, {
    openWizard: typeof openWizard;
}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    promptReady: (prompt: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onPromptReady?: ((prompt: string) => any) | undefined;
}>, {
    projectId: string;
    sessionKey: string;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=WizardPanel.vue.d.ts.map