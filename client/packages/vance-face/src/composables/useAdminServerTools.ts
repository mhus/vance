import { ref, type Ref } from 'vue';
import type {
  ServerToolDto,
  ServerToolOAuthStartDto,
  ServerToolTestResultDto,
  ToolTypeDto,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * The operational side of one configured server tool — what the
 * {@code vance-server-tool} form view needs beyond editing the document:
 * the tool-type catalog, the cascade-resolved tool (with the caller's MCP
 * login state), the connection test and the MCP login itself.
 *
 * <p>Editing is not here: the tool <em>is</em> its document
 * ({@code _vance/server-tools/<name>.yaml}) and saves through the document
 * pipeline like every settings area. Everything below acts on the
 * <em>saved</em> state — the server resolves the tool by name.
 */
export function useAdminServerTools(): {
  types: Ref<ToolTypeDto[]>;
  current: Ref<ServerToolDto | null>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadTypes: () => Promise<void>;
  loadOne: (projectId: string, name: string) => Promise<void>;
  test: (projectId: string, name: string) => Promise<ServerToolTestResultDto>;
  login: (projectId: string, name: string, returnTo: string) => Promise<void>;
  logout: (projectId: string, name: string) => Promise<void>;
} {
  const types = ref<ToolTypeDto[]>([]);
  const current = ref<ServerToolDto | null>(null);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadTypes(): Promise<void> {
    try {
      types.value = await brainFetch<ToolTypeDto[]>('GET', 'admin/server-tool-types');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load tool types.';
      types.value = [];
    }
  }

  async function loadOne(projectId: string, name: string): Promise<void> {
    try {
      current.value = await brainFetch<ServerToolDto>(
        'GET',
        `admin/projects/${encodeURIComponent(projectId)}/server-tools/${encodeURIComponent(name)}`,
      );
    } catch {
      // A tool the cascade does not resolve (unsaved draft, malformed
      // body) simply has no operational state yet.
      current.value = null;
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
      await loadOne(projectId, name);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Logout failed.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { types, current, busy, error, loadTypes, loadOne, test, login, logout };
}
