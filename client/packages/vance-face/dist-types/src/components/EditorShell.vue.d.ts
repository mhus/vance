import { type Crumb } from './EditorTopbar.vue';
export type { Crumb };
/**
 * The four-zone layout's focus state. Drives column/row sizing,
 * background highlighting, and reclaim handle visibility when
 * {@link Props.focusModel} is {@code 'auto'}. See
 * `specification/web-ui.md` §7.2.1.
 */
export type FocusZone = 'main' | 'sidebar' | 'right' | 'footer';
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
     *
     * <p>Ignored when {@link focusModel} is {@code 'auto'} — focus mode
     * computes the right-panel width from {@link focusZone} instead.
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
    /**
     * Help resource path under {@code /brain/{tenant}/help/{lang}/}. When
     * set, EditorShell renders a "?" toggle in the topbar; clicking it
     * loads the markdown via {@link useHelp} and slides a help drawer
     * over the right-panel area. The drawer is closed by default so
     * editors that already render their own help in {@code #right-panel}
     * keep working unchanged — they just don't pass {@code helpPath}.
     *
     * <p>The drawer reclaims the right-panel space rather than adding a
     * fourth column: on smaller windows the cost of always-on help is
     * higher than the cost of one toggle click.
     */
    helpPath?: string;
    /**
     * Initial open-state for the help drawer when {@link helpPath} is
     * set. Default {@code false} — user opts in via the topbar toggle.
     * Editors that want the drawer pre-opened (e.g. the first time the
     * user visits) can flip this and persist the preference themselves.
     */
    helpOpen?: boolean;
    /**
     * Renders the page title as a clickable element that emits the
     * {@code title-click} event. Editors with a sidebar typically wire
     * this to focusing the sidebar; editors with a meaningful "back to
     * entry-point" (e.g. chat → session picker) wire it to that
     * navigation. EditorShell itself does not implement any default
     * behaviour for the click — the parent decides via the emitted event.
     */
    titleClickable?: boolean;
    /**
     * Focus-driven zone resizing. {@code 'off'} (default) keeps the
     * legacy fixed-width layout — sidebar 16rem, right panel 20rem (or
     * 40rem with {@link wideRightPanel}), no footer scaling, no zone
     * highlighting, no reclaim handles. {@code 'auto'} activates the
     * single-focus-zone model: the fokussierte zone bekommt mehr Platz
     * und einen hellen Background; die anderen schrumpfen auf eine
     * Kompakt-Breite und nehmen den Editor-Background an. Reclaim-Handles
     * an den Rändern bleiben klickbar, auch wenn eine Zone auf 0
     * kollabiert ist.
     *
     * <p>See `specification/web-ui.md` §7.2.1 for the full model.
     */
    focusModel?: 'off' | 'auto';
    /**
     * Explicit per-zone visibility overrides. When set to {@code false}
     * the corresponding zone is suppressed even if its slot is filled —
     * useful when the slot content depends on a state that briefly
     * renders empty (e.g. chat's picker mode keeps the {@code #footer}
     * slot template registered but the inner v-if hides the composer;
     * without this prop the editor shows an empty footer rail).
     * Default {@code undefined} = fall back to slot-presence detection.
     */
    showSidebar?: boolean;
    showRightPanel?: boolean;
    showFooter?: boolean;
}
type __VLS_Props = Props;
type __VLS_PublicProps = __VLS_Props & {
    /**
     * Currently focused zone. v-model'd so a parent can read it (or
     * preset it) but EditorShell owns the runtime updates via its
     * pointerdown/focusin/escape listeners. Ignored entirely when
     * {@link Props.focusModel} is {@code 'off'}.
     */
    'focusZone'?: FocusZone;
};
declare var __VLS_9: {}, __VLS_11: {}, __VLS_13: {}, __VLS_18: {}, __VLS_20: {};
type __VLS_Slots = {} & {
    'topbar-extra'?: (props: typeof __VLS_9) => any;
} & {
    sidebar?: (props: typeof __VLS_11) => any;
} & {
    default?: (props: typeof __VLS_13) => any;
} & {
    'right-panel'?: (props: typeof __VLS_18) => any;
} & {
    footer?: (props: typeof __VLS_20) => any;
};
declare const __VLS_component: import("vue").DefineComponent<__VLS_PublicProps, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {
    "title-click": () => any;
    "update:focusZone": (value: FocusZone) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_PublicProps> & Readonly<{
    "onTitle-click"?: (() => any) | undefined;
    "onUpdate:focusZone"?: ((value: FocusZone) => any) | undefined;
}>, {
    breadcrumbs: Crumb[];
    wideRightPanel: boolean;
    fullHeight: boolean;
    helpOpen: boolean;
    titleClickable: boolean;
    focusModel: "off" | "auto";
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
declare const _default: __VLS_WithSlots<typeof __VLS_component, __VLS_Slots>;
export default _default;
type __VLS_WithSlots<T, S> = T & {
    new (): {
        $slots: S;
    };
};
//# sourceMappingURL=EditorShell.vue.d.ts.map