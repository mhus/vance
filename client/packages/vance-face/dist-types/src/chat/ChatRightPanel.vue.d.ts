import type { ProcessProgressNotification } from '@vance/generated';
interface Props {
    events: ProcessProgressNotification[];
    projectId?: string;
    /** Active session id — only used by WizardPanel to decide when to refresh. */
    sessionKey?: string;
}
/**
 * Deep-link entry point used by ChatView's `vance:/wizards/...` link
 * handler. Switches the tab to wizards (so the panel is mounted),
 * then calls into {@link WizardPanel.openWizard} on the next tick.
 */
declare function openWizard(name: string, prefill?: Record<string, string>): void;
declare const _default: import("vue").DefineComponent<Props, {
    openWizard: typeof openWizard;
}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "prompt-ready": any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onPrompt-ready"?: ((...args: any) => any) | undefined;
}>, {
    projectId: string;
    sessionKey: string;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ChatRightPanel.vue.d.ts.map