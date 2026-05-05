import { createNativeStackNavigator } from '@react-navigation/native-stack';
import InboxListScreen from '@/screens/inbox/InboxListScreen';
import InboxDetailScreen from '@/screens/inbox/InboxDetailScreen';
import type { InboxStackParamList } from './types';

const Stack = createNativeStackNavigator<InboxStackParamList>();

/**
 * Inbox-tab stack — List → Detail. Nested inside `MainTabs` as the
 * `Inbox` tab's component, so the bottom tab bar stays visible
 * across both screens (and per the platform's UX convention,
 * navigating back from the detail returns to the same list scroll).
 */
export function InboxStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      <Stack.Screen name="InboxList" component={InboxListScreen} />
      <Stack.Screen name="InboxDetail" component={InboxDetailScreen} />
    </Stack.Navigator>
  );
}
