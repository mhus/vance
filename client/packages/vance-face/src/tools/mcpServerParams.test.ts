import { describe, expect, it } from 'vitest';
import { paramsFromState, parseMap, stateFromParams } from './mcpServerParams';

describe('mcpServerParams', () => {
  it('round-trips an OAuth HTTP server and keeps unknown keys', () => {
    const params = {
      transport: 'http',
      url: 'https://mcp.example.com/mcp',
      auth: { type: 'oauth', loginScope: 'tenant', scopes: ['read', 'write'], custom: 'kept' },
      tls: { skipVerification: true },
      postUrl: 'unknown-to-the-form',
    };

    const state = stateFromParams(params);
    expect(state.authType).toBe('oauth');
    expect(state.loginScope).toBe('tenant');
    expect(state.scopes).toBe('read write');

    expect(paramsFromState(params, state)).toEqual({
      transport: 'http',
      url: 'https://mcp.example.com/mcp',
      auth: { type: 'oauth', loginScope: 'tenant', scopes: 'read write', custom: 'kept' },
      tls: { skipVerification: true },
      postUrl: 'unknown-to-the-form',
    });
  });

  it('switching the auth type drops the fields of the old one', () => {
    const params = { transport: 'http', url: 'https://x/mcp', auth: { type: 'bearer', token: 'T' } };
    const state = stateFromParams(params);
    state.authType = 'oauth';

    expect(paramsFromState(params, state).auth).toEqual({ type: 'oauth' });

    state.authType = 'none';
    expect(paramsFromState(params, state)).not.toHaveProperty('auth');
  });

  it('personal login scope is the default and is not written', () => {
    const params = { transport: 'http', url: 'https://x/mcp', auth: { type: 'oauth' } };
    const state = stateFromParams(params);

    expect(state.loginScope).toBe('user');
    expect(paramsFromState(params, state).auth).toEqual({ type: 'oauth' });
  });

  it('builds a stdio server and drops the HTTP keys', () => {
    const params = { transport: 'http', url: 'https://x/mcp', auth: { type: 'oauth' } };
    const state = stateFromParams(params);
    state.transport = 'stdio';
    state.command = 'npx\n-y\n@modelcontextprotocol/server-filesystem\n';
    state.env = 'LOG_LEVEL=debug\nunfinished';
    state.timeoutSeconds = '30';

    expect(paramsFromState(params, state)).toEqual({
      transport: 'stdio',
      command: ['npx', '-y', '@modelcontextprotocol/server-filesystem'],
      env: { LOG_LEVEL: 'debug' },
      timeoutSeconds: 30,
    });
  });

  it('keeps an inline lease untouched', () => {
    const lease = { name: 'l', levels: { tenant: 2 } };
    const params = { transport: 'http', url: 'https://x/mcp', lease };
    const state = stateFromParams(params);

    expect(state.lease).toBe('');
    expect(paramsFromState(params, state).lease).toBe(lease);
  });

  it('parseMap ignores lines without a key', () => {
    expect(parseMap('A=1\n=2\nB = x=y\nnope')).toEqual({ A: '1', B: 'x=y' });
  });
});
