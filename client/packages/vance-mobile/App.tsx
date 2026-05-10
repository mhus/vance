import './global.css';
// `bootNative` runs `configurePlatform` from `@vance/shared` at module
// load. Must come before any import that touches storage, REST or
// WebSocket — i.e. before everything else.
import { bootStoragePromise } from './src/platform/bootNative';

import { useEffect, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { NavigationContainer } from '@react-navigation/native';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from '@/api/queryClient';
import {
  currentAccountId,
  ensureAuthenticated,
  subscribeAccounts,
} from '@/auth';
import { navigationRef } from '@/navigation/navigationRef';
import { RootNavigator } from '@/navigation/RootNavigator';
import type { RootStackParamList } from '@/navigation/types';

type BootState =
  | { kind: 'loading' }
  | { kind: 'ready' }
  | { kind: 'error'; message: string };

const NO_ACCOUNT_KEY = '__no-account__';

export default function App() {
  const [state, setState] = useState<BootState>({ kind: 'loading' });
  // Drives the {@code <RootNavigator key=…>} re-mount on account
  // switch. Initialised inside the boot effect (where we know the
  // current id is resolved post-migration); mutated by the
  // subscribeAccounts handler whenever the active account flips.
  // Doubles as the source of truth for {@code initialRoute}: a
  // non-empty key means "active account exists, head straight to
  // Main"; the sentinel means "no signed-in account, show Login".
  const [accountKey, setAccountKey] = useState<string>(NO_ACCOUNT_KEY);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        await bootStoragePromise;
        // ensureAuthenticated either confirms the active mirror's
        // tokens are usable or refreshes them. Either way the active
        // account is the right one to mount; if no account is set
        // (fresh install / explicit sign-out), accountKey stays at
        // the sentinel and the navigator routes to Login.
        await ensureAuthenticated();
        if (cancelled) return;
        setAccountKey(currentAccountId() ?? NO_ACCOUNT_KEY);
        setState({ kind: 'ready' });
      } catch (e) {
        if (cancelled) return;
        setState({
          kind: 'error',
          message: e instanceof Error ? e.message : String(e),
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Listen for account inventory / active-account changes so the
  // RootNavigator re-mounts on switch / sign-out / add-account. The
  // subscription persists across the whole app lifetime; the cleanup
  // unhooks on hot-reload to avoid duplicate subscribers.
  useEffect(() => {
    const off = subscribeAccounts(() => {
      const next = currentAccountId() ?? NO_ACCOUNT_KEY;
      setAccountKey((prev) => (prev === next ? prev : next));
    });
    return off;
  }, []);

  if (state.kind === 'loading') {
    return (
      <SafeAreaProvider>
        <SafeAreaView className="flex-1 items-center justify-center bg-white dark:bg-slate-950">
          <ActivityIndicator />
          <StatusBar style="auto" />
        </SafeAreaView>
      </SafeAreaProvider>
    );
  }

  if (state.kind === 'error') {
    return (
      <SafeAreaProvider>
        <SafeAreaView className="flex-1 items-center justify-center bg-white dark:bg-slate-950 px-6">
          <View className="items-center gap-2">
            <Text className="text-base font-semibold text-red-600 dark:text-red-400">
              Boot failed
            </Text>
            <Text className="text-sm text-slate-600 dark:text-slate-400 text-center">
              {state.message}
            </Text>
          </View>
          <StatusBar style="auto" />
        </SafeAreaView>
      </SafeAreaProvider>
    );
  }

  // accountKey === sentinel ⇒ no active account ⇒ start at Login.
  // Anything else ⇒ active account exists ⇒ start at Main.
  // The same value drives the navigator's key, so any change (sign-in,
  // switch, sign-out) tears down and remounts the whole subtree.
  const initialRoute: keyof RootStackParamList =
    accountKey === NO_ACCOUNT_KEY ? 'Login' : 'Main';
  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        <NavigationContainer ref={navigationRef}>
          {/* Key on accountKey forces a full re-mount of the navigator
              on account switch — every screen's useEffect runs again,
              REST queries refetch, the WS hook reconnects to the new
              brain. Cold-restart semantics on a tap. */}
          <RootNavigator key={accountKey} initialRoute={initialRoute} />
          <StatusBar style="auto" />
        </NavigationContainer>
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}
