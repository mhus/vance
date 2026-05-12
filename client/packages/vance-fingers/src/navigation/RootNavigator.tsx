import { createNativeStackNavigator } from '@react-navigation/native-stack';
import LoginScreen from '@/screens/LoginScreen';
import { MainTabs } from './MainTabs';
import type { RootStackParamList } from './types';

const Stack = createNativeStackNavigator<RootStackParamList>();

interface Props {
  initialRoute: keyof RootStackParamList;
}

/**
 * Root stack: Login + Main. Phase B and C build on top of this.
 * The `Main` route hosts the bottom-tab navigator
 * (Inbox / Chat / Documents); per-tab stacks for detail screens
 * land in Phase D / E / F as nested navigators inside `MainTabs`.
 */
export function RootNavigator({ initialRoute }: Props) {
  return (
    <Stack.Navigator initialRouteName={initialRoute} screenOptions={{ headerShown: false }}>
      <Stack.Screen name="Login" component={LoginScreen} />
      <Stack.Screen name="Main" component={MainTabs} />
    </Stack.Navigator>
  );
}
