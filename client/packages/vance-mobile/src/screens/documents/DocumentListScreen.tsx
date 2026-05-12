import { FlatList, RefreshControl, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { DocumentSummary } from '@vance/generated';
import { useDocumentList } from '@/hooks/useDocuments';
import { MobileShell, VAlert, VEmptyState, VLoading } from '@/components';
import { DocumentRow } from './components/DocumentRow';
import type { DocumentsStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<DocumentsStackParamList, 'DocumentList'>;
type Route = RouteProp<DocumentsStackParamList, 'DocumentList'>;

/** Default scope: only documents under the user-content prefix
 *  (mirrors DocumentService.DOCUMENTS_FOLDER_PREFIX on the server).
 *  Trash and system folders (`_bin/`, `_vance/`, `_chatbox/`, …) stay
 *  out of the mobile view — same convention as vance-face. The mobile
 *  app has no escape hatch yet; that's intentional for v1 since the
 *  surface is read-only and a system-folder toggle would just be
 *  clutter on small screens. */
const DEFAULT_PATH_PREFIX = 'documents/';

/**
 * Documents inside a chosen project. Reached from `ProjectListScreen`;
 * the `projectId` is required, the `projectTitle` is just for the
 * header and falls back to the id when missing.
 */
export default function DocumentListScreen() {
  const nav = useNavigation<Nav>();
  const { projectId, projectTitle } = useRoute<Route>().params;
  const query = useDocumentList({ projectId, pathPrefix: DEFAULT_PATH_PREFIX });
  const items: DocumentSummary[] = query.data?.items ?? [];

  return (
    <MobileShell title={projectTitle} onBack={() => nav.goBack()}>
      {query.isLoading && !query.data ? (
        <VLoading variant="centered" label="Loading documents…" />
      ) : query.isError ? (
        <View className="px-4 py-3">
          <VAlert variant="error">Could not load documents — pull to retry.</VAlert>
        </View>
      ) : items.length === 0 ? (
        <VEmptyState
          icon={<Ionicons name="folder-open-outline" size={48} color="#94a3b8" />}
          headline="No documents"
          body={`The project "${projectTitle}" has no documents yet.`}
        />
      ) : (
        <FlatList
          data={items}
          keyExtractor={(item) => item.id}
          renderItem={({ item }) => (
            <DocumentRow item={item} onPress={() => nav.navigate('DocumentDetail', { id: item.id })} />
          )}
          refreshControl={
            <RefreshControl
              refreshing={query.isFetching && !query.isLoading}
              onRefresh={() => query.refetch()}
            />
          }
        />
      )}
    </MobileShell>
  );
}
