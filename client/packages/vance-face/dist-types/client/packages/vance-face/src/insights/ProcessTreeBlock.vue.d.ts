import type { ThinkProcessInsightsDto } from '@vance/generated';
export interface ProcessTreeNode {
    process: ThinkProcessInsightsDto;
    children: ProcessTreeNode[];
}
export type EventKind = 'spawn' | 'chat' | 'memory' | 'marvin' | 'pending';
export interface ProcessEvent {
    kind: EventKind;
    at: string | null;
    id: string;
    label: string;
    tag?: string;
    detail: string;
    detailIsMarkdown: boolean;
}
type __VLS_Props = {
    node: ProcessTreeNode;
    /** All event lists keyed by process id. */
    eventsByProcess: Record<string, ProcessEvent[]>;
    /** Process ids whose event-list is collapsed (defaulting to expanded). */
    collapsedProcesses: ReadonlySet<string>;
    /** Composite keys "{processId}|{eventId}" for currently-expanded events. */
    expandedEvents: ReadonlySet<string>;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "select-process": (id: string) => any;
    "toggle-process": (id: string) => any;
    "toggle-event": (processId: string, eventId: string) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    "onSelect-process"?: ((id: string) => any) | undefined;
    "onToggle-process"?: ((id: string) => any) | undefined;
    "onToggle-event"?: ((processId: string, eventId: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ProcessTreeBlock.vue.d.ts.map