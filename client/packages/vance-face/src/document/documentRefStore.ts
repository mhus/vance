import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { brainFetch, brainFetchText } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

/**
 * How long {@link resolve} waits for the host editor to populate the
 * current project before giving up. Chat (WS session-list) and Cortex
 * (REST sessions) both resolve the project asynchronously after the
 * editor mounts — a vance:-link inside a historical message can fire
 * its EmbeddedKindBox onMounted well before either lookup returns.
 * Five seconds covers the realistic worst case (cold WS + first-paint
 * jitter on a slow client) without hanging the bubble forever when
 * something is genuinely broken upstream.
 */
const CURRENT_PROJECT_WAIT_MS = 5_000;

/**
 * Session-lifetime cache for embedded-channel document loads (Markdown
 * links with {@code vance:} URI). Same {@code (projectId, path)} key is
 * resolved exactly once per session — page reload drops the cache.
 *
 * Spec: specification/inline-and-embedded-content.md §11.8.
 */

type CacheKey = string; // `${projectId ?? ""}::${path}`

function keyFor(projectId: string | undefined, path: string): CacheKey {
  return `${projectId ?? ''}::${path}`;
}

export const useDocumentRefStore = defineStore('documentRef', () => {
  const cache = ref(new Map<CacheKey, DocumentDto>());
  const pending = ref(new Map<CacheKey, Promise<DocumentDto>>());
  /**
   * Current project name — used as the default when a {@code vance:/...}
   * URI omits the authority segment. Set by the host editor (chat,
   * inbox, …) on bind.
   */
  const currentProject = ref<string>('');

  function setCurrentProject(projectName: string): void {
    currentProject.value = projectName;
  }

  /**
   * Resolves once {@link currentProject} carries a non-empty value, or
   * returns the empty string after {@link CURRENT_PROJECT_WAIT_MS}.
   * The watcher is one-shot and stops itself either way, so no leaks
   * on unmount. Bypasses entirely when the value is already there —
   * the common path stays a single ref read.
   */
  function waitForCurrentProject(): Promise<string> {
    if (currentProject.value) return Promise.resolve(currentProject.value);
    return new Promise<string>((resolveP) => {
      let settled = false;
      const finish = (val: string): void => {
        if (settled) return;
        settled = true;
        stop();
        clearTimeout(timer);
        resolveP(val);
      };
      const stop = watch(currentProject, (val) => {
        if (val) finish(val);
      });
      const timer = setTimeout(() => finish(''), CURRENT_PROJECT_WAIT_MS);
    });
  }

  async function resolve(embedRef: EmbedRef): Promise<DocumentDto> {
    let projectName = embedRef.project ?? currentProject.value;
    if (!projectName) {
      // Host editor hasn't populated the project context yet (typical
      // when a `vance:`-link mounts inside a freshly-restored chat
      // history before the session-list / sessions REST roundtrip
      // returns). Wait briefly — the project *is* on its way down the
      // pipe, we just need to let it land.
      projectName = await waitForCurrentProject();
    }
    if (!projectName) {
      throw new Error('No project context to resolve vance: URI');
    }
    const key = keyFor(projectName, embedRef.path);
    const hit = cache.value.get(key);
    if (hit) return hit;
    const inflight = pending.value.get(key);
    if (inflight) return inflight;

    const params = new URLSearchParams({
      projectId: projectName,
      path: embedRef.path,
    });
    // /documents/by-path returns metadata only — inlineText is a
    // client-side cache field (see DocumentDto.java). The embedded
    // views (RecordsView, MindmapView, …) read inlineText
    // synchronously, so we stream the body via /{id}/content here
    // and stash it onto the DTO before caching. Skip the body fetch
    // for binary kinds — ImageView / PdfView / VideoView fetch
    // their own blob URL from the content endpoint anyway.
    const p = brainFetch<DocumentDto>('GET', `documents/by-path?${params}`)
      .then(async (doc) => {
        if (shouldFetchBody(doc)) {
          try {
            const text = await brainFetchText(
              `documents/${encodeURIComponent(doc.id)}/content`,
            );
            doc.inlineText = text ?? '';
            doc.inline = true;
          } catch (e) {
            console.warn('documentRefStore: body fetch failed', e);
          }
        }
        cache.value.set(key, doc);
        pending.value.delete(key);
        return doc;
      })
      .catch((err) => {
        pending.value.delete(key);
        throw err instanceof Error ? err : new Error(String(err));
      });
    pending.value.set(key, p);
    return p;
  }

  /**
   * Body fetch is needed for text-shaped kinds where the view reads
   * {@code inlineText} directly. Binary kinds (image / pdf / video /
   * audio) fetch their own blob URL through {@code documentContentUrl(id)}
   * and don't need the text on the DTO. The check is best-effort —
   * unknown mime types still go through the fetch so we don't
   * accidentally short-circuit a kind we haven't classified.
   */
  function shouldFetchBody(doc: DocumentDto): boolean {
    const mime = doc.mimeType ?? '';
    if (mime.startsWith('image/')) return false;
    if (mime.startsWith('audio/')) return false;
    if (mime.startsWith('video/')) return false;
    if (mime === 'application/pdf') return false;
    return true;
  }

  /** Drop a single cache entry (e.g. when a document was edited). */
  function invalidate(projectName: string, path: string): void {
    cache.value.delete(keyFor(projectName, path));
  }

  /** Drop everything — page-reload-style reset for tenant/user switches. */
  function clear(): void {
    cache.value.clear();
    pending.value.clear();
  }

  return {
    currentProject,
    setCurrentProject,
    waitForCurrentProject,
    resolve,
    invalidate,
    clear,
  };
});
