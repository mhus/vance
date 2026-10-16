// Conversion between the parameters of an `mcp_server` tool and the text
// state of its structured form (McpServerForm.vue). Pure functions so the
// form logic is testable without mounting; the server's McpConfig /
// McpOAuthSettings stay the authority on what the keys mean.

export type McpParams = Record<string, unknown>;
export type McpTransport = 'http' | 'stdio';
export type McpAuthType = 'none' | 'bearer' | 'basic' | 'apiKey' | 'oauth';
export type McpLoginScope = 'user' | 'project' | 'tenant';

/** Everything the form edits, as text. */
export interface McpFormState {
  transport: McpTransport;
  url: string;
  command: string;
  cwd: string;
  env: string;
  authType: McpAuthType;
  token: string;
  user: string;
  password: string;
  headerName: string;
  apiKeyValue: string;
  loginScope: McpLoginScope;
  scopes: string;
  clientId: string;
  clientSecret: string;
  skipVerification: boolean;
  trustedCaPem: string;
  timeoutSeconds: string;
  initTimeoutSeconds: string;
  defaultArgs: string;
  lease: string;
}

const AUTH_FIELD_KEYS = [
  'token', 'user', 'password', 'headerName', 'value', 'loginScope', 'scopes', 'clientId', 'clientSecret',
];

function asRecord(v: unknown): McpParams {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as McpParams) : {};
}

function stringOf(v: unknown): string {
  return typeof v === 'string' || typeof v === 'number' ? String(v) : '';
}

function linesOfMap(v: unknown): string {
  return Object.entries(asRecord(v)).map(([k, val]) => `${k}=${stringOf(val)}`).join('\n');
}

/** `KEY=value` per line; lines without a key are ignored. */
export function parseMap(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of text.split('\n')) {
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    out[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return out;
}

export function authTypeOf(raw: unknown): McpAuthType {
  const v = String(raw ?? 'none').toLowerCase();
  if (v === 'bearer' || v === 'basic' || v === 'oauth') return v;
  if (v === 'apikey' || v === 'api_key' || v === 'api-key') return 'apiKey';
  return 'none';
}

/** Whether the lease is an inline definition — the form edits only a lease name. */
export function hasInlineLease(params: McpParams): boolean {
  return params.lease !== null && typeof params.lease === 'object';
}

export function stateFromParams(params: McpParams): McpFormState {
  const auth = asRecord(params.auth);
  const tls = asRecord(params.tls);
  const scope = String(auth.loginScope ?? '').toLowerCase();
  return {
    transport: String(params.transport ?? 'http').toLowerCase() === 'stdio' ? 'stdio' : 'http',
    url: stringOf(params.url),
    command: Array.isArray(params.command) ? params.command.map(stringOf).join('\n') : stringOf(params.command),
    cwd: stringOf(params.cwd),
    env: linesOfMap(params.env),
    authType: authTypeOf(auth.type),
    token: stringOf(auth.token),
    user: stringOf(auth.user),
    password: stringOf(auth.password),
    headerName: stringOf(auth.headerName),
    apiKeyValue: stringOf(auth.value),
    loginScope: scope === 'project' || scope === 'tenant' ? scope : 'user',
    scopes: Array.isArray(auth.scopes) ? auth.scopes.map(stringOf).join(' ') : stringOf(auth.scopes),
    clientId: stringOf(auth.clientId),
    clientSecret: stringOf(auth.clientSecret),
    skipVerification: tls.skipVerification === true,
    trustedCaPem: stringOf(tls.trustedCaPem),
    timeoutSeconds: stringOf(params.timeoutSeconds),
    initTimeoutSeconds: stringOf(params.initTimeoutSeconds),
    defaultArgs: linesOfMap(params.defaultArgs),
    lease: hasInlineLease(params) ? '' : stringOf(params.lease),
  };
}

function put(target: McpParams, key: string, value: unknown): void {
  const empty = value === undefined || value === null || value === ''
    || (Array.isArray(value) && value.length === 0)
    || (typeof value === 'object' && !Array.isArray(value) && Object.keys(value as object).length === 0);
  if (empty) delete target[key];
  else target[key] = value;
}

function positive(text: string): number | undefined {
  const n = Number.parseInt(text, 10);
  return Number.isFinite(n) && n > 0 ? n : undefined;
}

function buildAuth(initialAuth: McpParams, state: McpFormState): McpParams | undefined {
  if (state.authType === 'none') return undefined;
  // Keys the form does not know survive as long as the type stays the same.
  const auth: McpParams = authTypeOf(initialAuth.type) === state.authType ? { ...initialAuth } : {};
  auth.type = state.authType;
  for (const k of AUTH_FIELD_KEYS) delete auth[k];
  switch (state.authType) {
    case 'bearer':
      put(auth, 'token', state.token.trim());
      break;
    case 'basic':
      put(auth, 'user', state.user.trim());
      put(auth, 'password', state.password.trim());
      break;
    case 'apiKey':
      put(auth, 'headerName', state.headerName.trim());
      put(auth, 'value', state.apiKeyValue.trim());
      break;
    case 'oauth':
      put(auth, 'loginScope', state.loginScope === 'user' ? undefined : state.loginScope);
      put(auth, 'scopes', state.scopes.trim());
      put(auth, 'clientId', state.clientId.trim());
      put(auth, 'clientSecret', state.clientSecret.trim());
      break;
  }
  return auth;
}

/**
 * Parameters for `state`, starting from `initial` — keys the form does not
 * know are kept; keys of the other transport are dropped.
 */
export function paramsFromState(initial: McpParams, state: McpFormState): McpParams {
  const out: McpParams = { ...initial };
  out.transport = state.transport;
  if (state.transport === 'http') {
    put(out, 'url', state.url.trim());
    put(out, 'auth', buildAuth(asRecord(initial.auth), state));
    const tls: McpParams = { ...asRecord(initial.tls) };
    put(tls, 'skipVerification', state.skipVerification ? true : undefined);
    put(tls, 'trustedCaPem', state.trustedCaPem.trim());
    put(out, 'tls', tls);
    for (const k of ['command', 'cwd', 'env']) delete out[k];
  } else {
    put(out, 'command', state.command.split('\n').map(x => x.trim()).filter(x => x.length > 0));
    put(out, 'cwd', state.cwd.trim());
    put(out, 'env', parseMap(state.env));
    for (const k of ['url', 'auth', 'tls']) delete out[k];
  }
  put(out, 'timeoutSeconds', positive(state.timeoutSeconds));
  put(out, 'initTimeoutSeconds', positive(state.initTimeoutSeconds));
  put(out, 'defaultArgs', parseMap(state.defaultArgs));
  if (!hasInlineLease(initial)) put(out, 'lease', state.lease.trim());
  return out;
}
