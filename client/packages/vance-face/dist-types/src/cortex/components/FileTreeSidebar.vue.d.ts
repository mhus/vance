import type { FolderNode } from '../types';
interface Props {
    root: FolderNode;
    activeFileId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    "open-file": (id: string) => any;
    "delete-file": (id: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    "onOpen-file"?: ((id: string) => any) | undefined;
    "onDelete-file"?: ((id: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=FileTreeSidebar.vue.d.ts.map