import { Pressable } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { logoutLocal } from '@/auth';
import { resetToLogin } from '@/navigation/navigationRef';

/**
 * Trailing-slot icon for {@link MobileShell}'s header. Signs the
 * active account out. If another account remains in the inventory,
 * the platform automatically switches to the most-recently-used one
 * (handled inside {@code logoutLocal}); otherwise we navigate to
 * Login. Stays visible across all tabs until Phase D+ introduces a
 * proper user menu.
 */
export function SignOutButton() {
  async function onPress() {
    const next = await logoutLocal();
    if (next === null) {
      resetToLogin();
    }
    // When `next` is non-null, App.tsx's accountStore subscriber
    // flips the RootNavigator key and re-mounts the whole tree —
    // no manual navigation reset needed.
  }
  return (
    <Pressable
      onPress={() => {
        void onPress();
      }}
      hitSlop={8}
      className="p-2"
      accessibilityRole="button"
      accessibilityLabel="Sign out"
    >
      <Ionicons name="log-out-outline" size={22} color="#475569" />
    </Pressable>
  );
}
