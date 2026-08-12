import { computed, ref, watch, type Ref } from 'vue';
import type { FollowUpRequestDto, FollowUpResponseDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

export interface FollowUpConversationContext {
  context: string;
  anchorMessageId: string;
}

/**
 * Upper bound on remembered suggestions. A chat session produces one entry
 * per turn and never revisits an old one, so without a bound both maps grow
 * for as long as the tab is open.
 */
const CACHE_MAX = 50;

/**
 * Insertion-ordered map capped at {@link CACHE_MAX}; the oldest entry is
 * dropped once the cap is exceeded. Re-setting a key refreshes its position,
 * so the entry in active use is never the one evicted.
 */
function boundedSet<V>(map: Map<string, V>, key: string, value: V): void {
  map.delete(key);
  map.set(key, value);
  while (map.size > CACHE_MAX) {
    const oldest = map.keys().next();
    if (oldest.done) break;
    map.delete(oldest.value);
  }
}

/**
 * Short, stable digest of the transcript (FNV-1a over the text, plus its
 * length). Keeps the cache key a few dozen bytes instead of the full 12 000-
 * character context — the maps are held for the lifetime of the tab, and the
 * transcript itself is never read back from them. Length is mixed in because
 * a 32-bit hash alone is thin; a residual collision costs one stale ghost
 * suggestion, which is the same class of wrongness the feature already
 * tolerates.
 */
function digest(text: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return `${text.length}:${(hash >>> 0).toString(36)}`;
}

/**
 * Reactive follow-up suggestion for the chat editor's ghost bubble.
 * Reply mode receives a bounded, speaker-aware transcript rather than a
 * single assistant message, so shared-chat turns and non-alternating roles
 * remain visible to the model.
 */
export function useFollowUpSuggestion(params: {
  conversationContext: Ref<FollowUpConversationContext | null>;
  composerText: Ref<string>;
  projectId: Ref<string | null>;
  enabled?: Ref<boolean>;
  requestActive: Ref<boolean>;
}): {
  activeSuggestion: Ref<string | null>;
  loading: Ref<boolean>;
  acceptCurrent: () => void;
} {
  const { conversationContext, composerText, projectId, enabled, requestActive } = params;
  const cache = new Map<string, string | null>();
  // Map rather than Set: same bounded-LRU treatment, value unused.
  const accepted = new Map<string, true>();
  const fetchedSuggestion = ref<string | null>(null);
  const loading = ref(false);

  function cacheKey(
    project: string | null,
    conversation: FollowUpConversationContext | null,
  ): string | null {
    if (!project || !conversation?.context || !conversation.anchorMessageId) return null;
    return `${project}::${conversation.anchorMessageId}::${digest(conversation.context)}`;
  }

  const featureEnabled = computed<boolean>(() => enabled?.value !== false);
  const composerEmpty = computed<boolean>(() => composerText.value.trim().length === 0);

  const activeSuggestion = computed<string | null>(() => {
    if (!featureEnabled.value || !composerEmpty.value) return null;
    const key = cacheKey(projectId.value, conversationContext.value);
    if (key === null || accepted.has(key)) return null;
    return fetchedSuggestion.value;
  });

  let fetchSeq = 0;

  async function fetchFor(
    project: string,
    conversation: FollowUpConversationContext,
  ): Promise<void> {
    const key = cacheKey(project, conversation);
    if (key === null) return;
    if (cache.has(key)) {
      fetchedSuggestion.value = cache.get(key) ?? null;
      return;
    }
    const seq = ++fetchSeq;
    loading.value = true;
    try {
      const body: FollowUpRequestDto = {
        text: conversation.context,
        count: 1,
        mode: 'chat-reply',
      };
      const resp = await brainFetch<FollowUpResponseDto>(
        'POST',
        `follow-up/${encodeURIComponent(project)}`,
        { body },
      );
      if (seq !== fetchSeq) return;
      const first = resp.suggestions?.[0]?.text?.trim() ?? null;
      const value = first && first.length > 0 ? first : null;
      boundedSet(cache, key, value);
      fetchedSuggestion.value = value;
    } catch {
      if (seq === fetchSeq) {
        boundedSet(cache, key, null);
        fetchedSuggestion.value = null;
      }
    } finally {
      if (seq === fetchSeq) loading.value = false;
    }
  }

  watch(
    [conversationContext, projectId, featureEnabled, requestActive],
    ([conversation, project, on, active], previous) => {
      if (previous?.[0] !== conversation) fetchedSuggestion.value = null;
      if (!on || !project || !conversation || !active) return;
      void fetchFor(project, conversation);
    },
    { immediate: true },
  );

  function acceptCurrent(): void {
    const key = cacheKey(projectId.value, conversationContext.value);
    if (key !== null) boundedSet(accepted, key, true);
    fetchedSuggestion.value = null;
  }

  return { activeSuggestion, loading, acceptCurrent };
}
