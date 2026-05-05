import { Image, ScrollView, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { DocumentDto } from '@vance/generated';
import { useDocumentItem } from '@/hooks/useDocuments';
import { MobileShell, VAlert, VBadge, VEmptyState, VLoading } from '@/components';
import { contentHeaders, documentContentUrl } from '@/api/documentsApi';
import { relativeTime } from '@/util/format';
import type { DocumentsStackParamList } from '@/navigation/types';

type Nav = NativeStackNavigationProp<DocumentsStackParamList, 'DocumentDetail'>;
type Route = RouteProp<DocumentsStackParamList, 'DocumentDetail'>;

const STRUCTURED_KINDS = new Set([
  'mindmap',
  'graph',
  'sheet',
  'tree',
  'items',
  'records',
  'data',
]);

export default function DocumentDetailScreen() {
  const nav = useNavigation<Nav>();
  const { id } = useRoute<Route>().params;
  const query = useDocumentItem(id);

  return (
    <MobileShell title="Document" onBack={() => nav.goBack()}>
      {query.isLoading && !query.data ? (
        <VLoading variant="centered" label="Loading document…" />
      ) : query.isError || !query.data ? (
        <View className="px-4 py-3">
          <VAlert variant="error">Could not load this document.</VAlert>
        </View>
      ) : (
        <ScrollView contentContainerClassName="p-4 gap-4">
          <Header doc={query.data} />
          <View className="h-px bg-slate-200 dark:bg-slate-800" />
          <Body doc={query.data} />
        </ScrollView>
      )}
    </MobileShell>
  );
}

function Header({ doc }: { doc: DocumentDto }) {
  const heading = doc.title ?? doc.name;
  return (
    <View className="gap-2">
      <Text className="text-xl font-semibold text-slate-900 dark:text-slate-100">
        {heading}
      </Text>
      <Text className="text-xs text-slate-500 dark:text-slate-400">{doc.path}</Text>
      <View className="flex-row items-center gap-2 flex-wrap">
        {doc.kind ? <VBadge>{doc.kind}</VBadge> : null}
        {doc.mimeType ? (
          <Text className="text-xs text-slate-500 dark:text-slate-500">{doc.mimeType}</Text>
        ) : null}
        {doc.createdAtMs !== undefined ? (
          <Text className="text-xs text-slate-500 dark:text-slate-500">
            · {relativeTime(doc.createdAtMs)}
          </Text>
        ) : null}
      </View>
    </View>
  );
}

function Body({ doc }: { doc: DocumentDto }) {
  // Structured kinds get a JSON-plain-text fallback per the spec; the
  // mobile app intentionally does not try to render mindmaps / graphs
  // / sheets / trees.
  if (doc.kind && STRUCTURED_KINDS.has(doc.kind)) {
    return (
      <View className="gap-2">
        <VAlert variant="info">
          This {doc.kind} is best viewed on desktop. The raw text below is the
          unrendered source.
        </VAlert>
        {doc.inlineText ? (
          <Text className="text-sm font-mono text-slate-700 dark:text-slate-300">
            {doc.inlineText}
          </Text>
        ) : (
          <VEmptyState
            icon={<Ionicons name="document-outline" size={36} color="#94a3b8" />}
            headline="Stored binary"
            body="Open on desktop to view."
          />
        )}
      </View>
    );
  }

  if (doc.inline && doc.inlineText !== undefined) {
    return (
      <Text className="text-base text-slate-800 dark:text-slate-200">{doc.inlineText}</Text>
    );
  }

  if (doc.mimeType?.startsWith('image/')) {
    return (
      <Image
        source={{ uri: documentContentUrl(doc.id), headers: contentHeaders() }}
        resizeMode="contain"
        className="w-full h-96 bg-slate-100 dark:bg-slate-900 rounded"
      />
    );
  }

  // PDFs / Office docs / unknown binaries — Mobile v1 doesn't render
  // these. Surface the metadata and point at desktop.
  return (
    <VEmptyState
      icon={<Ionicons name="document-outline" size={36} color="#94a3b8" />}
      headline="Open on desktop"
      body="Mobile v1 cannot render this file type. Use the web UI to view it."
    />
  );
}
