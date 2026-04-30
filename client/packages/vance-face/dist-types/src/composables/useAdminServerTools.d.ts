import { type Ref } from 'vue';
import type { ServerToolDto, ServerToolWriteRequest, ToolTypeDto } from '@vance/generated';
/**
 * Read + write configured server tools at one project. The
 * {@code _vance} system project is selected like any other project —
 * the editor surfaces it explicitly so tenant-wide defaults can be
 * managed without a separate admin UI.
 *
 * <p>The cascade fallback to bundled bean tools is <b>not</b>
 * surfaced here — bean tools are not editable. Use {@code recipe_list}
 * / runtime tooling to see what bean tools are visible.
 */
export declare function useAdminServerTools(): {
    tools: Ref<ServerToolDto[]>;
    types: Ref<ToolTypeDto[]>;
    loading: Ref<boolean>;
    busy: Ref<boolean>;
    error: Ref<string | null>;
    loadProject: (projectId: string) => Promise<void>;
    loadTypes: () => Promise<void>;
    upsert: (projectId: string, name: string, body: ServerToolWriteRequest) => Promise<ServerToolDto>;
    remove: (projectId: string, name: string) => Promise<void>;
};
//# sourceMappingURL=useAdminServerTools.d.ts.map