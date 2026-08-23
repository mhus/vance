import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { nextTick, ref } from 'vue';

const brainFetch = vi.fn();
vi.mock('@vance/shared', () => ({ brainFetch: (...args: unknown[]) => brainFetch(...args) }));

const { useFollowUpSuggestion } = await import('./useFollowUpSuggestion');
type Context = { context: string; anchorMessageId: string };

function context(id: string, text: string): Context {
  return { anchorMessageId: id, context: text };
}

function setup() {
  const conversationContext = ref<Context | null>(null);
  const composerText = ref('');
  const projectId = ref<string | null>('proj');
  const requestActive = ref(true);
  const api = useFollowUpSuggestion({
    conversationContext,
    composerText,
    projectId,
    requestActive,
  });
  return { conversationContext, composerText, projectId, requestActive, ...api };
}

/** Drives one turn: new transcript in, awaited fetch out. */
async function turn(
  conversationContext: ReturnType<typeof ref<Context | null>>,
  value: Context,
): Promise<void> {
  conversationContext.value = value;
  await nextTick();
  await Promise.resolve();
  await Promise.resolve();
}

describe('useFollowUpSuggestion', () => {
  beforeEach(() => {
    brainFetch.mockReset();
    brainFetch.mockResolvedValue({ suggestions: [{ text: 'sure, go ahead' }] });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('serves a repeated transcript from cache instead of refetching', async () => {
    const s = setup();
    await turn(s.conversationContext, context('m1', 'ASSISTANT:\nready?'));
    expect(s.activeSuggestion.value).toBe('sure, go ahead');

    await turn(s.conversationContext, context('m2', 'ASSISTANT:\nsomething else'));
    await turn(s.conversationContext, context('m1', 'ASSISTANT:\nready?'));

    expect(brainFetch).toHaveBeenCalledTimes(2);
    expect(s.activeSuggestion.value).toBe('sure, go ahead');
  });

  it('sends the full transcript even though the cache key is a digest', async () => {
    // The key is shortened so the maps do not retain 12 000-character
    // transcripts; the request body must still carry the real thing.
    const s = setup();
    const transcript = 'Alice [USER]:\nDeploy tonight?\n\nASSISTANT:\nWe can defer.';
    await turn(s.conversationContext, context('m1', transcript));

    expect(brainFetch).toHaveBeenCalledWith(
      'POST',
      'follow-up/proj',
      { body: { text: transcript, count: 1, mode: 'chat-reply' } },
    );
  });

  it('evicts the oldest entries so a long session cannot grow the cache forever', async () => {
    const s = setup();
    // One entry per turn, none ever revisited — without a bound both the
    // suggestion cache and the accepted set would live as long as the tab.
    for (let i = 0; i < 60; i++) {
      await turn(s.conversationContext, context(`m${i}`, `ASSISTANT:\nturn ${i}`));
    }
    const afterFirstPass = brainFetch.mock.calls.length;

    // The very first transcript was evicted → fetched again.
    await turn(s.conversationContext, context('m0', 'ASSISTANT:\nturn 0'));
    expect(brainFetch.mock.calls.length).toBe(afterFirstPass + 1);

    // A recent one is still memoised → no extra call.
    await turn(s.conversationContext, context('m59', 'ASSISTANT:\nturn 59'));
    expect(brainFetch.mock.calls.length).toBe(afterFirstPass + 1);
  });

  it('asks for nothing and drops the ghost once the transcript has no anchor', async () => {
    // What the caller reports the moment the user sends: the conversation tail
    // is now their own message, so there is nothing to suggest a reply to.
    // Neither a request nor a leftover ghost may survive that.
    const s = setup();
    await turn(s.conversationContext, context('m1', 'ASSISTANT:\nready?'));
    expect(s.activeSuggestion.value).toBe('sure, go ahead');
    const before = brainFetch.mock.calls.length;

    s.conversationContext.value = null;
    await nextTick();
    await Promise.resolve();

    expect(brainFetch.mock.calls.length).toBe(before);
    expect(s.activeSuggestion.value).toBeNull();
  });

  it('hides an accepted suggestion for the same transcript', async () => {
    const s = setup();
    await turn(s.conversationContext, context('m1', 'ASSISTANT:\nready?'));
    s.acceptCurrent();
    await nextTick();

    expect(s.activeSuggestion.value).toBeNull();
  });
});
