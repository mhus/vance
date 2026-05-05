import { Pressable, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import type { DocumentSummary } from '@vance/generated';
import { VBadge } from '@/components';
import { relativeTime } from '@/util/format';

interface Props {
  item: DocumentSummary;
  onPress: () => void;
}

function pickIconName(d: DocumentSummary): React.ComponentProps<typeof Ionicons>['name'] {
  if (d.mimeType?.startsWith('image/')) return 'image-outline';
  if (d.mimeType === 'application/pdf') return 'document-attach-outline';
  if (d.kind === 'mindmap') return 'git-network-outline';
  if (d.kind === 'graph') return 'git-network-outline';
  if (d.kind === 'sheet') return 'grid-outline';
  if (d.kind === 'tree') return 'list-outline';
  return 'document-text-outline';
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function DocumentRow({ item, onPress }: Props) {
  const icon = pickIconName(item);
  const heading = item.title ?? item.name;
  return (
    <Pressable
      onPress={onPress}
      className="flex-row items-start gap-3 px-4 py-3 border-b border-slate-100 dark:border-slate-900 active:bg-slate-50 dark:active:bg-slate-900"
    >
      <View className="pt-1">
        <Ionicons name={icon} size={22} color="#64748b" />
      </View>
      <View className="flex-1 min-w-0">
        <Text
          numberOfLines={1}
          className="text-base font-semibold text-slate-900 dark:text-slate-100"
        >
          {heading}
        </Text>
        <Text
          numberOfLines={1}
          className="text-xs text-slate-500 dark:text-slate-400 mt-0.5"
        >
          {item.path || item.name}
        </Text>
        <View className="flex-row items-center gap-2 mt-1">
          {item.kind ? <VBadge>{item.kind}</VBadge> : null}
          <Text className="text-xs text-slate-500 dark:text-slate-500">
            {formatSize(item.size)}
          </Text>
          <Text className="text-xs text-slate-500 dark:text-slate-500">·</Text>
          <Text className="text-xs text-slate-500 dark:text-slate-500">
            {relativeTime(item.createdAtMs)}
          </Text>
        </View>
      </View>
    </Pressable>
  );
}
