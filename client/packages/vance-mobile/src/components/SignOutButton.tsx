import { Pressable } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { logoutLocal } from '@/auth';
import { resetToLogin } from '@/navigation/navigationRef';

/**
 * Trailing-slot icon for {@link MobileShell}'s header. Wipes local
 * tokens and navigates back to Login. Stays visible across all tabs
 * until Phase D+ introduces a proper user menu.
 */
export function SignOutButton() {
  function onPress() {
    logoutLocal();
    resetToLogin();
  }
  return (
    <Pressable
      onPress={onPress}
      hitSlop={8}
      className="p-2"
      accessibilityRole="button"
      accessibilityLabel="Sign out"
    >
      <Ionicons name="log-out-outline" size={22} color="#475569" />
    </Pressable>
  );
}
