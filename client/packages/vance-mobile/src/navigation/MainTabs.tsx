import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Ionicons } from '@expo/vector-icons';
import { ChatStack } from './ChatStack';
import { DocumentsStack } from './DocumentsStack';
import { InboxStack } from './InboxStack';
import SettingsScreen from '@/screens/SettingsScreen';
import type { MainTabParamList } from './types';

const Tab = createBottomTabNavigator<MainTabParamList>();

/**
 * Four-tab bottom navigator. Each work tab owns its own native stack
 * so the tab bar stays visible across List → Detail navigation. The
 * Settings tab is a single screen that future iterations will grow
 * into a stack as needed (Profile, Brain URL, Voice prefs, ...).
 */
export function MainTabs() {
  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#2563eb',
        tabBarInactiveTintColor: '#64748b',
      }}
    >
      <Tab.Screen
        name="Inbox"
        component={InboxStack}
        options={{
          tabBarIcon: ({ color, size }) => <Ionicons name="mail" size={size} color={color} />,
        }}
      />
      <Tab.Screen
        name="Chat"
        component={ChatStack}
        options={{
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="chatbubble" size={size} color={color} />
          ),
        }}
      />
      <Tab.Screen
        name="Documents"
        component={DocumentsStack}
        options={{
          tabBarLabel: 'Docs',
          tabBarIcon: ({ color, size }) => <Ionicons name="folder" size={size} color={color} />,
        }}
      />
      <Tab.Screen
        name="Settings"
        component={SettingsScreen}
        options={{
          tabBarIcon: ({ color, size }) => (
            <Ionicons name="settings-outline" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}
