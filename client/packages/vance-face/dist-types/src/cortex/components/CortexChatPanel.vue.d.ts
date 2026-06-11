import type { CortexClientToolService } from '../clientToolService';
interface Props {
    sessionId: string;
    projectId: string;
    /**
     * Owned by the parent app — single instance for the lifetime of the
     * Cortex view. Attached to the WS whenever a session goes live; the
     * brain pushes invocations through this same connection.
     */
    toolService?: CortexClientToolService | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CortexChatPanel.vue.d.ts.map