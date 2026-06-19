import type { CortexDocument } from '../types';
interface Props {
    tabs: CortexDocument[];
    activeTabId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    select: any;
    close: any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onSelect?: ((...args: any) => any) | undefined;
    onClose?: ((...args: any) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=EditorTabs.vue.d.ts.map