import type { FolderNode } from '../types';
interface Props {
    node: FolderNode;
    depth: number;
    activeFileId?: string | null;
    expanded: Set<string>;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    toggle: any;
    "open-file": any;
    "delete-file": any;
    "move-file": any;
    "upload-files": any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onToggle?: ((...args: any) => any) | undefined;
    "onOpen-file"?: ((...args: any) => any) | undefined;
    "onDelete-file"?: ((...args: any) => any) | undefined;
    "onMove-file"?: ((...args: any) => any) | undefined;
    "onUpload-files"?: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=FileTreeNode.vue.d.ts.map