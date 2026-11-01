import { onMounted, ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { SchedulerPolicyDto } from '@vance/generated';

/**
 * The finest cadence this brain lets cron schedulers keep
 * ({@code vance.scheduler.granularity}, spec `scheduler.md` §14). `null`
 * while unknown or when the lookup failed — the UI then shows no hint
 * instead of a wrong one.
 */
export type SchedulerGranularityValue = SchedulerPolicyDto['granularity'];

export function useSchedulerPolicy(
  projectId: () => string | null | undefined,
): { granularity: Ref<SchedulerGranularityValue | null> } {
  const granularity = ref<SchedulerGranularityValue | null>(null);

  onMounted(async () => {
    const project = projectId();
    if (!project) return;
    try {
      const policy = await brainFetch<SchedulerPolicyDto>(
        'GET',
        `project/${encodeURIComponent(project)}/scheduler-policy`,
      );
      granularity.value = policy.granularity;
    } catch {
      granularity.value = null;
    }
  });

  return { granularity };
}
