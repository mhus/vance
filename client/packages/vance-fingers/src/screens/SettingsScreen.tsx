import { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import {
  type Account,
  currentAccount,
  describeAccount,
  listAccounts,
  logoutLocal,
  subscribeAccounts,
} from '@/auth';
import { resetToLogin } from '@/navigation/navigationRef';
import { MobileShell } from '@/components';
import { AccountSwitchModal } from './AccountSwitchModal';

/**
 * Settings entry-point. Hosts the multi-account section (current
 * account display, switch, sign-out) plus future toggles (theme,
 * voice, …). Each row uses the uniform {@link Row} primitive so a
 * future row only needs to drop in.
 */
export default function SettingsScreen() {
  const [active, setActive] = useState<Account | null>(() => currentAccount());
  const [accountCount, setAccountCount] = useState<number>(() => listAccounts().length);
  const [switcherOpen, setSwitcherOpen] = useState<boolean>(false);

  useEffect(() => {
    return subscribeAccounts(() => {
      setActive(currentAccount());
      setAccountCount(listAccounts().length);
    });
  }, []);

  async function onSignOut() {
    const next = await logoutLocal();
    if (next === null) {
      resetToLogin();
    }
    // Otherwise: App.tsx's accountStore subscriber flips the
    // RootNavigator key and re-mounts the whole tree.
  }

  return (
    <MobileShell title="Settings">
      <View className="flex-1">
        <View className="px-4 py-4 bg-slate-50 dark:bg-slate-900 border-b border-slate-200 dark:border-slate-800">
          <Text className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
            Signed in as
          </Text>
          <Text className="text-base font-semibold text-slate-900 dark:text-slate-100 mt-0.5">
            {active !== null ? describeAccount(active) : '—'}
          </Text>
          <Text
            className="text-xs text-slate-500 dark:text-slate-400 mt-0.5"
            numberOfLines={1}
          >
            {active !== null ? active.brainUrl : ''}
          </Text>
        </View>

        <Row
          icon="people-outline"
          label={
            accountCount > 1
              ? `Switch account (${accountCount})`
              : 'Switch account'
          }
          onPress={() => setSwitcherOpen(true)}
        />
        <Row
          icon="log-out-outline"
          label="Sign out"
          danger
          onPress={() => {
            void onSignOut();
          }}
        />
      </View>

      <AccountSwitchModal
        visible={switcherOpen}
        onClose={() => setSwitcherOpen(false)}
      />
    </MobileShell>
  );
}

interface RowProps {
  icon: React.ComponentProps<typeof Ionicons>['name'];
  label: string;
  onPress?: () => void;
  danger?: boolean;
  disabled?: boolean;
}

function Row({ icon, label, onPress, danger = false, disabled = false }: RowProps) {
  const labelClass = danger
    ? 'text-base text-red-600 dark:text-red-400'
    : 'text-base text-slate-900 dark:text-slate-100';
  const iconColor = danger ? '#dc2626' : disabled ? '#94a3b8' : '#475569';
  return (
    <Pressable
      onPress={disabled ? undefined : onPress}
      disabled={disabled}
      className={`flex-row items-center gap-3 px-4 py-3 border-b border-slate-100 dark:border-slate-900 ${
        disabled
          ? 'opacity-50'
          : 'active:bg-slate-50 dark:active:bg-slate-900'
      }`}
    >
      <Ionicons name={icon} size={22} color={iconColor} />
      <Text className={`flex-1 ${labelClass}`}>{label}</Text>
      {!disabled ? (
        <Ionicons name="chevron-forward" size={18} color="#94a3b8" />
      ) : null}
    </Pressable>
  );
}
