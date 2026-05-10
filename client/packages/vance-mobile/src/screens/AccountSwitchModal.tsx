import { useEffect, useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import {
  type Account,
  currentAccountId,
  describeAccount,
  listAccounts,
  subscribeAccounts,
  switchToAccount,
} from '@/auth';
import { resetToLogin } from '@/navigation/navigationRef';
import { VButton } from '@/components';

interface Props {
  visible: boolean;
  onClose: () => void;
}

/**
 * Full-screen modal that lists every account registered on the device
 * and lets the user pick one (cold-restart switch via App.tsx's
 * RootNavigator re-mount) or kick off an Add-Account flow that
 * resets to the Login screen for a fresh sign-in.
 *
 * <p>Subscribes to {@link subscribeAccounts} so the list updates
 * live when an entry is added (e.g. after the user finishes the
 * Add-Account login flow and switches back to Settings) — without
 * the subscription the modal would render stale on its second open.
 */
export function AccountSwitchModal({ visible, onClose }: Props) {
  const [accounts, setAccounts] = useState<Account[]>(() => sortByLastUsed(listAccounts()));
  const [activeId, setActiveId] = useState<string | null>(() => currentAccountId());

  useEffect(() => {
    return subscribeAccounts(() => {
      setAccounts(sortByLastUsed(listAccounts()));
      setActiveId(currentAccountId());
    });
  }, []);

  async function onPick(account: Account) {
    if (account.id === activeId) {
      onClose();
      return;
    }
    onClose();
    // App.tsx's RootNavigator re-mounts on the resulting accountKey
    // change — no manual navigation needed here.
    await switchToAccount(account.id);
  }

  function onAddAccount() {
    onClose();
    resetToLogin();
  }

  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="pageSheet"
      onRequestClose={onClose}
    >
      <SafeAreaView className="flex-1 bg-white dark:bg-slate-950">
        {/* Header — dismiss + title */}
        <View className="flex-row items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-800">
          <Pressable onPress={onClose} hitSlop={12} accessibilityLabel="Close">
            <Ionicons name="close" size={24} color="#475569" />
          </Pressable>
          <Text className="text-base font-semibold text-slate-900 dark:text-slate-100">
            Switch account
          </Text>
          {/* Spacer mirroring the close button so the title stays centered */}
          <View style={{ width: 24 }} />
        </View>

        <ScrollView
          className="flex-1"
          contentContainerClassName="py-2"
          accessibilityRole="list"
        >
          {accounts.length === 0 ? (
            <View className="px-6 py-12 items-center gap-3">
              <Ionicons name="person-circle-outline" size={56} color="#94a3b8" />
              <Text className="text-sm text-center text-slate-500 dark:text-slate-400">
                No accounts yet. Sign in below.
              </Text>
            </View>
          ) : (
            accounts.map((account) => (
              <Pressable
                key={account.id}
                onPress={() => {
                  void onPick(account);
                }}
                className="flex-row items-center gap-3 px-4 py-3 border-b border-slate-100 dark:border-slate-900 active:bg-slate-50 dark:active:bg-slate-900"
                accessibilityRole="radio"
                accessibilityState={{ selected: account.id === activeId }}
              >
                <Ionicons
                  name={
                    account.id === activeId
                      ? 'radio-button-on'
                      : 'radio-button-off'
                  }
                  size={22}
                  color={account.id === activeId ? '#2563eb' : '#94a3b8'}
                />
                <View className="flex-1 min-w-0">
                  <Text
                    className="text-base font-medium text-slate-900 dark:text-slate-100"
                    numberOfLines={1}
                  >
                    {describeAccount(account)}
                  </Text>
                  <Text
                    className="text-xs text-slate-500 dark:text-slate-400"
                    numberOfLines={1}
                  >
                    {account.brainUrl}
                  </Text>
                </View>
              </Pressable>
            ))
          )}
        </ScrollView>

        <View className="border-t border-slate-200 dark:border-slate-800 px-4 py-3">
          <VButton variant="primary" size="lg" onPress={onAddAccount}>
            + Add account
          </VButton>
        </View>
      </SafeAreaView>
    </Modal>
  );
}

function sortByLastUsed(accounts: Account[]): Account[] {
  return [...accounts].sort((a, b) => b.lastUsedAt - a.lastUsedAt);
}
