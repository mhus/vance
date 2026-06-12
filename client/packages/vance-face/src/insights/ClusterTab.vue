<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type {
  BrainPodInsightsDto,
  BrainPodProjectInsightsDto,
} from '@vance/generated';
import { VAlert, VButton, VEmptyState } from '@/components';
import { useClusterPods } from '@/composables/useCluster';

const state = useClusterPods();

type SortKey =
  | 'node'
  | 'status'
  | 'endpoint'
  | 'heartbeat'
  | 'booted'
  | 'version'
  | 'projects';
type SortDir = 'asc' | 'desc';

/**
 * Default sort matches the server: by node name ascending. Click a
 * header to switch column, click the same header again to flip
 * direction.
 */
const sortKey = ref<SortKey>('node');
const sortDir = ref<SortDir>('asc');

function toggleSort(key: SortKey): void {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    sortDir.value = defaultDirection(key);
  }
}

/** Numeric/time columns default to descending — recent/loaded first. */
function defaultDirection(key: SortKey): SortDir {
  switch (key) {
    case 'heartbeat':
    case 'booted':
    case 'projects':
      return 'desc';
    default:
      return 'asc';
  }
}

function sortIndicator(key: SortKey): string {
  if (sortKey.value !== key) return '';
  return sortDir.value === 'asc' ? '▲' : '▼';
}

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

function projectChipClass(p: BrainPodProjectInsightsDto): string {
  switch (p.lifecycleType) {
    case 'PERMANENT': return 'project-chip project-chip--permanent';
    case 'EPHEMERAL': return 'project-chip project-chip--ephemeral';
    case 'HOMELESS':  return 'project-chip project-chip--homeless';
    default:          return 'project-chip';
  }
}

function projectChipTitle(p: BrainPodProjectInsightsDto): string {
  const parts = [
    `status: ${p.status ?? '—'}`,
    `lifecycle: ${p.lifecycleType ?? '—'}`,
    `score: ${p.homeResourceScore}`,
  ];
  return parts.join(' · ');
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

/** Relative remaining lifetime "in 12s" / "in 3m" / "expired". */
function fmtRemaining(value: string | Date | undefined | null): string {
  if (value == null) return '—';
  const ts = value instanceof Date ? value.getTime() : Date.parse(String(value));
  if (Number.isNaN(ts)) return '—';
  const deltaSec = Math.floor((ts - Date.now()) / 1000);
  if (deltaSec <= 0) return 'expired';
  if (deltaSec < 60) return `in ${deltaSec}s`;
  if (deltaSec < 3600) return `in ${Math.floor(deltaSec / 60)}m`;
  if (deltaSec < 86400) return `in ${Math.floor(deltaSec / 3600)}h`;
  return `in ${Math.floor(deltaSec / 86400)}d`;
}

const totalProjects = computed(() =>
  state.pods.value.reduce((sum, p) => sum + (p.tenantProjects?.length ?? 0), 0),
);

const masterPresent = computed(
  () => state.cluster.value?.masterPodId != null && state.cluster.value.masterPodId !== '',
);

/**
 * Returns the sort key as a tuple so we can lift {@code null}s to the
 * tail regardless of direction — missing timestamps/versions belong at
 * the bottom whether you sort asc or desc. The first element flags
 * null (0/1), the second carries the real comparable value.
 */
function sortValue(pod: BrainPodInsightsDto): [number, string | number] {
  switch (sortKey.value) {
    case 'node':
      return [0, (pod.nodeName ?? '').toLowerCase()];
    case 'status':
      return [0, effectiveStatus(pod).toLowerCase()];
    case 'endpoint':
      return [pod.endpoint ? 0 : 1, (pod.endpoint ?? '').toLowerCase()];
    case 'heartbeat':
      return tsTuple(pod.lastHeartbeatAt);
    case 'booted':
      return tsTuple(pod.bootedAt);
    case 'version':
      return [pod.version ? 0 : 1, (pod.version ?? '').toLowerCase()];
    case 'projects':
      return [0, pod.tenantProjects?.length ?? 0];
  }
}

function tsTuple(value: string | Date | undefined | null): [number, number] {
  if (value == null) return [1, 0];
  const ts = value instanceof Date ? value.getTime() : Date.parse(String(value));
  return Number.isNaN(ts) ? [1, 0] : [0, ts];
}

const sortedPods = computed<BrainPodInsightsDto[]>(() => {
  const out = [...state.pods.value];
  const dir = sortDir.value === 'asc' ? 1 : -1;
  out.sort((a, b) => {
    const [aNull, aVal] = sortValue(a);
    const [bNull, bVal] = sortValue(b);
    if (aNull !== bNull) return aNull - bNull;  // nulls always last
    if (aVal < bVal) return -1 * dir;
    if (aVal > bVal) return 1 * dir;
    // Stable tiebreaker so toggling direction on equal values doesn't shuffle.
    return (a.nodeName ?? '').localeCompare(b.nodeName ?? '');
  });
  return out;
});
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

    <!-- ─── Master lease summary ─── -->
    <div v-if="state.cluster.value" class="master-banner">
      <div class="master-banner__label">Cluster master</div>
      <template v-if="masterPresent">
        <span class="master-banner__node font-mono">{{ state.cluster.value.masterNodeName ?? '—' }}</span>
        <span class="master-banner__endpoint font-mono">{{ state.cluster.value.masterEndpoint ?? '' }}</span>
        <span class="master-banner__lease" :title="fmtTime(state.cluster.value.masterLeaseUntil)">
          lease {{ fmtRemaining(state.cluster.value.masterLeaseUntil) }}
        </span>
      </template>
      <template v-else>
        <span class="master-banner__none">no master elected</span>
      </template>
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
          <th class="w-44 th-sort" @click="toggleSort('node')">
            Node <span class="th-sort__arrow">{{ sortIndicator('node') }}</span>
          </th>
          <th class="w-24 th-sort" @click="toggleSort('status')">
            Status <span class="th-sort__arrow">{{ sortIndicator('status') }}</span>
          </th>
          <th class="th-sort" @click="toggleSort('endpoint')">
            Endpoint <span class="th-sort__arrow">{{ sortIndicator('endpoint') }}</span>
          </th>
          <th class="w-32 th-sort" @click="toggleSort('heartbeat')">
            Heartbeat <span class="th-sort__arrow">{{ sortIndicator('heartbeat') }}</span>
          </th>
          <th class="w-32 th-sort" @click="toggleSort('booted')">
            Booted <span class="th-sort__arrow">{{ sortIndicator('booted') }}</span>
          </th>
          <th class="w-24 th-sort" @click="toggleSort('version')">
            Version <span class="th-sort__arrow">{{ sortIndicator('version') }}</span>
          </th>
          <th class="th-sort" @click="toggleSort('projects')">
            Projects (this tenant)
            <span class="th-sort__arrow">{{ sortIndicator('projects') }}</span>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="pod in sortedPods"
          :key="pod.podId"
          :class="{ 'pod-row--self': pod.selfPod, 'pod-row--master': pod.master }"
        >
          <td class="text-sm">
            <span class="font-mono">{{ pod.nodeName }}</span>
            <span
              v-if="pod.master"
              class="ml-1 badge-role badge-role--master"
              title="Currently holds the cluster-master lease"
            >master</span>
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
                v-for="proj in pod.tenantProjects"
                :key="proj.name"
                :class="projectChipClass(proj)"
                :title="projectChipTitle(proj)"
              >
                <span class="font-mono">{{ proj.name }}</span>
                <span class="project-chip__score">{{ proj.homeResourceScore }}</span>
              </span>
            </div>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="text-[11px] opacity-60">
      Cluster: <span class="font-mono">{{ state.cluster.value?.clusterId ?? '—' }}</span>
    </div>
  </div>
</template>

<style scoped>
.th-sort {
  cursor: pointer;
  user-select: none;
}
.th-sort:hover { background: rgba(127, 127, 127, 0.08); }
.th-sort__arrow {
  display: inline-block;
  min-width: 0.8rem;
  font-size: 0.65rem;
  opacity: 0.6;
  margin-left: 0.15rem;
}

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

.badge-role {
  display: inline-block;
  padding: 0.02rem 0.4rem;
  border-radius: 0.25rem;
  font-size: 0.6rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.badge-role--master {
  background: rgba(168, 85, 247, 0.22);
  color: #7e22ce;
}

.pod-row--self > td {
  background: rgba(59, 130, 246, 0.05);
}
.pod-row--master > td {
  box-shadow: inset 3px 0 0 0 rgba(168, 85, 247, 0.55);
}

.master-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.6rem;
  padding: 0.4rem 0.6rem;
  border-radius: 0.35rem;
  background: rgba(168, 85, 247, 0.08);
  border: 1px solid rgba(168, 85, 247, 0.25);
  font-size: 0.75rem;
}
.master-banner__label {
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: #7e22ce;
}
.master-banner__node    { font-weight: 600; }
.master-banner__endpoint{ opacity: 0.75; }
.master-banner__lease   { opacity: 0.7; }
.master-banner__none    { opacity: 0.6; font-style: italic; }

.project-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  background: rgba(127, 127, 127, 0.16);
  font-size: 0.7rem;
  line-height: 1.4;
}
.project-chip--permanent { background: rgba(34, 197, 94, 0.18);  color: #15803d; }
.project-chip--ephemeral { background: rgba(59, 130, 246, 0.16); color: #1d4ed8; }
.project-chip--homeless  { background: rgba(127, 127, 127, 0.22); }
.project-chip__score {
  font-variant-numeric: tabular-nums;
  opacity: 0.7;
  font-size: 0.62rem;
  padding-left: 0.25rem;
  border-left: 1px solid currentColor;
}
</style>
