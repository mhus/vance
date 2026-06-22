import type { FolderNode } from '../types';
interface Props {
    node: FolderNode;
    depth: number;
    activeFileId?: string | null;
    expanded: Set<string>;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "open-file": (id: string) => any;
    "delete-file": (id: string) => any;
    "move-file": (payload: {
        id: string;
        targetFolder: string;
    }) => any;
    "upload-files": (payload: {
        files: File[];
        targetFolder: string;
    }) => any;
    toggle: (path: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onOpen-file"?: ((id: string) => any) | undefined;
    "onDelete-file"?: ((id: string) => any) | undefined;
    "onMove-file"?: ((payload: {
        id: string;
        targetFolder: string;
    }) => any) | undefined;
    "onUpload-files"?: ((payload: {
        files: File[];
        targetFolder: string;
    }) => any) | undefined;
    onToggle?: ((path: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=FileTreeNode.vue.d.ts.map