import type { ScriptFile } from '../types';
interface Props {
    file: ScriptFile;
}
declare function runQuick(): Promise<void>;
declare function runDeep(): Promise<void>;
declare const _default: import("vue").DefineComponent<Props, {
    runQuick: typeof runQuick;
    runDeep: typeof runDeep;
}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=ValidatePanel.vue.d.ts.map