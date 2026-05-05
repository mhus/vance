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
import { ensureAuthenticated } from '@/auth';
import { navigationRef } from '@/navigation/navigationRef';
import { RootNavigator } from '@/navigation/RootNavigator';
import type { RootStackParamList } from '@/navigation/types';

type BootState =
  | { kind: 'loading' }
  | { kind: 'ready'; initialRoute: keyof RootStackParamList }
  | { kind: 'error'; message: string };

export default function App() {
  const [state, setState] = useState<BootState>({ kind: 'loading' });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        await bootStoragePromise;
        const authed = await ensureAuthenticated();
        if (cancelled) return;
        setState({ kind: 'ready', initialRoute: authed ? 'Main' : 'Login' });
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

  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        <NavigationContainer ref={navigationRef}>
          <RootNavigator initialRoute={state.initialRoute} />
          <StatusBar style="auto" />
        </NavigationContainer>
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}
