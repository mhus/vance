import { computed, ref, watch, type Ref } from 'vue';
import type { FollowUpRequestDto, FollowUpResponseDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

export interface FollowUpConversationContext {
  context: string;
  anchorMessageId: string;
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
  const accepted = new Set<string>();
  const fetchedSuggestion = ref<string | null>(null);
  const loading = ref(false);

  function cacheKey(
    project: string | null,
    conversation: FollowUpConversationContext | null,
  ): string | null {
    if (!project || !conversation?.context || !conversation.anchorMessageId) return null;
    return `${project}::${conversation.anchorMessageId}::${conversation.context}`;
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
      cache.set(key, value);
      fetchedSuggestion.value = value;
    } catch {
      if (seq === fetchSeq) {
        cache.set(key, null);
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
    if (key !== null) accepted.add(key);
    fetchedSuggestion.value = null;
  }

  return { activeSuggestion, loading, acceptCurrent };
}
