import { ref, type Ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type {
  ServerToolDto,
  ServerToolOAuthStartDto,
  ServerToolTestResultDto,
  ToolTypeDto,
} from '@vance/generated';
import { brainFetch, RestError } from '@vance/shared';

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
 *
 * <p>The type catalog needs tenant admin; a project admin editing a tool
 * gets a 403 there and the form simply offers no type list (like
 * {@code useToolTemplates.forbidden}). Every operation starts by clearing
 * {@code error}, so a banner always belongs to the latest attempt.
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
  const { t } = useI18n();

  function messageOf(e: unknown, fallbackKey: string): string {
    return e instanceof Error && e.message ? e.message : t(fallbackKey);
  }

  async function loadTypes(): Promise<void> {
    error.value = null;
    try {
      types.value = await brainFetch<ToolTypeDto[]>('GET', 'admin/server-tool-types');
    } catch (e) {
      types.value = [];
      // Tenant-admin endpoint: without that right there is no catalog to
      // offer, which is not an error of this form.
      if (e instanceof RestError && e.status === 403) return;
      error.value = messageOf(e, 'tools.errors.loadTypesFailed');
    }
  }

  async function loadOne(projectId: string, name: string): Promise<void> {
    error.value = null;
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
      error.value = messageOf(e, 'tools.errors.testFailed');
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
      error.value = messageOf(e, 'tools.errors.loginFailed');
      busy.value = false;
      throw e;
    }
    if (!authorizationUrl) {
      error.value = t('tools.errors.loginFailed');
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
      error.value = messageOf(e, 'tools.errors.logoutFailed');
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { types, current, busy, error, loadTypes, loadOne, test, login, logout };
}
