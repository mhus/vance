import {
  BrainWebSocket,
  StorageKeys,
  getRestConfig,
  getStorage,
  getTenantId,
} from '@vance/shared';

/**
 * Open a WebSocket to the Brain on the mobile profile. Reads the
 * tenant from the platform identity store and the bearer token
 * from the secure store; both must be set (the user is signed in).
 *
 * The handshake URL needs the token as a query parameter because
 * RN's WebSocket constructor — like the browser's — cannot send
 * custom HTTP headers on the upgrade request. The Brain's
 * `BrainAccessFilter` accepts `?token=` for that reason
 * (see `specification/websocket-protokoll.md` §2).
 */
export async function connectBrainWs(): Promise<BrainWebSocket> {
  const tenant = getTenantId();
  if (tenant === null) {
    throw new Error('Cannot open WebSocket: not signed in.');
  }
  const config = getRestConfig();
  const token =
    config.authMode === 'bearer'
      ? getStorage().secureStore.get(StorageKeys.authAccessToken) ?? undefined
      : undefined;
  return BrainWebSocket.connect({
    tenant,
    profile: 'mobile',
    clientVersion: '0.1.0',
    jwt: token,
  });
}
