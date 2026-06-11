import type { ProjectGroupSummary, ProjectSummary } from '@vance/generated';
/**
 * Reusable project picker for editor sidebars. Renders the tenant's
 * projects grouped by their {@link ProjectSummary.projectGroupId},
 * with an optional filter input and selection-highlight. Hosts use
 * this in their {@code #sidebar} slot to avoid re-implementing the
 * same project list across chat, documents, scopes, …
 *
 * <p>Read-only labels (heading, filter placeholder, …) come in as
 * plain string props so each host can wire its own translation
 * namespace. The edit-mode strings (Add Group / Add Project modals)
 * use vue-i18n directly under {@code common.projectPicker.*} so the
 * same form behaviour ships to every host without per-host label
 * boilerplate. Mirrors the {@code SessionHeader} / {@code SettingFormView}
 * pattern for domain widgets.
 *
 * <p>Drag-and-drop reordering between groups will land in a
 * follow-up via a {@code move-project} emit.
 */
/**
 * Tree-selection variant. Hosts in tree mode (scopes admin) use
 * {@link Props.showGroupRows} + the {@code selectedNode} v-model
 * to track which node — group or project — is currently chosen.
 * The simpler {@code selectedProject} v-model still works for
 * project-only flows (chat, documents).
 */
export interface PickerNode {
    kind: 'group' | 'project';
    name: string;
}
interface Props {
    groups: ProjectGroupSummary[];
    projects: ProjectSummary[];
    loading?: boolean;
    error?: string | null;
    /** When true, render a filter input above the list. */
    searchEnabled?: boolean;
    /** When true, render Add-Group / Add-Project buttons. Server still
     *  enforces tenant-admin permission; non-admins get a 403 surfaced
     *  via the modal's inline error. */
    editEnabled?: boolean;
    /** Render group rows as clickable buttons (tree mode). Without
     *  this the group label is just a dim divider and only projects
     *  are selectable. Pairs with the {@code selectedNode} v-model. */
    showGroupRows?: boolean;
    /** Kit dropdown options for the create-project modal. When set,
     *  the component renders exactly these — first entry should be a
     *  "no kit" sentinel (blank value). When left unset the component
     *  loads the tenant's project-kits catalog on mount and builds
     *  the dropdown itself; pass {@link hideKitField}=true to opt out
     *  of the kit field entirely. */
    kitOptions?: {
        value: string;
        label: string;
    }[];
    /** Explicitly hide the kit field even when a catalog exists.
     *  Useful for hosts where project creation is meant to be
     *  intentionally minimal. */
    hideKitField?: boolean;
    /** Heading shown above the list (e.g. "Projekte"). When blank
     *  the heading row is suppressed entirely — useful when the host
     *  already paints its own section label. */
    heading?: string;
    /** Placeholder for the filter input. */
    filterPlaceholder?: string;
    /** Label used for the "Ohne Gruppe" / "Ungrouped" group block. */
    ungroupedLabel?: string;
    /** Message when {@link projects} is empty (no filter active).
     *  Pass an empty string to suppress the empty state. */
    emptyHeadline?: string;
    emptyBody?: string;
}
type __VLS_Props = Props;
type __VLS_PublicProps = __VLS_Props & {
    /**
     * Two-way bound project selection — used by hosts that only track
     * project selection (chat, documents). Writes from the host
     * (popstate, URL hydration, …) flow in; user clicks flow out.
     */
    'selectedProject'?: string | null;
    /**
     * Two-way bound tree selection — used by hosts that need to track
     * group + project selection together (scopes). Mutually exclusive
     * in practice with {@link selectedProject}, but the component
     * keeps both v-models so a host doesn't have to bridge them.
     */
    'selectedNode'?: PickerNode | null;
};
declare var __VLS_1: {}, __VLS_16: {}, __VLS_30: {
    kind: string;
    item: ProjectGroupSummary;
}, __VLS_32: {
    kind: string;
    item: ProjectSummary;
}, __VLS_38: {
    filter: string;
};
type __VLS_Slots = {} & {
    'header-extra'?: (props: typeof __VLS_1) => any;
} & {
    loading?: (props: typeof __VLS_16) => any;
} & {
    'row-suffix'?: (props: typeof __VLS_30) => any;
} & {
    'row-suffix'?: (props: typeof __VLS_32) => any;
} & {
    'filter-no-match'?: (props: typeof __VLS_38) => any;
};
declare const __VLS_component: import("vue").DefineComponent<__VLS_PublicProps, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {
    "update:selectedProject": (value: string | null) => any;
    "update:selectedNode": (value: PickerNode | null) => any;
} & {
    "project-pick": (payload: {
        name: string;
        title: string;
    }) => any;
    "group-pick": (payload: {
        name: string;
        title: string;
    }) => any;
    "focus-main": () => any;
    "data-changed": (payload: {
        kind: "group" | "project";
        name: string;
    }) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_PublicProps> & Readonly<{
    "onProject-pick"?: ((payload: {
        name: string;
        title: string;
    }) => any) | undefined;
    "onGroup-pick"?: ((payload: {
        name: string;
        title: string;
    }) => any) | undefined;
    "onFocus-main"?: (() => any) | undefined;
    "onData-changed"?: ((payload: {
        kind: "group" | "project";
        name: string;
    }) => any) | undefined;
    "onUpdate:selectedProject"?: ((value: string | null) => any) | undefined;
    "onUpdate:selectedNode"?: ((value: PickerNode | null) => any) | undefined;
}>, {
    error: string | null;
    loading: boolean;
    heading: string;
    searchEnabled: boolean;
    editEnabled: boolean;
    showGroupRows: boolean;
    kitOptions: {
        value: string;
        label: string;
    }[];
    hideKitField: boolean;
    filterPlaceholder: string;
    ungroupedLabel: string;
    emptyHeadline: string;
    emptyBody: string;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
declare const _default: __VLS_WithSlots<typeof __VLS_component, __VLS_Slots>;
export default _default;
type __VLS_WithSlots<T, S> = T & {
    new (): {
        $slots: S;
    };
};
//# sourceMappingURL=ProjectListSidebar.vue.d.ts.map