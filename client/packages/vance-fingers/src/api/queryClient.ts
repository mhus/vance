import { QueryClient } from '@tanstack/react-query';

/**
 * Global TanStack Query client. Defaults err on the side of "let
 * stale data show, refetch eagerly on focus / pull-to-refresh".
 *
 * - {@code staleTime: 30_000} — content is treated as fresh for 30s
 *   so tapping in/out of a list does not re-fire on every focus.
 * - {@code retry: 1} — single retry on network blips; the REST
 *   client itself already handles a 401 → refresh → retry round.
 * - {@code refetchOnReconnect: true} — Mobile loses LTE/WiFi
 *   regularly; coming back online should refresh visible queries.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnReconnect: true,
    },
    mutations: {
      retry: 0,
    },
  },
});
