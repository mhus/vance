import { createNativeStackNavigator } from '@react-navigation/native-stack';
import ChatPickerScreen from '@/screens/chat/ChatPickerScreen';
import NewSessionScreen from '@/screens/chat/NewSessionScreen';
import ChatLiveScreen from '@/screens/chat/ChatLiveScreen';
import type { ChatStackParamList } from './types';

const Stack = createNativeStackNavigator<ChatStackParamList>();

export function ChatStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      <Stack.Screen name="ChatPicker" component={ChatPickerScreen} />
      <Stack.Screen name="NewSession" component={NewSessionScreen} />
      <Stack.Screen name="ChatLive" component={ChatLiveScreen} />
    </Stack.Navigator>
  );
}
