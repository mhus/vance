import './global.css';
// `bootNative` is imported for its side effect — it calls
// `configurePlatform` from `@vance/shared` before any other module
// touches storage, REST, or WebSocket. Must be the first non-CSS
// import in this file.
import { bootStoragePromise } from './src/platform/bootNative';

import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';

export default function App() {
  const [ready, setReady] = useState(false);
  const [bootError, setBootError] = useState<string | null>(null);

  useEffect(() => {
    bootStoragePromise.then(
      () => setReady(true),
      (e: unknown) => setBootError(e instanceof Error ? e.message : String(e)),
    );
  }, []);

  return (
    <SafeAreaProvider>
      <SafeAreaView className="flex-1 items-center justify-center bg-white dark:bg-slate-900 px-6">
        {bootError ? (
          <View className="items-center gap-2">
            <Text className="text-base font-semibold text-red-600 dark:text-red-400">
              Boot failed
            </Text>
            <Text className="text-sm text-slate-600 dark:text-slate-400 text-center">
              {bootError}
            </Text>
          </View>
        ) : (
          <View className="items-center gap-2">
            <Text className="text-2xl font-semibold text-slate-900 dark:text-slate-100">
              Vance Mobile
            </Text>
            <Text className="text-sm text-slate-600 dark:text-slate-400">
              {ready ? 'Phase A — boot adapter ready ✓' : 'Loading platform…'}
            </Text>
          </View>
        )}
        <StatusBar style="auto" />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
