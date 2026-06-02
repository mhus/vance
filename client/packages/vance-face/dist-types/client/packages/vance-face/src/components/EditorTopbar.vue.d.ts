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
     * Connection-state dot. Only WS editors set this; REST-only editors
     * omit the prop and the dot is hidden. See `specification/web-ui.md`
     * §6.4 for the state semantics.
     */
    connectionState?: 'connected' | 'idle' | 'occupied';
    /** Optional tooltip override; defaults to a sensible per-state label. */
    connectionTooltip?: string;
    /**
     * When set, the topbar renders a "?" help toggle. The drawer itself
     * is rendered by the parent {@code <EditorShell>} (it reclaims the
     * right-panel area). The topbar only owns the button; the open-state
     * is two-way-bound via {@link helpOpen} + the {@code toggle-help}
     * emit.
     */
    helpPath?: string;
    /** Reflects whether the help drawer is currently open. */
    helpOpen?: boolean;
    /**
     * When true, the page title renders as a clickable element that
     * emits {@code title-click} on activation. Used by editors with a
     * sidebar zone to let users jump back to the navigation. Visually
     * the title gets a pointer cursor + subtle hover effect.
     */
    titleClickable?: boolean;
}
declare var __VLS_1: {};
type __VLS_Slots = {} & {
    'topbar-extra'?: (props: typeof __VLS_1) => any;
};
declare const __VLS_component: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "toggle-help": () => any;
    "title-click": () => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onToggle-help"?: (() => any) | undefined;
    "onTitle-click"?: (() => any) | undefined;
}>, {
    breadcrumbs: Crumb[];
    helpOpen: boolean;
    titleClickable: boolean;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
declare const _default: __VLS_WithSlots<typeof __VLS_component, __VLS_Slots>;
export default _default;
type __VLS_WithSlots<T, S> = T & {
    new (): {
        $slots: S;
    };
};
//# sourceMappingURL=EditorTopbar.vue.d.ts.map