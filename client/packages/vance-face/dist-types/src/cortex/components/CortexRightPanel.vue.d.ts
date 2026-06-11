import type { CortexDocument } from '../types';
import type { CortexClientToolService } from '../clientToolService';
interface Props {
    sessionId: string;
    projectId: string;
    toolService?: CortexClientToolService | null;
    activeDocument: CortexDocument | null;
}
declare const _default: import("vue").DefineComponent<Props, {}, {}, {}, {}, import("vue").ComponentOptionsMixin, import("vue").ComponentOptionsMixin, {}, string, import("vue").PublicProps, Readonly<Props> & Readonly<{}>, {}, {}, {}, {}, string, import("vue").ComponentProvideOptions, false, {}, any>;
export default _default;
//# sourceMappingURL=CortexRightPanel.vue.d.ts.map