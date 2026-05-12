import { createNativeStackNavigator } from '@react-navigation/native-stack';
import ProjectListScreen from '@/screens/documents/ProjectListScreen';
import DocumentListScreen from '@/screens/documents/DocumentListScreen';
import DocumentDetailScreen from '@/screens/documents/DocumentDetailScreen';
import type { DocumentsStackParamList } from './types';

const Stack = createNativeStackNavigator<DocumentsStackParamList>();

/**
 * Documents tab — three levels deep: project picker, then the
 * document list scoped to the chosen project, then the read-only
 * document detail.
 */
export function DocumentsStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      <Stack.Screen name="ProjectList" component={ProjectListScreen} />
      <Stack.Screen name="DocumentList" component={DocumentListScreen} />
      <Stack.Screen name="DocumentDetail" component={DocumentDetailScreen} />
    </Stack.Navigator>
  );
}
