import type { FileLoadResult } from '@/composables/useWorkspaceFile';
interface Props {
    /** Selected file's display name + path — for the header. */
    name: string | null;
    path: string | null;
    loading: boolean;
    error: string | null;
    result: FileLoadResult | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=WorkspaceFileViewer.vue.d.ts.map