<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VInput, VModal } from '@vance/components';
import {
  connectionBlobFor,
  createIntegrationToken,
  integrationTokenIsLive,
  listIntegrationTokens,
  revokeIntegrationToken,
} from '@vance/shared';
import type { IntegrationTokenDto } from '@vance/generated';

/**
 * Manage the capture credentials for this link list.
 *
 * <p>What this dialog hands out is a credential that leaves the building, so
 * two things are said out loud rather than left to be discovered:
 *
 * <ol>
 *   <li><b>The token is shown once.</b> The server keeps no copy — that is the
 *       point — so a closed dialog means minting again.</li>
 *   <li><b>The confinement is the project, not this list.</b> The token is
 *       pinned to the project; the folder travels in the connection string as
 *       a destination. A tool that changes it reaches another list in the same
 *       project. Calling it "the token for this list" would be a comfortable
 *       lie.</li>
 * </ol>
 */
const props = defineProps<{
  projectId: string;
  /** The app folder — the capture destination, not a permission. */
  folder: string;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

/** The profile the links addon declares. */
const PROFILE = 'links-capture';

const open = ref(true);
const busy = ref(false);
const error = ref<string | null>(null);
const tokens = ref<IntegrationTokenDto[]>([]);

const label = ref('Browser extension');
const days = ref('365');

/** Set for exactly as long as the dialog stays open after a mint. */
const freshBlob = ref<string | null>(null);
const copied = ref(false);

/**
 * This list's tokens: same profile, same project.
 *
 * Filtered here rather than server-side — a person has a handful of these, and
 * a query parameter would be a filter surface built for one caller. The
 * project is as narrow as the filter can honestly be, because it is as narrow
 * as the token is.
 */
const mine = computed(() =>
  tokens.value.filter((t) => t.scopeProfile === PROFILE && t.projectId === props.projectId),
);

const live = computed(() => mine.value.filter(integrationTokenIsLive));

onMounted(load);

async function load(): Promise<void> {
  busy.value = true;
  error.value = null;
  try {
    tokens.value = await listIntegrationTokens();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function onCreate(): Promise<void> {
  const parsed = Number.parseInt(days.value, 10);
  if (!Number.isFinite(parsed) || parsed < 1) {
    error.value = 'Lifetime must be a number of days.';
    return;
  }
  busy.value = true;
  error.value = null;
  try {
    const minted = await createIntegrationToken({
      scopeProfile: PROFILE,
      projectId: props.projectId,
      label: label.value.trim() || 'Browser extension',
      expiresInDays: parsed,
    });
    // The one moment the value exists. Everything below hangs off this.
    freshBlob.value = connectionBlobFor({
      projectId: props.projectId,
      target: props.folder,
      profile: PROFILE,
      token: minted.token ?? '',
      expiresAt: minted.expiresAtTimestamp ?? undefined,
    });
    copied.value = false;
    // Straight to the clipboard: this string exists exactly once, and the
    // whole reason somebody pressed Create is to paste it somewhere else.
    // Waiting for a second click is one keystroke away from losing it.
    await onCopy(true);
    await load();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function onRevoke(token: IntegrationTokenDto): Promise<void> {
  if (!window.confirm(
    `Revoke “${token.label}”? Anything still using it stops working within seconds.`,
  )) return;
  busy.value = true;
  error.value = null;
  try {
    await revokeIntegrationToken(token.tokenId);
    await load();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}

/**
 * @param silent true for the copy attempted right after minting, where a
 *               refusal is not something the person asked for and must not
 *               look like their action failed.
 */
async function onCopy(silent = false): Promise<void> {
  if (!freshBlob.value) return;
  try {
    await navigator.clipboard.writeText(freshBlob.value);
    copied.value = true;
  } catch {
    // Clipboard access can be refused — permissions, an insecure context, a
    // browser that wants a user gesture. The block below is selectable, so the
    // manual route always exists; saying so beats a button that does nothing.
    if (!silent) {
      error.value = 'Could not reach the clipboard — select the text and copy it manually.';
    }
  }
}

/**
 * Closing throws the connection string away — the server keeps no copy.
 *
 * <p>There is deliberately **no confirm** here. ESC closes the native
 * `<dialog>` before this runs, so a question asked at this point could not
 * cancel the close it is asking about — a guard that works from the ✕ and
 * silently fails from the keyboard is worse than none. The loss is removed
 * instead of confirmed: minting copies to the clipboard straight away, so by
 * the time anybody can close this, the string is already somewhere.
 */
function onClose(): void {
  open.value = false;
  emit('close');
}

function stamp(millis?: number | null): string {
  return millis ? new Date(millis).toISOString().slice(0, 10) : '—';
}

function message(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e) return String((e as Error).message);
  return String(e);
}
</script>

<template>
  <!-- `:model-value` rather than `v-model`: with both, the built-in binding
       and an explicit listener would each act on a close, and this component
       owns what closing means. -->
  <VModal
    :model-value="open"
    title="Capture access"
    size="lg"
    :close-on-backdrop="false"
    @update:model-value="onClose()"
  >
    <div class="flex flex-col gap-4">
      <p class="text-sm opacity-70">
        A capture token lets an outside tool — a browser extension, a shell alias — add
        links without a login. It can look up one URL, read the group names, and save.
        It cannot read this list, change an entry, or delete one.
      </p>

      <!-- The honest scope. Stated up front, not in a footnote: somebody
           handing this to a tool has to know what it reaches. -->
      <VAlert variant="info">
        The token is pinned to project <b>{{ projectId }}</b>. The folder
        <code>{{ folder }}</code> travels with it as the destination, not as a limit —
        a tool that changes it reaches another link list in the same project.
      </VAlert>

      <VAlert v-if="error" variant="error" class="whitespace-pre-line">{{ error }}</VAlert>

      <!-- Existing tokens -->
      <div class="flex flex-col gap-2">
        <span class="text-xs font-semibold uppercase tracking-wide opacity-50">
          Tokens for this project
        </span>
        <p v-if="mine.length === 0 && !busy" class="text-sm opacity-60">
          None yet.
        </p>
        <div
          v-for="t in mine"
          :key="t.tokenId"
          class="flex items-center gap-3 rounded border border-base-300 px-3 py-2"
        >
          <div class="flex min-w-0 flex-1 flex-col">
            <span class="truncate font-medium" :class="integrationTokenIsLive(t) ? '' : 'line-through opacity-50'">
              {{ t.label }}
            </span>
            <!-- Last used is the only thing that ever reveals a token nobody
                 remembers handing out. -->
            <span class="text-xs opacity-60">
              created {{ stamp(t.createdAtTimestamp) }} ·
              expires {{ stamp(t.expiresAtTimestamp) }} ·
              last used {{ t.lastUsedAtTimestamp ? stamp(t.lastUsedAtTimestamp) : 'never' }}
              <template v-if="t.revokedAtTimestamp">
                · revoked {{ stamp(t.revokedAtTimestamp) }}
              </template>
            </span>
          </div>
          <VButton
            v-if="integrationTokenIsLive(t)"
            size="xs"
            variant="ghost"
            :disabled="busy"
            @click="onRevoke(t)"
          >
            Revoke
          </VButton>
        </div>
      </div>

      <!-- Mint -->
      <div class="flex flex-col gap-2 border-t border-base-300 pt-4">
        <span class="text-xs font-semibold uppercase tracking-wide opacity-50">
          New token
        </span>
        <div class="flex items-end gap-2">
          <div class="min-w-0 flex-1">
            <VInput v-model="label" label="Label" placeholder="Browser extension" :disabled="busy" />
          </div>
          <div class="w-32 flex-none">
            <VInput v-model="days" label="Days" :disabled="busy" />
          </div>
          <VButton variant="primary" :disabled="busy" @click="onCreate()">
            Create
          </VButton>
        </div>
        <p v-if="live.length > 0" class="text-xs opacity-60">
          {{ live.length }} live token{{ live.length === 1 ? '' : 's' }} already —
          a new one does not replace them.
        </p>
      </div>

      <!-- The one moment the value exists -->
      <div v-if="freshBlob" class="flex flex-col gap-2 border-t border-base-300 pt-4">
        <div class="flex items-center gap-2">
          <span class="text-xs font-semibold uppercase tracking-wide opacity-50">
            Connection string
          </span>
          <VButton size="xs" :variant="copied ? 'primary' : 'ghost'" @click="onCopy()">
            {{ copied ? '✓ Copied' : '⧉ Copy' }}
          </VButton>
        </div>
        <VAlert :variant="copied ? 'success' : 'warning'">
          <template v-if="copied">
            Copied to your clipboard. It is shown once and not stored — paste it before
            you copy anything else.
          </template>
          <template v-else>
            Shown once. The server keeps no copy — if you lose it, revoke this token and
            create another.
          </template>
        </VAlert>
        <!-- A `pre` rather than a textarea: this is a value to take away, not
             to edit, and `select-all` makes one click grab the whole string
             for the case where the clipboard button is refused. -->
        <pre
          class="max-h-40 select-all overflow-y-auto whitespace-pre-wrap break-all rounded
                 border border-base-300 p-2 text-xs"
        >{{ freshBlob }}</pre>
        <p class="text-xs opacity-60">
          Holds the brain URL, tenant, project, folder, the token and a checksum.
          Paste it into the extension as one value.
        </p>
      </div>
    </div>

    <template #actions>
      <VButton variant="ghost" @click="onClose()">Close</VButton>
    </template>
  </VModal>
</template>
