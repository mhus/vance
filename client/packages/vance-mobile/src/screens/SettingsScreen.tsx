import { Pressable, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { getTenantId, getUsername } from '@vance/shared';
import { logoutLocal } from '@/auth';
import { resetToLogin } from '@/navigation/navigationRef';
import { MobileShell } from '@/components';

/**
 * Settings entry-point. Phase F+1 lands the logout action here so
 * each tab's header is free for tab-specific affordances; future
 * phases will fill the list with profile, voice, brain-URL, theme
 * choices etc.
 *
 * Section pattern: a list of `<Row>` items rendered uniformly so a
 * future row only needs to drop in.
 */
export default function SettingsScreen() {
  const tenant = getTenantId();
  const username = getUsername();

  function onSignOut() {
    logoutLocal();
    resetToLogin();
  }

  return (
    <MobileShell title="Settings">
      <View className="flex-1">
        <View className="px-4 py-4 bg-slate-50 dark:bg-slate-900 border-b border-slate-200 dark:border-slate-800">
          <Text className="text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
            Signed in as
          </Text>
          <Text className="text-base font-semibold text-slate-900 dark:text-slate-100 mt-0.5">
            {username ?? '—'}
          </Text>
          <Text className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            Tenant: {tenant ?? '—'}
          </Text>
        </View>

        <Row
          icon="log-out-outline"
          label="Sign out"
          danger
          onPress={onSignOut}
        />
      </View>
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
