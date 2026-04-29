/**
 * A breadcrumb segment. Either a plain string label (immutable, no
 * navigation) or an object with an {@code onClick} handler that turns
 * the segment into a button — used to navigate back to a parent view
 * (e.g. from a process detail back to the owning session).
 */
export type Crumb = string | {
    text: string;
    onClick?: () => void;
};
interface Props {
    /** Page title shown in the topbar. */
    title: string;
    /** Breadcrumb segments left-to-right (e.g. `['Project foo', 'Session bar']`). */
    breadcrumbs?: Crumb[];
    /**
     * Connection-state dot in the topbar. Only WS editors set this; REST-only
     * editors omit the prop and the dot is hidden.
     *
     * Three states (see `specification/web-ui.md` §6.4):
     *  - `connected` (green) — WS bound to a session, live stream active
     *  - `idle`      (grey)  — picker mode, or transient reconnect
     *  - `occupied`  (red)   — last bind attempt was rejected with 409
     *                          (another connection holds the session lock)
     */
    connectionState?: 'connected' | 'idle' | 'occupied';
    /** Optional tooltip override; defaults to a sensible per-state label. */
    connectionTooltip?: string;
    /**
     * Doubles the default width of the right panel (320px → 640px). Use for
     * editors whose right panel hosts forms (e.g. settings editor).
     */
    wideRightPanel?: boolean;
    /**
     * App-style layout: the main slot becomes a fixed-height frame
     * (`overflow-hidden`) instead of the default page-scroll
     * (`overflow-y-auto`). Editors that own internal scroll regions —
     * chat with its message list + progress feed, future canvas
     * editors — opt into this so the page itself never scrolls.
     */
    fullHeight?: boolean;
}
declare var __VLS_1: {}, __VLS_3: {}, __VLS_5: {}, __VLS_7: {};
type __VLS_Slots = {} & {
    'topbar-extra'?: (props: typeof __VLS_1) => any;
} & {
    sidebar?: (props: typeof __VLS_3) => any;
} & {
    default?: (props: typeof __VLS_5) => any;
} & {
    'right-panel'?: (props: typeof __VLS_7) => any;
};
declare const __VLS_component: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    breadcrumbs: Crumb[];
    wideRightPanel: boolean;
    fullHeight: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
declare const _default: __VLS_WithSlots<typeof __VLS_component, __VLS_Slots>;
export default _default;
type __VLS_WithSlots<T, S> = T & {
    new (): {
        $slots: S;
    };
};
//# sourceMappingURL=EditorShell.vue.d.ts.map