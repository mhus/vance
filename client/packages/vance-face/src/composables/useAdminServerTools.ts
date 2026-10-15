import { ref, type Ref } from 'vue';
import type {
  ServerToolDto,
  ServerToolOAuthStartDto,
  ServerToolTestResultDto,
  ServerToolWriteRequest,
  ToolTypeDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

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
export function useAdminServerTools(): {
  tools: Ref<ServerToolDto[]>;
  types: Ref<ToolTypeDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadProject: (projectId: string) => Promise<void>;
  loadTypes: () => Promise<void>;
  upsert: (
    projectId: string,
    name: string,
    body: ServerToolWriteRequest,
  ) => Promise<ServerToolDto>;
  remove: (projectId: string, name: string) => Promise<void>;
  test: (projectId: string, name: string) => Promise<ServerToolTestResultDto>;
  login: (projectId: string, name: string, returnTo: string) => Promise<void>;
  logout: (projectId: string, name: string) => Promise<void>;
} {
  const tools = ref<ServerToolDto[]>([]);
  const types = ref<ToolTypeDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadProject(projectId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      tools.value = await brainFetch<ServerToolDto[]>(
        'GET',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load server tools.';
      tools.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadTypes(): Promise<void> {
    try {
      types.value = await brainFetch<ToolTypeDto[]>('GET', 'admin/server-tool-types');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tool types.';
      types.value = [];
    }
  }

  async function upsert(
    projectId: string,
    name: string,
    body: ServerToolWriteRequest,
  ): Promise<ServerToolDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<ServerToolDto>(
        'PUT',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools/${encodeURIComponent(name)}`,
        { body },
      );
      await loadProject(projectId);
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save server tool.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools/${encodeURIComponent(name)}`,
      );
      await loadProject(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete server tool.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  /** Connection test of an MCP server tool as the calling user. */
  async function test(projectId: string, name: string): Promise<ServerToolTestResultDto> {
    busy.value = true;
    error.value = null;
    try {
      return await brainFetch<ServerToolTestResultDto>(
        'POST',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools/${encodeURIComponent(name)}/test`,
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Connection test failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  /**
   * OAuth login for an MCP server tool. {@code mcp/init} is a POST: it makes
   * sure the server's OAuth provider exists (discovery / client registration
   * on first use — a failure comes back as a message here) and returns the
   * authorization URL; only then does the browser navigate into the code flow.
   */
  async function login(projectId: string, name: string, returnTo: string): Promise<void> {
    busy.value = true;
    error.value = null;
    const query = `project=${encodeURIComponent(projectId)}&tool=${encodeURIComponent(name)}`
      + `&returnTo=${encodeURIComponent(returnTo)}`;
    let authorizationUrl: string | undefined;
    try {
      const started = await brainFetch<ServerToolOAuthStartDto>('POST', `oauth-client/mcp/init?${query}`);
      authorizationUrl = started.authorizationUrl;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Login could not be started.';
      busy.value = false;
      throw e;
    }
    if (!authorizationUrl) {
      error.value = 'Login could not be started.';
      busy.value = false;
      return;
    }
    window.location.assign(authorizationUrl);
  }

  async function logout(projectId: string, name: string): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `oauth-client/mcp/connection?project=${encodeURIComponent(projectId)}&tool=${encodeURIComponent(name)}`,
      );
      await loadProject(projectId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Logout failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { tools, types, loading, busy, error, loadProject, loadTypes, upsert, remove, test, login, logout };
}
