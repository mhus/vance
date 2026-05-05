import type { WorkspaceTreeNodeDto } from '@vance/generated';
interface Props {
    node: WorkspaceTreeNodeDto;
    /** Reactive set of expanded folder paths. */
    expanded: Set<string>;
    /** Reactive set of paths whose children fetch is in flight. */
    loadingPaths: Set<string>;
    /** Currently-selected file path (for highlight); null when nothing selected. */
    selectedPath: string | null;
    /** Indentation depth — children pass parent + 1. */
    level: number;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    selectFile: (node: WorkspaceTreeNodeDto) => any;
    toggle: (path: string, isDir: boolean) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onSelectFile?: ((node: WorkspaceTreeNodeDto) => any) | undefined;
    onToggle?: ((path: string, isDir: boolean) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=WorkspaceTreeNode.vue.d.ts.map