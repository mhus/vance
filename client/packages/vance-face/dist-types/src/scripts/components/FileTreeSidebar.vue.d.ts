import type { FolderNode } from '../types';
interface Props {
    root: FolderNode;
    activeFileId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "open-file": any;
    "new-file": any;
    "delete-file": any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onOpen-file"?: ((...args: any) => any) | undefined;
    "onNew-file"?: ((...args: any) => any) | undefined;
    "onDelete-file"?: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=FileTreeSidebar.vue.d.ts.map