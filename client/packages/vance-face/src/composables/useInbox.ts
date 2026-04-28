import { ref, type Ref } from 'vue';
import {
  AnswerOutcome,
  InboxItemStatus,
  type InboxItemDto,
  type InboxListResponse,
  type InboxTagsResponse,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

export type AssignedToFilter =
  | { kind: 'self' }
  | { kind: 'team'; teamName: string }
  | { kind: 'user'; userId: string };

export interface InboxFilter {
  /** Whose inbox to show — `self` (default), a team, or a specific
   *  team-mate. */
  assignedTo: AssignedToFilter;
  /** Item status. {@code null} → all statuses. */
  status?: InboxItemStatus | null;
  /** Single tag filter. {@code null} → no tag filter. */
  tag?: string | null;
}

/** Map an {@link AssignedToFilter} to the wire {@code ?assignedTo=} param. */
function encodeAssignedTo(a: AssignedToFilter): string | null {
  switch (a.kind) {
    case 'self': return null;
    case 'team': return `team:${a.teamName}`;
    case 'user': return a.userId;
  }
}

/**
 * Reactive wrapper around the inbox REST endpoints. One instance
 * per editor instance — exposes the active list, the selected
 * item, and mutation helpers.
 */
export function useInbox(): {
  items: Ref<InboxItemDto[]>;
  selected: Ref<InboxItemDto | null>;
  tags: Ref<string[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  filter: Ref<InboxFilter>;
  loadList: (filter: InboxFilter) => Promise<void>;
  loadOne: (id: string) => Promise<void>;
  loadTags: () => Promise<void>;
  clearSelection: () => void;
  answer: (id: string, outcome: AnswerOutcome,
           value?: Record<string, unknown> | null,
           reason?: string | null) => Promise<boolean>;
  archive: (id: string) => Promise<boolean>;
  unarchive: (id: string) => Promise<boolean>;
  dismiss: (id: string) => Promise<boolean>;
  delegate: (id: string, toUserId: string, note?: string | null) => Promise<boolean>;
} {
  const items = ref<InboxItemDto[]>([]);
  const selected = ref<InboxItemDto | null>(null);
  const tags = ref<string[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const filter = ref<InboxFilter>({
    assignedTo: { kind: 'self' },
    status: InboxItemStatus.PENDING,
    tag: null,
  });

  async function loadList(next: InboxFilter): Promise<void> {
    loading.value = true;
    error.value = null;
    filter.value = next;
    try {
      const params = new URLSearchParams();
      const a = encodeAssignedTo(next.assignedTo);
      if (a) params.set('assignedTo', a);
      if (next.status !== null && next.status !== undefined) {
        // Numerical TS-enum → backend's string name via reverse lookup.
        params.set('status', InboxItemStatus[next.status]);
      }
      if (next.tag) params.set('tag', next.tag);
      const qs = params.toString();
      const data = await brainFetch<InboxListResponse>(
        'GET',
        qs ? `inbox?${qs}` : 'inbox',
      );
      items.value = data.items ?? [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load inbox.';
    } finally {
      loading.value = false;
    }
  }

  async function loadOne(id: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      selected.value = await brainFetch<InboxItemDto>(
        'GET', `inbox/${encodeURIComponent(id)}`);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load item.';
    } finally {
      loading.value = false;
    }
  }

  async function loadTags(): Promise<void> {
    try {
      const data = await brainFetch<InboxTagsResponse>('GET', 'inbox/tags');
      tags.value = data.tags ?? [];
    } catch (e) {
      // Tags are a UX nicety — non-fatal. Log and clear.
      tags.value = [];
      console.warn('Failed to load inbox tags', e);
    }
  }

  function clearSelection(): void {
    selected.value = null;
  }

  /**
   * Apply a mutation result to local state — replace `selected`
   * if it was the touched item, and refresh the row in `items`.
   * Returns `true` so the caller can chain.
   */
  function applyMutation(updated: InboxItemDto): boolean {
    if (selected.value?.id === updated.id) {
      selected.value = updated;
    }
    const idx = items.value.findIndex((i) => i.id === updated.id);
    if (idx >= 0) {
      // After a mutation the item often falls out of the active
      // filter (e.g. answered → no longer PENDING). The strict
      // approach is to drop it; we go with replace-in-place so
      // the user keeps context, and the next loadList reconciles.
      items.value[idx] = updated;
    }
    return true;
  }

  async function answer(
    id: string,
    outcome: AnswerOutcome,
    value?: Record<string, unknown> | null,
    reason?: string | null,
  ): Promise<boolean> {
    error.value = null;
    try {
      // The wire-form uses the enum's STRING name (Spring/Jackson
      // deserialises @JsonValue-style by default). The generated
      // TS-enum is numeric, so reverse-lookup gives us the name.
      const body = {
        itemId: id,
        outcome: AnswerOutcome[outcome],
        value: value ?? undefined,
        reason: reason ?? undefined,
      };
      const updated = await brainFetch<InboxItemDto>(
        'POST', `inbox/${encodeURIComponent(id)}/answer`, { body });
      return applyMutation(updated);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to answer.';
      return false;
    }
  }

  async function archive(id: string): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<InboxItemDto>(
        'POST', `inbox/${encodeURIComponent(id)}/archive`);
      return applyMutation(updated);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to archive.';
      return false;
    }
  }

  async function unarchive(id: string): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<InboxItemDto>(
        'POST', `inbox/${encodeURIComponent(id)}/unarchive`);
      return applyMutation(updated);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to unarchive.';
      return false;
    }
  }

  async function dismiss(id: string): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<InboxItemDto>(
        'POST', `inbox/${encodeURIComponent(id)}/dismiss`);
      return applyMutation(updated);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to dismiss.';
      return false;
    }
  }

  async function delegate(
    id: string,
    toUserId: string,
    note?: string | null,
  ): Promise<boolean> {
    error.value = null;
    try {
      const body = {
        itemId: id,
        toUserId,
        note: note ?? undefined,
      };
      const updated = await brainFetch<InboxItemDto>(
        'POST', `inbox/${encodeURIComponent(id)}/delegate`, { body });
      return applyMutation(updated);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delegate.';
      return false;
    }
  }

  return {
    items,
    selected,
    tags,
    loading,
    error,
    filter,
    loadList,
    loadOne,
    loadTags,
    clearSelection,
    answer,
    archive,
    unarchive,
    dismiss,
    delegate,
  };
}
