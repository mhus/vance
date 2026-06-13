import type { CortexDocument } from '../types';
interface Props {
    tabs: CortexDocument[];
    activeTabId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    close: (id: string) => any;
    select: (id: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onClose?: ((id: string) => any) | undefined;
    onSelect?: ((id: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=EditorTabs.vue.d.ts.map