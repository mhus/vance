<script setup lang="ts">
import { computed, onMounted } from 'vue';
import type { BrainPodInsightsDto } from '@vance/generated';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useClusterPods } from '@/composables/useCluster';

const state = useClusterPods();

onMounted(() => {
  void state.load();
});

function refresh(): void {
  void state.load();
}

/**
 * Effective status for the badge: STALE wins over the pod's own
 * self-reported status. The wire still carries both — STALE comes
 * derived from {@code lastHeartbeatAt} on the answering brain.
 */
function effectiveStatus(pod: BrainPodInsightsDto): string {
  return pod.stale ? 'STALE' : pod.status;
}

function statusClass(pod: BrainPodInsightsDto): string {
  const eff = effectiveStatus(pod);
  switch (eff) {
    case 'RUNNING':  return 'badge-status badge-status--running';
    case 'STARTING': return 'badge-status badge-status--starting';
    case 'STOPPING': return 'badge-status badge-status--stopping';
    case 'STOPPED':  return 'badge-status badge-status--stopped';
    case 'STALE':    return 'badge-status badge-status--stale';
    default:         return 'badge-status';
  }
}

function fmtTime(value: string | Date | undefined | null): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString().replace('T', ' ').slice(0, 19);
  return String(value).replace('T', ' ').slice(0, 19);
}

/** Relative age "12s ago" / "3m ago" / "2h ago" — handy for heartbeats. */
function fmtAge(value: string | Date | undefined | null): string {
  if (value == null) return '—';
  const ts = value instanceof Date ? value.getTime() : Date.parse(String(value));
  if (Number.isNaN(ts)) return '—';
  const deltaSec = Math.max(0, Math.floor((Date.now() - ts) / 1000));
  if (deltaSec < 60) return `${deltaSec}s ago`;
  if (deltaSec < 3600) return `${Math.floor(deltaSec / 60)}m ago`;
  if (deltaSec < 86400) return `${Math.floor(deltaSec / 3600)}h ago`;
  return `${Math.floor(deltaSec / 86400)}d ago`;
}

const totalProjects = computed(() =>
  state.pods.value.reduce((sum, p) => sum + (p.tenantProjects?.length ?? 0), 0),
);
</script>

<template>
  <div class="flex flex-col gap-3 p-4">
    <!-- ─── Toolbar ─── -->
    <div class="flex flex-wrap items-end gap-3 text-sm">
      <VButton variant="ghost" size="sm" @click="refresh">
        Refresh
      </VButton>
      <div class="text-xs opacity-60 ml-auto">
        {{ state.pods.value.length }} pod{{ state.pods.value.length === 1 ? '' : 's' }}
        · {{ totalProjects }} project{{ totalProjects === 1 ? '' : 's' }} (this tenant)
      </div>
    </div>

    <div v-if="state.loading.value" class="text-sm opacity-60">Loading cluster…</div>

    <VAlert v-else-if="state.error.value" variant="error">
      {{ state.error.value }}
    </VAlert>

    <VEmptyState
      v-else-if="state.pods.value.length === 0"
      :headline="'No pods'"
      :body="'No brain pods are registered in this cluster.'"
    />

    <table v-else class="table table-sm">
      <thead>
        <tr>
          <th class="w-44">Node</th>
          <th class="w-24">Status</th>
          <th>Endpoint</th>
          <th class="w-32">Heartbeat</th>
          <th class="w-32">Booted</th>
          <th class="w-24">Version</th>
          <th>Projects (this tenant)</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="pod in state.pods.value"
          :key="pod.podId"
          :class="{ 'pod-row--self': pod.selfPod }"
        >
          <td class="text-sm">
            <span class="font-mono">{{ pod.nodeName }}</span>
            <span
              v-if="pod.selfPod"
              class="ml-1 text-[10px] uppercase tracking-wider opacity-70"
              title="This is the brain currently serving the request"
            >self</span>
            <div class="text-[10px] opacity-50 font-mono truncate" :title="pod.podId">
              {{ pod.podId }}
            </div>
          </td>
          <td>
            <span :class="statusClass(pod)">{{ effectiveStatus(pod).toLowerCase() }}</span>
          </td>
          <td class="font-mono text-xs">{{ pod.endpoint || '—' }}</td>
          <td class="text-xs opacity-80" :title="fmtTime(pod.lastHeartbeatAt)">
            {{ fmtAge(pod.lastHeartbeatAt) }}
          </td>
          <td class="text-xs opacity-70">{{ fmtTime(pod.bootedAt) }}</td>
          <td class="text-xs opacity-70">{{ pod.version ?? '—' }}</td>
          <td class="text-xs">
            <div v-if="pod.tenantProjects.length === 0" class="opacity-50">—</div>
            <div v-else class="flex flex-wrap gap-1">
              <span
                v-for="name in pod.tenantProjects"
                :key="name"
                class="project-chip font-mono"
              >{{ name }}</span>
            </div>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="text-[11px] opacity-60">
      Cluster: <span class="font-mono">{{ state.pods.value[0]?.clusterId ?? '—' }}</span>
    </div>
  </div>
</template>

<style scoped>
.badge-status {
  display: inline-block;
  padding: 0.05rem 0.45rem;
  border-radius: 0.25rem;
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: lowercase;
  letter-spacing: 0.02em;
  background: rgba(127, 127, 127, 0.18);
}
.badge-status--running  { background: rgba(34, 197, 94, 0.22);  color: #16a34a; }
.badge-status--starting { background: rgba(59, 130, 246, 0.22); color: #2563eb; }
.badge-status--stopping { background: rgba(234, 179, 8, 0.22);  color: #b45309; }
.badge-status--stopped  { background: rgba(127, 127, 127, 0.22); }
.badge-status--stale    { background: rgba(239, 68, 68, 0.22);  color: #b91c1c; }

.pod-row--self > td {
  background: rgba(59, 130, 246, 0.05);
}

.project-chip {
  display: inline-block;
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  background: rgba(127, 127, 127, 0.16);
  font-size: 0.7rem;
  line-height: 1.4;
}
</style>
