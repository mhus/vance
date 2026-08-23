<script setup lang="ts">
import { ref, watch } from 'vue';
import { VAlert, VButton, VCard, VEmptyState, VInput } from '@vance/components';
import { decide, loadOperatorQueue } from './api';
import type { OperatorView } from './types';

const props = defineProps<{ projectId: string; sourceId: string }>();

const view = ref<OperatorView | null>(null);
const loading = ref(false);
const error = ref('');
const notice = ref('');

// No sign-in here. Whether this account may operate is the store's answer,
// asked with the link this installation already holds — a password prompt
// would establish nothing the store does not already know, and would teach
// people to type their store password into a brain screen.
const rejecting = ref('');
const reason = ref('');

async function openQueue(): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    view.value = await loadOperatorQueue(props.projectId, props.sourceId);
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not open the queue.';
  } finally {
    loading.value = false;
  }
}

watch(() => [props.projectId, props.sourceId], openQueue, { immediate: true });

async function run(
  decision: 'approve-vendor' | 'reject-vendor' | 'approve-release' | 'reject-release',
  body: { vendor?: string; kitId?: string; version?: string; reason?: string },
): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    view.value = await decide(props.projectId, decision, {
      sourceId: props.sourceId,
      ...body,
    });
    notice.value = 'Done.';
    rejecting.value = '';
    reason.value = '';
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Could not apply that decision.';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-if="notice" variant="info">{{ notice }}</VAlert>

    <div class="flex items-center gap-2">
      <VButton size="sm" :disabled="loading" @click="openQueue">
        {{ loading ? '…' : 'Refresh' }}
      </VButton>
      <span class="text-xs opacity-60">
        Acting as this installation's store account.
      </span>
    </div>

    <!-- ── vendors waiting ── -->
    <VCard v-if="view">
      <div class="font-semibold mb-1">Vendors waiting</div>
      <div class="text-xs opacity-70 mb-2">
        What is being decided here is the handle: whether the name claims an
        affiliation the applicant does not have. Everything else in the intake is
        already checked.
      </div>
      <VEmptyState
        v-if="view.pendingVendors.length === 0"
        headline="Nothing waiting"
        body="No vendor applications."
      />
      <div v-for="vendor in view.pendingVendors" :key="vendor.name" class="py-2 border-t">
        <div class="flex items-start justify-between gap-4">
          <div>
            <div class="font-mono">{{ vendor.name }}</div>
            <div class="text-sm opacity-70">
              {{ vendor.displayName }}
              <span v-if="vendor.homepage"> · {{ vendor.homepage }}</span>
              <span v-if="vendor.termsVersion"> · terms {{ vendor.termsVersion }}</span>
            </div>
          </div>
          <div class="flex gap-2 shrink-0">
            <VButton
              size="sm"
              :disabled="loading"
              @click="run('approve-vendor', { vendor: vendor.name })"
            >
              Approve
            </VButton>
            <VButton
              size="sm"
              variant="secondary"
              outline
              @click="rejecting = `vendor:${vendor.name}`"
            >
              Refuse
            </VButton>
          </div>
        </div>
        <div v-if="rejecting === `vendor:${vendor.name}`" class="mt-2 flex gap-2">
          <!-- Width on a wrapper, not on the field: VInput's root already
               carries w-full, and a second width class squeezes the button. -->
          <div class="grow">
            <VInput v-model="reason" label="Reason" />
          </div>
          <VButton
            size="sm"
            :disabled="loading || !reason"
            @click="run('reject-vendor', { vendor: vendor.name, reason })"
          >
            Send
          </VButton>
        </div>
      </div>
    </VCard>

    <!-- ── releases waiting ── -->
    <VCard v-if="view">
      <div class="font-semibold mb-1">Releases waiting</div>
      <VEmptyState
        v-if="view.submittedReleases.length === 0"
        headline="Nothing waiting"
        body="No submitted releases."
      />
      <div
        v-for="release in view.submittedReleases"
        :key="`${release.vendorName}/${release.kitId}/${release.version}`"
        class="py-2 border-t"
      >
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="font-medium truncate">
              {{ release.vendorName }}/{{ release.kitId }} {{ release.version }}
            </div>
            <div class="text-sm opacity-70">
              submitted
              <span v-if="release.submittedAt">
                {{ new Date(release.submittedAt).toLocaleString() }}
              </span>
            </div>
          </div>
          <div class="flex gap-2 shrink-0">
            <VButton
              size="sm"
              :disabled="loading"
              @click="run('approve-release', {
                vendor: release.vendorName,
                kitId: release.kitId,
                version: release.version,
              })"
            >
              Publish
            </VButton>
            <VButton
              size="sm"
              variant="secondary"
              outline
              @click="rejecting = `release:${release.vendorName}/${release.kitId}/${release.version}`"
            >
              Refuse
            </VButton>
          </div>
        </div>
        <!--
          A refusal needs a reason. It is the only thing the developer will
          read, and one nobody can act on wastes their next attempt as
          surely as no answer at all.
        -->
        <div
          v-if="rejecting === `release:${release.vendorName}/${release.kitId}/${release.version}`"
          class="mt-2 flex gap-2"
        >
          <!-- Width on a wrapper, not on the field: VInput's root already
               carries w-full, and a second width class squeezes the button. -->
          <div class="grow">
            <VInput v-model="reason" label="Reason" />
          </div>
          <VButton
            size="sm"
            :disabled="loading || !reason"
            @click="run('reject-release', {
              vendor: release.vendorName,
              kitId: release.kitId,
              version: release.version,
              reason,
            })"
          >
            Send
          </VButton>
        </div>
      </div>
    </VCard>
  </div>
</template>
