import { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { clearRememberedLogin, getRememberedLogin, setRememberedLogin } from '@vance/shared';
import { LoginError, login } from '@/auth';
import { VAlert, VButton, VInput } from '@/components';
import type { RootStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<RootStackParamList, 'Login'>;

/**
 * Initial sign-in. Pre-fills the (tenant, username) hint when the
 * user ticked "remember user" on a previous successful login;
 * password is never persisted.
 */
export default function LoginScreen() {
  const nav = useNavigation<Nav>();
  const [tenant, setTenant] = useState('default');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const r = getRememberedLogin();
    if (r) {
      setTenant(r.tenant);
      setUsername(r.username);
      setRemember(true);
    }
  }, []);

  const submitDisabled = submitting || username.trim() === '' || password === '';

  async function onSubmit() {
    if (submitDisabled) return;
    setError(null);
    setSubmitting(true);
    const t = tenant.trim();
    const u = username.trim();
    try {
      await login({ tenant: t, username: u, password });
      if (remember) {
        setRememberedLogin({ tenant: t, username: u });
      } else {
        clearRememberedLogin();
      }
      setPassword('');
      nav.reset({ index: 0, routes: [{ name: 'Main' }] });
    } catch (e) {
      if (e instanceof LoginError) {
        setError(
          e.status === 401 ? 'Invalid credentials.' : `Login failed (${e.status}).`,
        );
      } else {
        setError('Login failed — check the brain URL and your network.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <SafeAreaView className="flex-1 bg-white dark:bg-slate-950">
      <View className="flex-1 px-6 justify-center">
        <View className="gap-6">
          <View className="gap-1">
            <Text className="text-3xl font-semibold text-slate-900 dark:text-slate-100">
              Vance
            </Text>
            <Text className="text-sm text-slate-500 dark:text-slate-400">
              Sign in to your tenant
            </Text>
          </View>

          <View className="gap-4">
            <VInput
              label="Tenant"
              value={tenant}
              onChangeText={setTenant}
              autoCapitalize="none"
              autoCorrect={false}
              editable={!submitting}
            />
            <VInput
              label="Username"
              value={username}
              onChangeText={setUsername}
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="username"
              editable={!submitting}
            />
            <VInput
              label="Password"
              value={password}
              onChangeText={setPassword}
              secureTextEntry
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="current-password"
              editable={!submitting}
            />

            <Pressable
              onPress={() => setRemember((v) => !v)}
              className="flex-row items-center gap-2 py-1"
              accessibilityRole="checkbox"
              accessibilityState={{ checked: remember }}
            >
              <View
                className={`h-5 w-5 rounded border items-center justify-center ${
                  remember
                    ? 'bg-blue-600 border-blue-600'
                    : 'bg-transparent border-slate-400 dark:border-slate-600'
                }`}
              >
                {remember ? <Text className="text-white text-xs">✓</Text> : null}
              </View>
              <Text className="text-sm text-slate-700 dark:text-slate-300">
                Remember tenant and username
              </Text>
            </Pressable>
          </View>

          {error !== null ? <VAlert variant="error">{error}</VAlert> : null}

          <VButton
            variant="primary"
            size="lg"
            loading={submitting}
            disabled={submitDisabled}
            onPress={onSubmit}
          >
            Sign in
          </VButton>
        </View>
      </View>
    </SafeAreaView>
  );
}
