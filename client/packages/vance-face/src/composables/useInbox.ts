import { ref, type Ref } from 'vue';
import {
  AnswerOutcome,
  MaximegalonStatus,
  type EffectDescription,
  type MaximegalonDto,
  type InboxListResponse,
  type InboxTagsResponse,
} from '@vance/generated';
import { brainFetch, RestError } from '@vance/shared';
import { refreshInboxCount } from '@/inbox/inboxCountStore';

export type AssignedToFilter =
  | { kind: 'self' }
  | { kind: 'team'; teamName: string }
  | { kind: 'user'; userId: string };

export interface InboxFilter {
  /** Whose inbox to show — `self` (default), a team, or a specific
   *  team-mate. */
  assignedTo: AssignedToFilter;
  /** Item status. {@code null} → all statuses. */
  status?: MaximegalonStatus | null;
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
  items: Ref<MaximegalonDto[]>;
  selected: Ref<MaximegalonDto | null>;
  /** Server-rendered facts for the selected item's effect, if it has one. */
  effect: Ref<EffectDescription | null>;
  tags: Ref<string[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  filter: Ref<InboxFilter>;
  loadList: (filter: InboxFilter) => Promise<void>;
  /** Threads whose object is the given document — fills the same `items` ref. */
  loadForDocument: (documentId: string) => Promise<void>;
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
  postMessage: (id: string, body: string, parentId?: string | null) => Promise<boolean>;
  markRead: (id: string, messageIds?: string[] | null) => Promise<boolean>;
  invite: (id: string, userId: string) => Promise<boolean>;
  removeParticipant: (id: string, userId: string) => Promise<boolean>;
  setFollowing: (id: string, following: boolean) => Promise<boolean>;
  react: (id: string, key: string, on: boolean, messageId?: string | null) => Promise<boolean>;
} {
  const items = ref<MaximegalonDto[]>([]);
  const selected = ref<MaximegalonDto | null>(null);
  const effect = ref<EffectDescription | null>(null);
  const tags = ref<string[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const filter = ref<InboxFilter>({
    assignedTo: { kind: 'self' },
    status: MaximegalonStatus.PENDING,
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
        params.set('status', MaximegalonStatus[next.status]);
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

  /**
   * Threads about one document. Fills the same `items` ref as {@link loadList},
   * so the row rendering and every mutation helper work unchanged.
   *
   * <p>The server filters by visibility, and an empty answer therefore does not
   * mean "there are none" — it means none this person may see. The caller must
   * not phrase the empty state as a statement about existence; saying "none you
   * may see" would confirm the very thing the filter protects.
   */
  async function loadForDocument(documentId: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const data = await brainFetch<InboxListResponse>(
        'GET', `inbox/by-document/${encodeURIComponent(documentId)}`);
      items.value = data.items ?? [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load discussions.';
      items.value = [];
    } finally {
      loading.value = false;
    }
  }

  async function loadOne(id: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      selected.value = await brainFetch<MaximegalonDto>(
        'GET', `inbox/${encodeURIComponent(id)}`);
      await loadEffect(id, selected.value);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load item.';
    } finally {
      loading.value = false;
    }
  }

  /**
   * Loads what answering this item would execute. Only items carrying an
   * effect have one; the endpoint answers 204 otherwise, which
   * `brainFetch` surfaces as an empty body.
   *
   * Failing to load must not hide the item: the caller still sees title,
   * quoted reason and the answer buttons, just without the fact table.
   */
  async function loadEffect(id: string, item: MaximegalonDto | null): Promise<void> {
    effect.value = null;
    if (!item?.effectType) return;
    try {
      const data = await brainFetch<EffectDescription | null>(
        'GET', `inbox/${encodeURIComponent(id)}/effect`);
      effect.value = data ?? null;
    } catch (e) {
      console.warn('Failed to load inbox effect description', e);
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
    effect.value = null;
  }

  /**
   * Apply a mutation result to local state — replace `selected`
   * if it was the touched item, and refresh the row in `items`.
   * Returns `true` so the caller can chain.
   */
  function applyMutation(updated: MaximegalonDto): boolean {
    if (selected.value?.id === updated.id) {
      selected.value = updated;
      // The effect ran as part of answering, so its state has moved on —
      // most importantly it may have FAILED, which the user has to see.
      void loadEffect(updated.id, updated);
    }
    const idx = items.value.findIndex((i) => i.id === updated.id);
    if (idx >= 0) {
      // After a mutation the item often falls out of the active
      // filter (e.g. answered → no longer PENDING). The strict
      // approach is to drop it; we go with replace-in-place so
      // the user keeps context, and the next loadList reconciles.
      items.value[idx] = updated;
    }
    // Every mutation here can move an item in or out of PENDING, so the
    // topbar badge is stale the moment we return. Fire-and-forget: the
    // badge must never gate an inbox action.
    void refreshInboxCount();
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
      const updated = await brainFetch<MaximegalonDto>(
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
      const updated = await brainFetch<MaximegalonDto>(
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
      const updated = await brainFetch<MaximegalonDto>(
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
      const updated = await brainFetch<MaximegalonDto>(
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
      const updated = await brainFetch<MaximegalonDto>(
        'POST', `inbox/${encodeURIComponent(id)}/delegate`, { body });
      return applyMutation(updated);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delegate.';
      return false;
    }
  }

  // ──── Thread ────────────────────────────────────────────────────────

  /**
   * Turns a failure into a message for the user.
   *
   * <p>A 409 carries a thread invariant the server refused to break, with a
   * stable {@code reason} code — "you are the assignee of an open ask, delegate
   * instead" is worth saying precisely. Anything else falls back to the
   * generic text, because guessing at an unknown failure is worse than
   * admitting it.
   */
  function threadError(e: unknown, fallbackKey: string): string {
    if (e instanceof RestError && e.reason) return e.reason;
    return e instanceof Error ? e.message : fallbackKey;
  }

  async function postMessage(
    id: string, body: string, parentId?: string | null,
  ): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<MaximegalonDto>(
        'POST', `inbox/${encodeURIComponent(id)}/messages`,
        { body: { body, parentId: parentId ?? undefined } });
      return applyMutation(updated);
    } catch (e) {
      error.value = threadError(e, 'Failed to post message.');
      return false;
    }
  }

  /**
   * Tells the server what the user has seen. Whether that is on open, on
   * scroll or after a delay is this client's business; that it happened has to
   * reach the server, or a second device shows a badge that is already dealt
   * with.
   *
   * <p>Silent on failure: a read-marker that does not arrive costs a stale
   * badge, and an error toast for it would be noise on every flaky moment.
   */
  async function markRead(id: string, messageIds?: string[] | null): Promise<boolean> {
    try {
      const updated = await brainFetch<MaximegalonDto>(
        'POST', `inbox/${encodeURIComponent(id)}/read`,
        { body: { messageIds: messageIds ?? undefined } });
      return applyMutation(updated);
    } catch (e) {
      console.warn('Failed to mark inbox thread read', e);
      return false;
    }
  }

  async function invite(id: string, userId: string): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<MaximegalonDto>(
        'POST', `inbox/${encodeURIComponent(id)}/invite`, { body: { userId } });
      return applyMutation(updated);
    } catch (e) {
      error.value = threadError(e, 'Failed to invite.');
      return false;
    }
  }

  /**
   * Take someone back out of the thread. The counterpart to `invite`, and the
   * only way an unwanted join can be undone by anyone other than the joiner —
   * being a participant is checked before anything derived, so joining freezes
   * a visibility that until then merely followed the assignee.
   *
   * Server-side this is gated on whoever may decide, not on whoever may see;
   * a 409 comes back for the assignee of an open ask and for the originator.
   */
  async function removeParticipant(id: string, userId: string): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<MaximegalonDto>(
        'POST', `inbox/${encodeURIComponent(id)}/participants/remove`,
        { body: { userId } });
      return applyMutation(updated);
    } catch (e) {
      error.value = threadError(e, 'Failed to remove participant.');
      return false;
    }
  }

  async function setFollowing(id: string, following: boolean): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<MaximegalonDto>(
        'POST', `inbox/${encodeURIComponent(id)}/follow`, { body: { following } });
      return applyMutation(updated);
    } catch (e) {
      error.value = threadError(e, 'Failed to change subscription.');
      return false;
    }
  }

  async function react(
    id: string, key: string, on: boolean, messageId?: string | null,
  ): Promise<boolean> {
    error.value = null;
    try {
      const updated = await brainFetch<MaximegalonDto>(
        'POST', `inbox/${encodeURIComponent(id)}/react`,
        { body: { key, on, messageId: messageId ?? undefined } });
      return applyMutation(updated);
    } catch (e) {
      error.value = threadError(e, 'Failed to react.');
      return false;
    }
  }

  return {
    items,
    selected,
    effect,
    tags,
    loading,
    error,
    filter,
    loadList,
    loadForDocument,
    loadOne,
    loadTags,
    clearSelection,
    answer,
    archive,
    unarchive,
    dismiss,
    delegate,
    postMessage,
    markRead,
    invite,
    removeParticipant,
    setFollowing,
    react,
  };
}
