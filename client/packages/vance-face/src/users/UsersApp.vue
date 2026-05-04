<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  EditorShell,
  MarkdownView,
  VAlert,
  VButton,
  VCard,
  VCheckbox,
  VEmptyState,
  VInput,
  VModal,
  VSelect,
  VTextarea,
} from '@/components';
import { useAdminUsers } from '@/composables/useAdminUsers';
import { useAdminTeams } from '@/composables/useAdminTeams';
import { useScopeSettings } from '@/composables/useScopeSettings';
import { useHelp } from '@/composables/useHelp';
import { getUsername } from '@vance/shared';
import {
  SettingType,
  type SettingDto,
  type TeamDto,
  type UserDto,
} from '@vance/generated';

const { t } = useI18n();
const usersState = useAdminUsers();
const teamsState = useAdminTeams();
const settingsState = useScopeSettings();
const help = useHelp();
const currentUsername = getUsername() ?? '';

// SettingType labels are the wire-enum values themselves — they're
// recognisable across UI languages, no translation needed.
const settingTypeOptions = [
  { value: SettingType.STRING, label: 'STRING' },
  { value: SettingType.INT, label: 'INT' },
  { value: SettingType.LONG, label: 'LONG' },
  { value: SettingType.DOUBLE, label: 'DOUBLE' },
  { value: SettingType.BOOLEAN, label: 'BOOLEAN' },
  { value: SettingType.PASSWORD, label: 'PASSWORD' },
];
const editingKey = ref<string | null>(null);
const editValue = ref('');
const editDescription = ref('');
const newSettingKey = ref('');
const newSettingValue = ref('');
const newSettingType = ref<SettingType>(SettingType.STRING);
const newSettingDescription = ref('');

type Selection =
  | { kind: 'user'; name: string }
  | { kind: 'team'; name: string };

const selection = ref<Selection | null>(null);
const banner = ref<string | null>(null);
const formError = ref<string | null>(null);

// ─── User form state ────────────────────────────────────────────────────
const userForm = reactive({
  title: '',
  email: '',
  status: 'ACTIVE',
});
const userStatusOptions = [
  { value: 'ACTIVE', label: 'ACTIVE' },
  { value: 'DISABLED', label: 'DISABLED' },
  { value: 'PENDING', label: 'PENDING' },
];

// ─── Team form state ────────────────────────────────────────────────────
const teamForm = reactive({
  title: '',
  enabled: true,
  membersText: '',
});

// ─── Modals ─────────────────────────────────────────────────────────────
const showCreateUser = ref(false);
const newUserName = ref('');
const newUserTitle = ref('');
const newUserEmail = ref('');
const newUserPassword = ref('');
const newUserError = ref<string | null>(null);

const showCreateTeam = ref(false);
const newTeamName = ref('');
const newTeamTitle = ref('');
const newTeamMembersText = ref('');
const newTeamError = ref<string | null>(null);

const showSetPassword = ref(false);
const passwordPlaintext = ref('');
const passwordPlaintextRepeat = ref('');
const passwordError = ref<string | null>(null);

const NAME_PATTERN_USER = /^[a-z0-9][a-z0-9_.-]*$/;
const NAME_PATTERN_TEAM = /^[a-z0-9][a-z0-9_-]*$/;

// ─── Derived ────────────────────────────────────────────────────────────

const selectedUser = computed<UserDto | null>(() => {
  const sel = selection.value;
  if (sel?.kind !== 'user') return null;
  return usersState.users.value.find(u => u.name === sel.name) ?? null;
});

const selectedTeam = computed<TeamDto | null>(() => {
  const sel = selection.value;
  if (sel?.kind !== 'team') return null;
  return teamsState.teams.value.find(t => t.name === sel.name) ?? null;
});

const isOwnAccount = computed(() =>
  selectedUser.value?.name === currentUsername);

const breadcrumbs = computed<string[]>(() => {
  const sel = selection.value;
  if (!sel) return [];
  return [
    sel.kind === 'user'
      ? t('users.breadcrumbs.userPrefix', { name: sel.name })
      : t('users.breadcrumbs.teamPrefix', { name: sel.name }),
  ];
});

const combinedError = computed<string | null>(() =>
  usersState.error.value || teamsState.error.value || settingsState.error.value);

// ─── Lifecycle ──────────────────────────────────────────────────────────

onMounted(async () => {
  await Promise.all([
    usersState.reload(),
    teamsState.reload(),
    help.load('user-team-admin.md'),
  ]);
});

watch(selection, async (sel) => {
  banner.value = null;
  formError.value = null;
  resetSettingEditor();
  populateForm();
  if (sel?.kind === 'user') {
    await settingsState.load('user', sel.name);
  } else {
    settingsState.clear();
  }
});

function resetSettingEditor(): void {
  editingKey.value = null;
  editValue.value = '';
  editDescription.value = '';
  newSettingKey.value = '';
  newSettingValue.value = '';
  newSettingType.value = SettingType.STRING;
  newSettingDescription.value = '';
}

async function addUserSetting(): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  const key = newSettingKey.value.trim();
  if (!key) return;
  try {
    await settingsState.upsert(
      'user',
      selection.value.name,
      key,
      newSettingValue.value === '' ? null : newSettingValue.value,
      newSettingType.value,
      newSettingDescription.value.trim() || null,
    );
    resetSettingEditor();
  } catch {
    /* error in settingsState.error */
  }
}

function startEditUserSetting(s: SettingDto): void {
  editingKey.value = s.key;
  editValue.value = s.type === SettingType.PASSWORD ? '' : (s.value ?? '');
  editDescription.value = s.description ?? '';
}

function cancelEditUserSetting(): void {
  editingKey.value = null;
}

async function saveEditUserSetting(s: SettingDto): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  try {
    await settingsState.upsert(
      'user',
      selection.value.name,
      s.key,
      editValue.value === '' && s.type === SettingType.PASSWORD ? null : editValue.value,
      s.type,
      editDescription.value || null,
    );
    editingKey.value = null;
  } catch {
    /* error */
  }
}

async function deleteUserSetting(s: SettingDto): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  if (!confirm(t('users.user.settings.confirmDelete', { key: s.key }))) return;
  try {
    await settingsState.remove('user', selection.value.name, s.key);
  } catch {
    /* error */
  }
}

watch(() => selectedUser.value, () => {
  if (selection.value?.kind === 'user') populateForm();
});
watch(() => selectedTeam.value, () => {
  if (selection.value?.kind === 'team') populateForm();
});

function populateForm(): void {
  if (selectedUser.value) {
    userForm.title = selectedUser.value.title ?? '';
    userForm.email = selectedUser.value.email ?? '';
    userForm.status = selectedUser.value.status;
  } else if (selectedTeam.value) {
    teamForm.title = selectedTeam.value.title ?? '';
    teamForm.enabled = selectedTeam.value.enabled;
    teamForm.membersText = selectedTeam.value.members.join('\n');
  }
}

// ─── Selection ──────────────────────────────────────────────────────────

function selectUser(name: string): void {
  selection.value = { kind: 'user', name };
}
function selectTeam(name: string): void {
  selection.value = { kind: 'team', name };
}

function isSelectedUser(u: UserDto): boolean {
  return selection.value?.kind === 'user' && selection.value.name === u.name;
}
function isSelectedTeam(t: TeamDto): boolean {
  return selection.value?.kind === 'team' && selection.value.name === t.name;
}

// ─── Save / Delete ──────────────────────────────────────────────────────

async function saveUser(): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  formError.value = null;
  banner.value = null;
  if (userForm.status === 'DISABLED' && selection.value.name === currentUsername) {
    formError.value = t('users.user.cantDisableSelf');
    return;
  }
  try {
    await usersState.update(selection.value.name, {
      title: userForm.title,
      email: userForm.email,
      status: userForm.status,
    });
    banner.value = t('users.user.saved');
  } catch {
    /* error in usersState.error */
  }
}

async function deleteUser(): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  if (selection.value.name === currentUsername) {
    formError.value = t('users.user.cantDeleteSelf');
    return;
  }
  if (!confirm(t('users.user.confirmDelete', { name: selection.value.name }))) return;
  const name = selection.value.name;
  try {
    await usersState.remove(name);
    selection.value = null;
    banner.value = t('users.user.deleted', { name });
  } catch { /* state.error */ }
}

async function saveTeam(): Promise<void> {
  if (selection.value?.kind !== 'team') return;
  formError.value = null;
  banner.value = null;
  try {
    await teamsState.update(selection.value.name, {
      title: teamForm.title,
      enabled: teamForm.enabled,
      members: splitLines(teamForm.membersText),
    });
    banner.value = t('users.team.saved');
  } catch { /* state.error */ }
}

async function deleteTeam(): Promise<void> {
  if (selection.value?.kind !== 'team') return;
  if (!confirm(t('users.team.confirmDelete', { name: selection.value.name }))) return;
  const name = selection.value.name;
  try {
    await teamsState.remove(name);
    selection.value = null;
    banner.value = t('users.team.deleted', { name });
  } catch { /* state.error */ }
}

// ─── Create modals ──────────────────────────────────────────────────────

function openCreateUser(): void {
  newUserName.value = '';
  newUserTitle.value = '';
  newUserEmail.value = '';
  newUserPassword.value = '';
  newUserError.value = null;
  showCreateUser.value = true;
}

async function submitCreateUser(): Promise<void> {
  newUserError.value = null;
  const name = newUserName.value.trim();
  if (!name || !NAME_PATTERN_USER.test(name)) {
    newUserError.value = t('users.createUser.nameInvalid');
    return;
  }
  if (usersState.users.value.some(u => u.name === name)) {
    newUserError.value = t('users.createUser.alreadyExists', { name });
    return;
  }
  try {
    await usersState.create({
      name,
      title: newUserTitle.value.trim() || undefined,
      email: newUserEmail.value.trim() || undefined,
      password: newUserPassword.value || undefined,
    });
    showCreateUser.value = false;
    selectUser(name);
    banner.value = t('users.createUser.created', { name });
  } catch (e) {
    newUserError.value = e instanceof Error ? e.message : t('users.createUser.createFailed');
  }
}

function openCreateTeam(): void {
  newTeamName.value = '';
  newTeamTitle.value = '';
  newTeamMembersText.value = '';
  newTeamError.value = null;
  showCreateTeam.value = true;
}

async function submitCreateTeam(): Promise<void> {
  newTeamError.value = null;
  const name = newTeamName.value.trim();
  if (!name || !NAME_PATTERN_TEAM.test(name)) {
    newTeamError.value = t('users.createTeam.nameInvalid');
    return;
  }
  if (teamsState.teams.value.some(team => team.name === name)) {
    newTeamError.value = t('users.createTeam.alreadyExists', { name });
    return;
  }
  try {
    await teamsState.create({
      name,
      title: newTeamTitle.value.trim() || undefined,
      members: splitLines(newTeamMembersText.value),
    });
    showCreateTeam.value = false;
    selectTeam(name);
    banner.value = t('users.createTeam.created', { name });
  } catch (e) {
    newTeamError.value = e instanceof Error ? e.message : t('users.createTeam.createFailed');
  }
}

// ─── Set password ───────────────────────────────────────────────────────

function openSetPassword(): void {
  passwordPlaintext.value = '';
  passwordPlaintextRepeat.value = '';
  passwordError.value = null;
  showSetPassword.value = true;
}

async function submitSetPassword(): Promise<void> {
  if (selection.value?.kind !== 'user') return;
  passwordError.value = null;
  const pw = passwordPlaintext.value;
  if (!pw) {
    passwordError.value = t('users.setPassword.required');
    return;
  }
  if (pw !== passwordPlaintextRepeat.value) {
    passwordError.value = t('users.setPassword.mismatch');
    return;
  }
  try {
    await usersState.setPassword(selection.value.name, pw);
    showSetPassword.value = false;
    banner.value = t('users.setPassword.updated', { name: selection.value.name });
  } catch (e) {
    passwordError.value = e instanceof Error ? e.message : t('users.setPassword.failed');
  }
}

// ─── Helpers ────────────────────────────────────────────────────────────

function splitLines(s: string): string[] {
  return s
    .split(/[\n,]/)
    .map(x => x.trim())
    .filter(x => x.length > 0);
}

function fmt(value: unknown): string {
  if (value == null) return '—';
  if (value instanceof Date) return value.toISOString();
  return String(value);
}
</script>

<template>
  <EditorShell :title="$t('users.pageTitle')" :breadcrumbs="breadcrumbs" wide-right-panel>
    <!-- ─── Sidebar ─── -->
    <template #sidebar>
      <nav class="flex flex-col gap-3 p-2">
        <section>
          <div class="flex items-center justify-between px-2 mb-1">
            <span class="text-xs uppercase opacity-50">{{ $t('users.sidebar.usersTitle') }}</span>
            <VButton variant="ghost" size="sm" @click="openCreateUser">
              {{ $t('users.sidebar.addUser') }}
            </VButton>
          </div>
          <div v-if="usersState.loading.value" class="px-2 text-xs opacity-60">
            {{ $t('users.loading') }}
          </div>
          <button
            v-for="u in usersState.users.value"
            :key="'u-' + u.name"
            type="button"
            class="row-item"
            :class="{ 'row-item--active': isSelectedUser(u) }"
            @click="selectUser(u.name)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-mono text-sm truncate">{{ u.name }}</span>
              <span
                class="text-xs px-1.5 py-0.5 rounded"
                :class="{
                  'badge-active': u.status === 'ACTIVE',
                  'badge-disabled': u.status === 'DISABLED',
                  'badge-pending': u.status === 'PENDING',
                }"
              >{{ u.status?.toLowerCase() }}</span>
            </div>
            <div v-if="u.title || u.email" class="text-xs opacity-60 truncate">
              {{ u.title }}<span v-if="u.title && u.email"> · </span>{{ u.email }}
            </div>
          </button>
        </section>

        <section>
          <div class="flex items-center justify-between px-2 mb-1">
            <span class="text-xs uppercase opacity-50">{{ $t('users.sidebar.teamsTitle') }}</span>
            <VButton variant="ghost" size="sm" @click="openCreateTeam">
              {{ $t('users.sidebar.addTeam') }}
            </VButton>
          </div>
          <div v-if="teamsState.loading.value" class="px-2 text-xs opacity-60">
            {{ $t('users.loading') }}
          </div>
          <button
            v-for="team in teamsState.teams.value"
            :key="'t-' + team.name"
            type="button"
            class="row-item"
            :class="{ 'row-item--active': isSelectedTeam(team) }"
            @click="selectTeam(team.name)"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="font-mono text-sm truncate">{{ team.name }}</span>
              <span class="text-xs opacity-60">
                {{
                  team.members.length === 1
                    ? $t('users.sidebar.memberCountSingular', { count: team.members.length })
                    : $t('users.sidebar.memberCountPlural', { count: team.members.length })
                }}
              </span>
            </div>
            <div class="text-xs opacity-60 truncate">
              {{ team.title }}
              <span v-if="!team.enabled">{{ ' ' + $t('users.sidebar.disabledSuffix') }}</span>
            </div>
          </button>
        </section>
      </nav>
    </template>

    <!-- ─── Main ─── -->
    <div class="p-6 flex flex-col gap-3 max-w-3xl">
      <VAlert v-if="combinedError" variant="error">
        <span>{{ combinedError }}</span>
      </VAlert>
      <VAlert v-if="banner" variant="success">
        <span>{{ banner }}</span>
      </VAlert>
      <VAlert v-if="formError" variant="error">
        <span>{{ formError }}</span>
      </VAlert>

      <VEmptyState
        v-if="!selection"
        :headline="$t('users.empty.headline')"
        :body="$t('users.empty.body')"
      />

      <!-- ─── User detail ─── -->
      <template v-else-if="selection.kind === 'user'">
        <div v-if="!selectedUser" class="opacity-70">{{ $t('users.loading') }}</div>
        <template v-else>
          <VCard :title="$t('users.user.cardTitle', { name: selectedUser.name })">
            <VAlert v-if="isOwnAccount" variant="info" class="mb-3">
              <span>{{ $t('users.user.ownAccountNote') }}</span>
            </VAlert>
            <div class="flex flex-col gap-3">
              <VInput
                :model-value="selectedUser.name"
                :label="$t('users.user.nameLabel')"
                disabled
                :help="$t('users.user.nameImmutable')"
                @update:model-value="() => {}"
              />
              <VInput v-model="userForm.title" :label="$t('users.user.titleLabel')" />
              <VInput v-model="userForm.email" :label="$t('users.user.emailLabel')" type="email" />
              <VSelect
                v-model="userForm.status"
                :options="userStatusOptions"
                :label="$t('users.user.statusLabel')"
              />
              <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80">
                <dt class="opacity-60">{{ $t('users.user.createdLabel') }}</dt>
                <dd>{{ fmt(selectedUser.createdAt) }}</dd>
              </dl>
              <div class="flex justify-between">
                <div class="flex gap-2">
                  <VButton
                    variant="danger"
                    :disabled="isOwnAccount"
                    :loading="usersState.busy.value"
                    @click="deleteUser"
                  >{{ $t('users.user.delete') }}</VButton>
                  <VButton variant="ghost" @click="openSetPassword">
                    {{ $t('users.user.setPassword') }}
                  </VButton>
                </div>
                <VButton variant="primary" :loading="usersState.busy.value" @click="saveUser">
                  {{ $t('users.user.save') }}
                </VButton>
              </div>
            </div>
          </VCard>

          <!-- ─── User settings ─── -->
          <VCard :title="$t('users.user.settings.cardTitle')">
            <p class="text-xs opacity-70 mb-3">
              {{ $t('users.user.settings.intro', { name: selectedUser.name }) }}
            </p>

            <VEmptyState
              v-if="!settingsState.loading.value && settingsState.settings.value.length === 0"
              :headline="$t('users.user.settings.noSettingsHeadline')"
              :body="$t('users.user.settings.noSettingsBody')"
            />

            <ul class="flex flex-col divide-y divide-base-300">
              <li
                v-for="s in settingsState.settings.value"
                :key="s.key"
                class="setting-row"
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="font-mono text-sm truncate">{{ s.key }}</span>
                  <span class="opacity-60 text-xs">{{ s.type }}</span>
                </div>
                <template v-if="editingKey === s.key">
                  <VInput
                    v-if="s.type !== SettingType.PASSWORD"
                    v-model="editValue"
                    :label="$t('users.user.settings.valueLabel')"
                  />
                  <VInput
                    v-else
                    v-model="editValue"
                    type="password"
                    :label="$t('users.user.settings.newPasswordLabel')"
                    :placeholder="$t('users.user.settings.passwordEmptyToClear')"
                  />
                  <VTextarea
                    v-model="editDescription"
                    :label="$t('users.user.settings.descriptionLabel')"
                    :rows="2"
                  />
                  <div class="flex justify-end gap-2 mt-1">
                    <VButton variant="ghost" size="sm" @click="cancelEditUserSetting">
                      {{ $t('users.user.settings.cancel') }}
                    </VButton>
                    <VButton
                      variant="primary"
                      size="sm"
                      :loading="settingsState.busy.value"
                      @click="saveEditUserSetting(s)"
                    >{{ $t('users.user.settings.save') }}</VButton>
                  </div>
                </template>
                <template v-else>
                  <div class="text-sm break-words">
                    <span class="opacity-70">{{ s.value ?? $t('users.user.settings.empty') }}</span>
                  </div>
                  <div v-if="s.description" class="text-xs opacity-60">{{ s.description }}</div>
                  <div class="flex justify-end gap-2 mt-1">
                    <VButton variant="ghost" size="sm" @click="startEditUserSetting(s)">
                      {{ $t('users.user.settings.edit') }}
                    </VButton>
                    <VButton variant="ghost" size="sm" @click="deleteUserSetting(s)">
                      {{ $t('users.user.settings.delete') }}
                    </VButton>
                  </div>
                </template>
              </li>
            </ul>

            <div class="border-t border-base-300 pt-3 mt-2 flex flex-col gap-2">
              <h4 class="text-xs uppercase opacity-60">
                {{ $t('users.user.settings.addTitle') }}
              </h4>
              <VInput
                v-model="newSettingKey"
                :label="$t('users.user.settings.keyLabel')"
                :placeholder="$t('users.user.settings.keyPlaceholder')"
              />
              <VSelect
                v-model="newSettingType"
                :label="$t('users.user.settings.typeLabel')"
                :options="settingTypeOptions"
              />
              <VInput
                v-if="newSettingType !== SettingType.PASSWORD"
                v-model="newSettingValue"
                :label="$t('users.user.settings.valueLabel')"
              />
              <VInput
                v-else
                v-model="newSettingValue"
                type="password"
                :label="$t('users.user.settings.passwordLabel')"
              />
              <VTextarea
                v-model="newSettingDescription"
                :label="$t('users.user.settings.descriptionOptional')"
                :rows="2"
              />
              <VButton
                variant="primary"
                size="sm"
                :disabled="!newSettingKey.trim()"
                :loading="settingsState.busy.value"
                @click="addUserSetting"
              >{{ $t('users.user.settings.add') }}</VButton>
            </div>
          </VCard>
        </template>
      </template>

      <!-- ─── Team detail ─── -->
      <template v-else-if="selection.kind === 'team'">
        <div v-if="!selectedTeam" class="opacity-70">{{ $t('users.loading') }}</div>
        <template v-else>
          <VCard :title="$t('users.team.cardTitle', { name: selectedTeam.name })">
            <div class="flex flex-col gap-3">
              <VInput
                :model-value="selectedTeam.name"
                :label="$t('users.team.nameLabel')"
                disabled
                :help="$t('users.team.nameImmutable')"
                @update:model-value="() => {}"
              />
              <VInput v-model="teamForm.title" :label="$t('users.team.titleLabel')" />
              <VCheckbox v-model="teamForm.enabled" :label="$t('users.team.enabledLabel')" />
              <VTextarea
                v-model="teamForm.membersText"
                :label="$t('users.team.membersLabel')"
                :placeholder="$t('users.team.membersPlaceholder')"
                :rows="6"
                :help="splitLines(teamForm.membersText).length === 1
                  ? $t('users.team.memberHelpSingular', { count: splitLines(teamForm.membersText).length })
                  : $t('users.team.memberHelpPlural', { count: splitLines(teamForm.membersText).length })"
              />
              <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-sm opacity-80">
                <dt class="opacity-60">{{ $t('users.team.createdLabel') }}</dt>
                <dd>{{ fmt(selectedTeam.createdAt) }}</dd>
              </dl>
              <div class="flex justify-between">
                <VButton
                  variant="danger"
                  :loading="teamsState.busy.value"
                  @click="deleteTeam"
                >{{ $t('users.team.delete') }}</VButton>
                <VButton variant="primary" :loading="teamsState.busy.value" @click="saveTeam">
                  {{ $t('users.team.save') }}
                </VButton>
              </div>
            </div>
          </VCard>
        </template>
      </template>
    </div>

    <!-- ─── Right panel: help ─── -->
    <template #right-panel>
      <div class="p-4 flex flex-col gap-4">
        <h3 class="text-xs uppercase opacity-60 mb-2">{{ $t('users.helpPanel.title') }}</h3>
        <div v-if="help.loading.value" class="text-xs opacity-60">
          {{ $t('users.helpPanel.loading') }}
        </div>
        <div v-else-if="help.error.value" class="text-xs opacity-60">
          {{ $t('users.helpPanel.unavailable', { error: help.error.value }) }}
        </div>
        <div v-else-if="!help.content.value" class="text-xs opacity-60">
          {{ $t('users.helpPanel.empty') }}
        </div>
        <MarkdownView v-else :source="help.content.value" />
      </div>
    </template>

    <!-- ─── Create-user modal ─── -->
    <VModal v-model="showCreateUser" :title="$t('users.createUser.title')">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newUserError" variant="error">
          <span>{{ newUserError }}</span>
        </VAlert>
        <VInput
          v-model="newUserName"
          :label="$t('users.createUser.nameLabel')"
          required
          :help="$t('users.createUser.nameHelp')"
        />
        <VInput v-model="newUserTitle" :label="$t('users.createUser.titleLabel')" />
        <VInput v-model="newUserEmail" :label="$t('users.createUser.emailLabel')" type="email" />
        <VInput
          v-model="newUserPassword"
          :label="$t('users.createUser.passwordLabel')"
          type="password"
          :help="$t('users.createUser.passwordHelp')"
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateUser = false">
            {{ $t('users.createUser.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :loading="usersState.busy.value"
            @click="submitCreateUser"
          >{{ $t('users.createUser.create') }}</VButton>
        </div>
      </div>
    </VModal>

    <!-- ─── Create-team modal ─── -->
    <VModal v-model="showCreateTeam" :title="$t('users.createTeam.title')">
      <div class="flex flex-col gap-3">
        <VAlert v-if="newTeamError" variant="error">
          <span>{{ newTeamError }}</span>
        </VAlert>
        <VInput
          v-model="newTeamName"
          :label="$t('users.createTeam.nameLabel')"
          required
          :help="$t('users.createTeam.nameHelp')"
        />
        <VInput v-model="newTeamTitle" :label="$t('users.createTeam.titleLabel')" />
        <VTextarea
          v-model="newTeamMembersText"
          :label="$t('users.createTeam.membersLabel')"
          :placeholder="$t('users.createTeam.membersPlaceholder')"
          :rows="4"
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showCreateTeam = false">
            {{ $t('users.createTeam.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :loading="teamsState.busy.value"
            @click="submitCreateTeam"
          >{{ $t('users.createTeam.create') }}</VButton>
        </div>
      </div>
    </VModal>

    <!-- ─── Set-password modal ─── -->
    <VModal v-model="showSetPassword" :title="$t('users.setPassword.title')">
      <div class="flex flex-col gap-3">
        <VAlert v-if="passwordError" variant="error">
          <span>{{ passwordError }}</span>
        </VAlert>
        <p class="text-sm opacity-80">
          {{ $t('users.setPassword.intro', {
            name: selection?.kind === 'user' ? selection.name : '',
          }) }}
        </p>
        <VInput
          v-model="passwordPlaintext"
          :label="$t('users.setPassword.newPasswordLabel')"
          type="password"
          required
          autocomplete="new-password"
        />
        <VInput
          v-model="passwordPlaintextRepeat"
          :label="$t('users.setPassword.repeatPasswordLabel')"
          type="password"
          required
          autocomplete="new-password"
        />
        <div class="flex justify-end gap-2">
          <VButton variant="ghost" @click="showSetPassword = false">
            {{ $t('users.setPassword.cancel') }}
          </VButton>
          <VButton
            variant="primary"
            :loading="usersState.busy.value"
            @click="submitSetPassword"
          >{{ $t('users.setPassword.submit') }}</VButton>
        </div>
      </div>
    </VModal>
  </EditorShell>
</template>

<style scoped>
.row-item {
  display: block;
  text-align: left;
  padding: 0.4rem 0.6rem;
  border-radius: 0.375rem;
  background: transparent;
  cursor: pointer;
  width: 100%;
  border: 1px solid transparent;
  margin-bottom: 0.15rem;
}
.row-item:hover { background: hsl(var(--bc) / 0.06); }
.row-item--active {
  background: hsl(var(--p) / 0.12);
  border-color: hsl(var(--p) / 0.3);
}

.badge-active   { background: hsl(var(--su) / 0.18); color: hsl(var(--suc)); }
.badge-disabled { background: hsl(var(--bc) / 0.1);  color: hsl(var(--bc) / 0.6); }
.badge-pending  { background: hsl(var(--wa) / 0.18); color: hsl(var(--wac)); }
</style>
