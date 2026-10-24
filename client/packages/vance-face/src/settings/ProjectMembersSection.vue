<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ProjectRole, RoleSubjectType, type RoleAssignmentDto } from '@vance/generated';
import {
  VAlert,
  VButton,
  VCard,
  VGroup,
  VGroupRow,
  VIconButton,
  VInput,
  VSelect,
  useConfirm,
} from '@/components';
import { PROJECT_ROLES, useProjectMembers } from '@/composables/useRoles';

/**
 * Members of a project — who works in it, and with which project role. A team
 * as member gives the role to every member of the team; that is also how a
 * project is opened to everybody (a team "all"). Visible to the project's
 * admins; for everybody else the brain answers 403 and the section stays
 * hidden.
 */
const props = defineProps<{
  projectName: string;
}>();

const { t } = useI18n();
const confirmAction = useConfirm();
const state = useProjectMembers();

const subjectType = ref<RoleSubjectType>(RoleSubjectType.USER);
const subjectId = ref('');
const role = ref<ProjectRole>(ProjectRole.MEMBER);
const notice = ref<string | null>(null);

const subjectTypeOptions = computed(() =>
  [RoleSubjectType.USER, RoleSubjectType.TEAM].map((value) => ({
    value,
    label: t(`projectMembers.subject.${value}`),
  })));
const roleOptions = computed(() =>
  PROJECT_ROLES.map((value) => ({ value, label: t(`projectMembers.roles.${value}`) })));

watch(
  () => props.projectName,
  (project) => {
    notice.value = null;
    if (project) void state.load(project);
  },
  { immediate: true },
);

async function add(): Promise<void> {
  notice.value = null;
  const name = subjectId.value.trim();
  if (!name) return;
  try {
    await state.setMember(props.projectName, subjectType.value, name, role.value);
    notice.value = t('projectMembers.added', {
      name,
      role: t(`projectMembers.roles.${role.value}`),
    });
    subjectId.value = '';
  } catch {
    /* error in state.error */
  }
}

async function changeRole(member: RoleAssignmentDto, next: ProjectRole): Promise<void> {
  notice.value = null;
  try {
    await state.setMember(props.projectName, member.subjectType, member.subjectId, next);
  } catch {
    /* error in state.error */
  }
}

async function remove(member: RoleAssignmentDto): Promise<void> {
  notice.value = null;
  const ok = await confirmAction({
    message: t('projectMembers.confirmRemove', { name: member.subjectId }),
    confirmLabel: t('projectMembers.remove'),
    danger: true,
  });
  if (!ok) return;
  try {
    await state.removeMember(props.projectName, member.subjectType, member.subjectId);
  } catch {
    /* error in state.error */
  }
}
</script>

<template>
  <VCard v-if="!state.forbidden.value" :title="t('projectMembers.title')">
    <div class="flex flex-col gap-3">
      <p class="text-sm text-(--v-label-secondary)">{{ t('projectMembers.intro') }}</p>

      <VAlert v-if="state.error.value" variant="error">
        <span>{{ state.error.value }}</span>
      </VAlert>
      <VAlert v-else-if="notice" variant="success">
        <span>{{ notice }}</span>
      </VAlert>

      <div v-if="state.loading.value" class="text-sm text-(--v-label-secondary)">
        {{ t('projectMembers.loading') }}
      </div>
      <p v-else-if="state.members.value.length === 0" class="text-sm text-(--v-label-secondary)">
        {{ t('projectMembers.empty') }}
      </p>
      <VGroup v-else>
        <VGroupRow
          v-for="m in state.members.value"
          :key="m.subjectType + ':' + m.subjectId"
          :label="m.subjectId"
          :meta="t(`projectMembers.subject.${m.subjectType}`)"
        >
          <div class="w-36">
            <VSelect
              :model-value="m.projectRole ?? null"
              :options="roleOptions"
              :disabled="state.busy.value"
              @update:model-value="(v) => v && changeRole(m, v as ProjectRole)"
            />
          </div>
          <VIconButton
            icon="delete"
            :label="t('projectMembers.remove')"
            :disabled="state.busy.value"
            @click="remove(m)"
          />
        </VGroupRow>
      </VGroup>

      <div class="border-t border-(--v-separator) pt-3 mt-2 flex flex-wrap items-end gap-3">
        <div class="w-32">
          <VSelect v-model="subjectType" :options="subjectTypeOptions" :label="t('projectMembers.subjectType')" />
        </div>
        <div class="flex-1 min-w-48">
          <VInput v-model="subjectId" :label="t('projectMembers.subjectId')" />
        </div>
        <div class="w-36">
          <VSelect v-model="role" :options="roleOptions" :label="t('projectMembers.role')" />
        </div>
        <VButton
          variant="secondary"
          icon="add"
          :disabled="!subjectId.trim()"
          :loading="state.busy.value"
          @click="add"
        >{{ t('projectMembers.add') }}</VButton>
      </div>
    </div>
  </VCard>
</template>
