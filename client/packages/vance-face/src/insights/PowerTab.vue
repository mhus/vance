<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { LlmPowerUserDto } from '@vance/generated';
import { VAlert, VButton, VGroup, VGroupRow, VIcon, VInput, VToggle, useConfirm } from '@/components';
import { useLlmPower } from '@/composables/useLlmPower';
import { TENANT_KEY, userKey } from './llmPower';

/**
 * Insights → Power: the LLM emergency stop of this tenant. The main switch
 * refuses every model call of the tenant; a user switch refuses the calls made
 * for that user. Tenant admins only — the brain enforces that.
 *
 * A switch changes colour at once and spins until the brain confirms that a
 * pod enforces the new position (see useLlmPower).
 */
const { t } = useI18n();
const confirmAction = useConfirm();
const power = useLlmPower();

const search = ref('');
const onlyOff = ref(false);

onMounted(() => {
  void power.load();
});

const status = computed(() => power.status.value);
const tenantPending = computed(() => power.pending.value.has(TENANT_KEY));

const users = computed<LlmPowerUserDto[]>(() => {
  const needle = search.value.trim().toLowerCase();
  return (status.value?.users ?? []).filter((u) => {
    if (onlyOff.value && u.enabled) return false;
    if (!needle) return true;
    return u.userId.toLowerCase().includes(needle) || (u.title ?? '').toLowerCase().includes(needle);
  });
});

const offCount = computed(() => (status.value?.users ?? []).filter((u) => !u.enabled).length);

function fmtTime(value: Date | string | undefined): string {
  if (value == null) return '';
  const ms = value instanceof Date ? value.getTime() : Date.parse(String(value));
  return Number.isNaN(ms) ? '' : new Date(ms).toLocaleString();
}

function offLine(since: Date | string | undefined, note: string | undefined): string {
  const when = fmtTime(since);
  if (when && note) return t('insights.power.offSinceBy', { when, note });
  if (when) return t('insights.power.offSince', { when });
  return note ?? '';
}

function userDescription(u: LlmPowerUserDto): string {
  const parts: string[] = [];
  if (!u.known) parts.push(t('insights.power.unknownUser'));
  else if (u.serviceAccount) parts.push(t('insights.power.serviceAccount'));
  if (!u.enabled) parts.push(offLine(u.since, u.note));
  if (power.unconfirmed.value.has(userKey(u.userId))) parts.push(t('insights.power.unconfirmed'));
  return parts.filter(Boolean).join(' · ');
}

async function onToggleTenant(): Promise<void> {
  const current = status.value;
  if (!current || tenantPending.value) return;
  const next = !current.enabled;
  if (!next && !await confirmAction({
    message: t('insights.power.confirmOff'),
    confirmLabel: t('insights.power.switchOff'),
    danger: true,
  })) return;
  await power.setTenant(next);
}

async function onToggleUser(u: LlmPowerUserDto): Promise<void> {
  if (power.pending.value.has(userKey(u.userId))) return;
  await power.setUser(u.userId, !u.enabled);
}
</script>

<template>
  <div class="flex flex-col gap-6 p-4 max-w-3xl">
    <div class="flex flex-wrap items-center gap-3 text-sm">
      <VButton variant="secondary" size="sm" icon="refresh" :disabled="power.loading.value" @click="power.load()">
        {{ $t('insights.power.refresh') }}
      </VButton>
      <span class="text-xs opacity-60">{{ $t('insights.power.hint') }}</span>
    </div>

    <VAlert v-if="power.error.value" variant="error">{{ power.error.value }}</VAlert>

    <div v-if="!status && power.loading.value" class="text-sm opacity-60">
      {{ $t('insights.power.loading') }}
    </div>

    <template v-if="status">
      <!-- ─── Main switch ─── -->
      <section class="flex items-center gap-6">
        <button
          type="button"
          :class="['power-main', status.enabled ? 'power-on' : 'power-off']"
          :aria-pressed="status.enabled"
          :aria-busy="tenantPending"
          :title="status.enabled ? $t('insights.power.switchOff') : $t('insights.power.switchOn')"
          :disabled="tenantPending"
          @click="onToggleTenant"
        >
          <VIcon :name="tenantPending ? 'loading' : 'power'" :size="20" :class="{ 'animate-spin': tenantPending }" />
        </button>
        <div class="flex flex-col gap-1">
          <div class="text-lg font-semibold">
            {{ status.enabled ? $t('insights.power.tenantOn') : $t('insights.power.tenantOff') }}
          </div>
          <div class="text-sm opacity-70">
            {{ status.enabled ? $t('insights.power.tenantOnBody') : $t('insights.power.tenantOffBody') }}
          </div>
          <div v-if="!status.enabled" class="text-xs opacity-60">{{ offLine(status.since, status.note) }}</div>
          <div v-if="tenantPending" class="text-xs opacity-60">{{ $t('insights.power.pending') }}</div>
          <div v-if="power.unconfirmed.value.has(TENANT_KEY)" class="text-xs text-(--v-warning)">
            {{ $t('insights.power.unconfirmed') }}
          </div>
        </div>
      </section>

      <!-- ─── Users ─── -->
      <section class="flex flex-col gap-3">
        <div class="flex flex-wrap items-end gap-4">
          <div class="w-64">
            <VInput v-model="search" size="sm" :placeholder="$t('insights.power.searchPlaceholder')" />
          </div>
          <VToggle v-model="onlyOff" :label="$t('insights.power.onlyOff', { n: offCount })" />
        </div>
        <VGroup
          :label="$t('insights.power.usersHeading')"
          :note="status.enabled ? $t('insights.power.usersNote') : $t('insights.power.usersNoteTenantOff')"
        >
          <VGroupRow v-if="users.length === 0" :label="$t('insights.power.noUsers')" disabled />
          <VGroupRow
            v-for="u in users"
            :key="u.userId"
            :label="u.title || u.userId"
            :meta="u.title ? u.userId : undefined"
            :description="userDescription(u)"
          >
            <button
              type="button"
              :class="['power-user', u.enabled ? 'power-on' : 'power-off']"
              :aria-pressed="u.enabled"
              :aria-busy="power.pending.value.has(userKey(u.userId))"
              :title="u.enabled ? $t('insights.power.switchUserOff', { user: u.userId }) : $t('insights.power.switchUserOn', { user: u.userId })"
              :disabled="power.pending.value.has(userKey(u.userId))"
              @click="onToggleUser(u)"
            >
              <VIcon
                :name="power.pending.value.has(userKey(u.userId)) ? 'loading' : 'power'"
                :class="{ 'animate-spin': power.pending.value.has(userKey(u.userId)) }"
              />
              <span>{{ u.enabled ? $t('insights.power.on') : $t('insights.power.off') }}</span>
            </button>
          </VGroupRow>
        </VGroup>
      </section>
    </template>
  </div>
</template>

<style scoped>
/*
 * Red/green is the meaning here, not decoration: the one place in the
 * workspace where a control's colour is its state. Tokens only, so dark mode
 * follows (corporate-design.md, status colours).
 */
.power-main {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 96px;
  height: 96px;
  border-radius: 9999px;
  border: 0;
  color: var(--v-accent-on);
  cursor: pointer;
  transition: background-color 150ms ease, transform 100ms ease;
}
.power-main :deep(svg) {
  width: 44px;
  height: 44px;
}
.power-main:active:not(:disabled) {
  transform: scale(0.97);
}
.power-main:disabled {
  cursor: progress;
  opacity: 0.85;
}
.power-main.power-on {
  background: var(--v-success);
}
.power-main.power-off {
  background: var(--v-danger);
}
.power-main:focus-visible,
.power-user:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--v-focus-ring);
}

.power-user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 72px;
  justify-content: center;
  padding: 3px 10px;
  border-radius: 9999px;
  border: 1px solid currentColor;
  background: transparent;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.power-user:disabled {
  cursor: progress;
}
.power-user.power-on {
  color: var(--v-success);
}
.power-user.power-off {
  color: var(--v-danger);
}
</style>
