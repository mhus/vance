import type { AppliedSettingDto } from '@vance/generated';
/**
 * Wrapper around {@link FormFields} for a single Setting Form. Owns
 * the apply / validate / reset workflow and renders the live cascade
 * values for each direct-mapped field as a Card above the form.
 *
 * <p>Inputs:
 * <ul>
 *   <li>{@code name} — form name to load (required)</li>
 *   <li>{@code projectId} — optional; passed through to the brain so
 *       the cascade and live values come from the right context</li>
 *   <li>{@code reloadKey} — bump to force a reload of the definition,
 *       e.g. after the user switched projects</li>
 * </ul>
 *
 * <p>Pebble templates never reach this component — the brain strips
 * them server-side before responding. Only the field schema +
 * {@code currentValue}/{@code currentSource} per direct-mapped field
 * and the {@code settings:} catalogue come over the wire.
 */
interface Props {
    name: string;
    projectId?: string;
    reloadKey?: string | number;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    applied: (applied: AppliedSettingDto[]) => any;
    closed: () => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onApplied?: ((applied: AppliedSettingDto[]) => any) | undefined;
    onClosed?: (() => any) | undefined;
}>, {
    projectId: string;
    reloadKey: string | number;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=SettingFormView.vue.d.ts.map