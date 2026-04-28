import { ref, type Ref } from 'vue';
import type { SkillDto, SkillWriteRequest } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * Read + write skills at one of three persistence scopes — pick by
 * setting exactly one of {@code projectId} / {@code userId}, or both
 * to {@code null} for tenant scope. The "effective" list overlays the
 * cascade BUNDLED → TENANT → PROJECT? → USER? and returns one entry
 * per name with a {@code scope} field.
 */
export function useAdminSkills(): {
  skills: Ref<SkillDto[]>;
  loading: Ref<boolean>;
  busy: Ref<boolean>;
  error: Ref<string | null>;
  loadEffective: (projectId: string | null, userId: string | null) => Promise<void>;
  upsert: (
    projectId: string | null,
    userId: string | null,
    name: string,
    body: SkillWriteRequest,
  ) => Promise<SkillDto>;
  remove: (projectId: string | null, userId: string | null, name: string) => Promise<void>;
} {
  const skills = ref<SkillDto[]>([]);
  const loading = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function loadEffective(
    projectId: string | null,
    userId: string | null,
  ): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams();
      if (projectId) params.set('projectId', projectId);
      if (userId) params.set('userId', userId);
      const path = `admin/skills/effective${params.toString() ? '?' + params.toString() : ''}`;
      skills.value = await brainFetch<SkillDto[]>('GET', path);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load skills.';
      skills.value = [];
    } finally {
      loading.value = false;
    }
  }

  function pathFor(
    projectId: string | null, userId: string | null, name: string,
  ): string {
    if (userId) {
      return `admin/users/${encodeURIComponent(userId)}/skills/${encodeURIComponent(name)}`;
    }
    if (projectId) {
      return `admin/projects/${encodeURIComponent(projectId)}/skills/${encodeURIComponent(name)}`;
    }
    return `admin/skills/${encodeURIComponent(name)}`;
  }

  async function upsert(
    projectId: string | null,
    userId: string | null,
    name: string,
    body: SkillWriteRequest,
  ): Promise<SkillDto> {
    busy.value = true;
    error.value = null;
    try {
      const saved = await brainFetch<SkillDto>(
        'PUT', pathFor(projectId, userId, name), { body });
      await loadEffective(projectId, userId);
      return saved;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save skill.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  async function remove(
    projectId: string | null,
    userId: string | null,
    name: string,
  ): Promise<void> {
    busy.value = true;
    error.value = null;
    try {
      await brainFetch<void>('DELETE', pathFor(projectId, userId, name));
      await loadEffective(projectId, userId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete skill.';
      throw e;
    } finally {
      busy.value = false;
    }
  }

  return { skills, loading, busy, error, loadEffective, upsert, remove };
}
