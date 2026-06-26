import type { SessionParticipantDto } from '@vance/generated';
/**
 * Avatar stack for a multi-user session — drop into the Cortex
 * top-bar or chat header. Shows initials + a colour derived
 * deterministically from the user id so the same participant keeps
 * the same chip across the session. Tooltip on each avatar reveals
 * the full display name.
 *
 * <p>Hidden when only one participant is present — there is no
 * collaboration to display in that case.
 *
 * <p>See {@code planning/multi-user-sessions.md} §6 / §7.
 */
interface Props {
    participants: SessionParticipantDto[];
    /** How many avatars to render before collapsing into a +N badge. */
    max?: number;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {
    max: number;
}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=SessionParticipants.vue.d.ts.map