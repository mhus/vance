<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VCard, VCheckbox, VInput } from '@/components';
import { useAdminProjectGroups } from '@/composables/useAdminProjectGroups';
import type { ProjectGroupSummary } from '@vance/generated';

/**
 * Project-group properties card — the port of the Scopes page's group card:
 * immutable {@code name}, editable {@code title} / {@code enabled}, save and
 * guarded delete through {@code admin/project-groups} via
 * {@link useAdminProjectGroups}. The reserved {@code archived} group is
 * read-only. Same i18n keys as Scopes ({@code scopes.group.*}).
 *
 * The host owns the group list ({@code groups} prop) and the navigation —
 * this card only emits what happened ({@code banner}, {@code deleted}).
 */
const props = defineProps<{
  name: string;
  groups: ProjectGroupSummary[];
}>();

const emit = defineEmits<{
  (e: 'banner', message: string | null): void;
  (e: 'deleted'): void;
}>();

const { t } = useI18n();
const groupsState = useAdminProjectGroups();

const ARCHIVED_GROUP = 'archived';
const isReservedGroup = computed(() => props.name === ARCHIVED_GROUP);

const group = computed<ProjectGroupSummary | null>(
  () => props.groups.find(g => g.name === props.name) ?? null,
);

const form = reactive({ title: '', enabled: true });

watch(group, (g) => {
  form.title = g?.title ?? '';
  form.enabled = g?.enabled ?? true;
}, { immediate: true });

async function saveGroup(): Promise<void> {
  try {
    await groupsState.update(props.name, {
      title: form.title,
      enabled: form.enabled,
    });
    emit('banner', t('scopes.group.saved'));
  } catch {
    /* state.error */
  }
}

async function deleteGroup(): Promise<void> {
  if (!window.confirm(t('scopes.group.confirmDelete', { name: props.name }))) return;
  try {
    await groupsState.remove(props.name);
    emit('banner', t('scopes.group.deleted', { name: props.name }));
    emit('deleted');
  } catch {
    /* state.error */
  }
}
</script>

<template>
  <VCard :title="$t('scopes.group.cardTitle', { name })">
    <VAlert v-if="isReservedGroup" variant="info" class="mb-3">
      <span>{{ $t('scopes.group.reservedNote') }}</span>
    </VAlert>
    <div v-if="!group" class="opacity-70">{{ $t('scopes.loading') }}</div>
    <div v-else class="flex flex-col gap-3">
      <VInput
        :model-value="group.name"
        :label="$t('scopes.common.name')"
        disabled
        :help="$t('scopes.group.nameImmutable')"
        @update:model-value="() => {}"
      />
      <VInput v-model="form.title" :label="$t('scopes.common.title')" />
      <VCheckbox v-model="form.enabled" :label="$t('scopes.common.enabled')" />
      <div class="flex justify-between">
        <VButton
          variant="danger"
          :disabled="isReservedGroup"
          :loading="groupsState.busy.value"
          @click="deleteGroup"
        >{{ $t('scopes.group.delete') }}</VButton>
        <VButton variant="primary" :loading="groupsState.busy.value" @click="saveGroup">
          {{ $t('scopes.common.save') }}
        </VButton>
      </div>
    </div>
  </VCard>
</template>
