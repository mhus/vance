import type { TodoItem } from '@vance/generated';
type ProcessModeName = 'NORMAL' | 'EXPLORING' | 'PLANNING' | 'EXECUTING';
type __VLS_Props = {
    mode: ProcessModeName;
    todos: TodoItem[];
    planMeta: {
        version: number;
        summary?: string;
    } | null;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=PlanModeIndicator.vue.d.ts.map