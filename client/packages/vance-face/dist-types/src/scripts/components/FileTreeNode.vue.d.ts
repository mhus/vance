import type { FolderNode } from '../types';
interface Props {
    node: FolderNode;
    depth: number;
    activeFileId?: string | null;
    expanded: Set<string>;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    toggle: (path: string) => any;
    "open-file": (id: string) => any;
    "new-file": (parentPath: string) => any;
    "delete-file": (id: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onToggle?: ((path: string) => any) | undefined;
    "onOpen-file"?: ((id: string) => any) | undefined;
    "onNew-file"?: ((parentPath: string) => any) | undefined;
    "onDelete-file"?: ((id: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=FileTreeNode.vue.d.ts.map