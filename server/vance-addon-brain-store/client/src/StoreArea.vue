<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput } from '@vance/components';
import { connect, disconnect, install, loadOverview } from './api';
import type { EntryState, StoreEntry, StoreSourceView } from './types';

const props = defineProps<{ projectId?: string }>();

/**
 * The four lists are one list with a state per entry — a kit that is
 * owned and installed and updatable would otherwise appear three times.
 * These tabs filter it; they do not fetch separately.
 */
const tabs: { key: 'ALL' | EntryState; label: string }[] = [
  { key: 'ALL', label: 'All' },
  { key: 'OFFERED', label: 'Offered' },
  { key: 'OWNED', label: 'Purchased' },
  { key: 'INSTALLED', label: 'Installed' },
  { key: 'UPDATABLE', label: 'Updatable' },
];

const projectId = computed(() => props.projectId ?? '_tenant');

const views = ref<StoreSourceView[]>([]);
const activeTab = ref<'ALL' | EntryState>('ALL');
const loading = ref(false);
const busyPath = ref<string>('');
const error = ref('');
const notice = ref('');

// Sign-in form, per source. Only one is open at a time.
const signingIn = ref<string>('');
const email = ref('');
const password = ref('');
const label = ref('');

async function load(): Promise<void> {
  error.value = '';
  loading.value = true;
  try {
    views.value = await loadOverview(projectId.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not load the store.';
  } finally {
    loading.value = false;
  }
}

function entriesOf(view: StoreSourceView): StoreEntry[] {
  if (activeTab.value === 'ALL') return view.entries;
  return view.entries.filter((entry) => entry.state === activeTab.value);
}

function openSignIn(sourceId: string): void {
  signingIn.value = sourceId;
  email.value = '';
  password.value = '';
  // A label the person will recognise in their device list at the store.
  label.value = `${projectId.value}`;
}

async function submitSignIn(sourceId: string): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    const connection = await connect(
      projectId.value, sourceId, email.value, password.value, label.value || undefined,
    );
    notice.value = `Signed in as ${connection.accountId}.`;
    signingIn.value = '';
    password.value = '';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not sign in.';
  } finally {
    loading.value = false;
  }
}

async function signOut(sourceId: string): Promise<void> {
  error.value = '';
  notice.value = '';
  try {
    await disconnect(projectId.value, sourceId);
    notice.value = 'Signed out. The link at the store is still there — '
      + 'remove it in your device list if you meant to deauthorise this brain.';
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not sign out.';
  }
}

async function installEntry(entry: StoreEntry): Promise<void> {
  error.value = '';
  notice.value = '';
  busyPath.value = entry.path;
  try {
    const result = await install(projectId.value, entry.sourceId, entry.path);
    notice.value = `${result.kitName ?? entry.displayName}: ${result.mode?.toLowerCase() ?? 'done'}.`;
    if (result.warnings?.length) notice.value += ` ${result.warnings.join(' ')}`;
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not install this kit.';
  } finally {
    busyPath.value = '';
  }
}

/** What the button on a row does, if anything. */
function actionOf(entry: StoreEntry): string | null {
  if (entry.state === 'OWNED') return 'Install';
  if (entry.state === 'UPDATABLE') return 'Update';
  return null;
}

function expiryOf(entry: StoreEntry): string | null {
  if (!entry.licenseExpiresAt) return null;
  const when = new Date(entry.licenseExpiresAt);
  const lapsed = when.getTime() < Date.now();
  return lapsed
    ? `Licence lapsed on ${when.toLocaleDateString()} — installed kits keep working`
    : `Updates until ${when.toLocaleDateString()}`;
}

onMounted(load);
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info">{{ notice }}</VAlert>

    <VEmptyState
      v-if="!loading && views.length === 0"
      headline="No library configured"
      body="Add a library source in _vance/config/kit-sources.yaml of the _tenant project."
    />

    <VCard v-for="view in views" :key="view.sourceId">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="font-semibold">{{ view.sourceId }}</div>
          <div class="text-sm opacity-70">{{ view.url }}</div>
          <div v-if="view.accountId" class="text-sm mt-1">
            Signed in as <span class="font-mono">{{ view.accountId }}</span>
          </div>
          <div v-else class="text-sm mt-1 opacity-70">Not signed in</div>
        </div>
        <div class="flex gap-2">
          <VButton v-if="view.accountId" size="sm" @click="signOut(view.sourceId)">
            Sign out
          </VButton>
          <VButton
            v-else-if="signingIn !== view.sourceId"
            size="sm"
            @click="openSignIn(view.sourceId)"
          >
            Sign in
          </VButton>
        </div>
      </div>

      <!--
        Unreachable is not the same as empty: a store that could not be
        asked must not read as a store with nothing for sale.
      -->
      <VAlert v-if="!view.reachable" variant="warning" class="mt-3">
        This store could not be reached: {{ view.problem }}
      </VAlert>

      <div v-if="signingIn === view.sourceId" class="mt-3 flex flex-col gap-2">
        <VInput v-model="email" label="Email" type="email" autocomplete="username" />
        <VInput
          v-model="password"
          label="Password"
          type="password"
          autocomplete="current-password"
        />
        <VInput
          v-model="label"
          label="Name for this brain"
          help="Shown in your device list at the store, so you can tell your machines apart."
        />
        <div class="flex gap-2">
          <VButton :disabled="loading" @click="submitSignIn(view.sourceId)">Sign in</VButton>
          <VButton variant="secondary" outline @click="signingIn = ''">Cancel</VButton>
        </div>
      </div>

      <div v-if="view.reachable" class="mt-4">
        <div class="flex gap-2 mb-3">
          <VButton
            v-for="tab in tabs"
            :key="tab.key"
            size="sm"
            :variant="activeTab === tab.key ? 'primary' : 'secondary'"
            :outline="activeTab !== tab.key"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
          </VButton>
        </div>

        <VEmptyState
          v-if="entriesOf(view).length === 0"
          headline="Nothing here"
          :body="view.accountId
            ? 'Nothing in this list yet.'
            : 'Sign in to see what this account owns.'"
        />

        <div
          v-for="entry in entriesOf(view)"
          :key="entry.path"
          class="flex items-start justify-between gap-4 py-2 border-t"
        >
          <div class="min-w-0">
            <div class="font-medium truncate">{{ entry.displayName }}</div>
            <div class="text-sm opacity-70 truncate">
              {{ entry.path }}
              <span v-if="entry.availableVersion"> · {{ entry.availableVersion }}</span>
              <span v-if="entry.installedVersion && entry.state === 'UPDATABLE'">
                (installed {{ entry.installedVersion }})
              </span>
            </div>
            <div v-if="entry.description" class="text-sm mt-1">{{ entry.description }}</div>
            <div v-if="expiryOf(entry)" class="text-xs mt-1 opacity-70">
              {{ expiryOf(entry) }}
            </div>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <span class="text-xs opacity-60">{{ entry.state }}</span>
            <VButton
              v-if="actionOf(entry)"
              size="sm"
              :disabled="busyPath === entry.path || !entry.downloadable"
              @click="installEntry(entry)"
            >
              {{ busyPath === entry.path ? '…' : actionOf(entry) }}
            </VButton>
          </div>
        </div>
      </div>
    </VCard>
  </div>
</template>
