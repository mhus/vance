import { ref, type Ref } from 'vue';
import type {
  ProfileDto,
  ProfileSettingWriteRequest,
  ProfileUpdateRequest,
} from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * REST loader / mutator for the self-service profile page. Talks to
 * {@code /brain/{tenant}/profile} — that endpoint always operates on
 * the caller's own user, so no username plumbing is needed here.
 *
 * <p>Every mutator updates the local {@link profile} ref with the
 * fresh server response so the UI stays in sync without a manual
 * reload.
 */
export function useProfile(): {
  profile: Ref<ProfileDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
  saveIdentity: (patch: ProfileUpdateRequest) => Promise<void>;
  saveSetting: (key: string, value: string | null) => Promise<void>;
  deleteSetting: (key: string) => Promise<void>;
} {
  const profile = ref<ProfileDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      profile.value = await brainFetch<ProfileDto>('GET', 'profile');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load profile.';
      profile.value = null;
    } finally {
      loading.value = false;
    }
  }

  async function saveIdentity(patch: ProfileUpdateRequest): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      profile.value = await brainFetch<ProfileDto>('PUT', 'profile', { body: patch });
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save profile.';
      throw e;
    } finally {
      loading.value = false;
    }
  }

  async function saveSetting(key: string, value: string | null): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const body: ProfileSettingWriteRequest = { value: value ?? undefined };
      profile.value = await brainFetch<ProfileDto>(
        'PUT',
        `profile/settings/${encodeURIComponent(key)}`,
        { body },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to save setting.';
      throw e;
    } finally {
      loading.value = false;
    }
  }

  async function deleteSetting(key: string): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      await brainFetch<void>(
        'DELETE',
        `profile/settings/${encodeURIComponent(key)}`,
      );
      // Reload so the {@code webUiSettings} map matches what the
      // server now thinks. Cheaper than reasoning about stale keys.
      await load();
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to delete setting.';
      throw e;
    } finally {
      loading.value = false;
    }
  }

  return { profile, loading, error, load, saveIdentity, saveSetting, deleteSetting };
}
