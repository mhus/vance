import { defineStore } from 'pinia';
import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { DocumentDto } from '@vance/generated';
import type { EmbedRef } from '@/kindRenderers/parseVanceUri';

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

  async function resolve(embedRef: EmbedRef): Promise<DocumentDto> {
    const projectName = embedRef.project ?? currentProject.value;
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
    const p = brainFetch<DocumentDto>('GET', `documents/by-path?${params}`)
      .then((doc) => {
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
    resolve,
    invalidate,
    clear,
  };
});
