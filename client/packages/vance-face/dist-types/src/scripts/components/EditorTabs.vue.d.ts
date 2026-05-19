import type { ScriptFile } from '../types';
interface Props {
    tabs: ScriptFile[];
    activeTabId?: string | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {} & {
    select: (id: string) => any;
    close: (id: string) => any;
}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{
    onSelect?: ((id: string) => any) | undefined;
    onClose?: ((id: string) => any) | undefined;
}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=EditorTabs.vue.d.ts.map