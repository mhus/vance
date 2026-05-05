import { View } from 'react-native';
import { InboxItemStatus, InboxItemType, type InboxItemDto } from '@vance/generated';
import { VAlert, VButton } from '@/components';
import {
  useArchiveInboxItem,
  useDismissInboxItem,
  useUnarchiveInboxItem,
} from '@/hooks/useInbox';
import { ApprovalActions } from './ApprovalActions';
import { DecisionActions } from './DecisionActions';
import { FeedbackActions } from './FeedbackActions';

interface Props {
  item: InboxItemDto;
  onResolved: () => void;
}

/**
 * Type-aware footer for {@link InboxDetailScreen}. Pending ASK
 * items get the type-specific buttons; resolved or output items get
 * archive / unarchive shortcuts. Mobile v1 punts on ORDERING and
 * STRUCTURE_EDIT — those need richer editors than a phone screen
 * justifies.
 */
export function InboxActionBar({ item, onResolved }: Props) {
  if (item.status === InboxItemStatus.PENDING) {
    return <PendingActions item={item} onResolved={onResolved} />;
  }
  return <ResolvedActions item={item} onResolved={onResolved} />;
}

function PendingActions({ item, onResolved }: Props) {
  const dismiss = useDismissInboxItem();

  return (
    <View className="gap-3">
      {pendingBody(item, onResolved)}
      <VButton
        variant="ghost"
        size="sm"
        disabled={dismiss.isPending}
        onPress={() => dismiss.mutate(item.id, { onSuccess: onResolved })}
      >
        Dismiss without answering
      </VButton>
    </View>
  );
}

function pendingBody(item: InboxItemDto, onResolved: () => void): React.ReactNode {
  switch (item.type) {
    case InboxItemType.APPROVAL:
      return <ApprovalActions item={item} onAnswered={onResolved} />;
    case InboxItemType.DECISION:
      return <DecisionActions item={item} onAnswered={onResolved} />;
    case InboxItemType.FEEDBACK:
      return <FeedbackActions item={item} onAnswered={onResolved} />;
    case InboxItemType.ORDERING:
    case InboxItemType.STRUCTURE_EDIT:
      return (
        <VAlert variant="warning">
          This item type is best handled on the desktop UI. Mobile cannot edit
          structure or reorder lists yet.
        </VAlert>
      );
    case InboxItemType.OUTPUT_TEXT:
    case InboxItemType.OUTPUT_IMAGE:
    case InboxItemType.OUTPUT_DOCUMENT:
      // Outputs are notifications, not asks — show an archive action only.
      return null;
    default:
      return null;
  }
}

function ResolvedActions({ item, onResolved }: Props) {
  const archive = useArchiveInboxItem();
  const unarchive = useUnarchiveInboxItem();
  const isArchived = item.status === InboxItemStatus.ARCHIVED;

  return (
    <View className="gap-2">
      <VButton
        variant="secondary"
        disabled={archive.isPending || unarchive.isPending}
        onPress={() =>
          isArchived
            ? unarchive.mutate(item.id, { onSuccess: onResolved })
            : archive.mutate(item.id, { onSuccess: onResolved })
        }
      >
        {isArchived ? 'Restore from archive' : 'Archive'}
      </VButton>
    </View>
  );
}
