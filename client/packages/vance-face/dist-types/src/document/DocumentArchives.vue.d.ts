import type { DocumentDto } from '@vance/generated';
/**
 * Versions panel slotted into the document detail view. Shows the count
 * (always visible as a header), expands into a date-sorted list, lets
 * the user preview / delete / restore individual versions.
 *
 * <p>Read-only — there is no "edit this archived version" path. Restoring
 * brings the version back as the live document; the previous live content
 * becomes a new archive entry, so the user never loses their current work
 * accidentally.
 */
type __VLS_Props = {
    /** Live document the panel is attached to. {@code null} clears the list. */
    document: DocumentDto | null;
};
declare const _default: import("vue").DefineComponent<__VLS_Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    restored: (restored: DocumentDto) => any;
    "update:count": (count: number) => any;
}, string, import("vue").PublicProps, Readonly<__VLS_Props> & Readonly<{
    onRestored?: ((restored: DocumentDto) => any) | undefined;
    "onUpdate:count"?: ((count: number) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=DocumentArchives.vue.d.ts.map