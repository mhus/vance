<script setup lang="ts">
// Reference page of the @vance/components building blocks — every primitive
// in its states, light and dark (corporate-design.md §10.2, web-ui.md §7.4).
// Kept current when a primitive is added or changed; a quick look here after a
// theme or DaisyUI update is the smoke test.
import { ref } from 'vue';
import {
  ICONS,
  VAlert,
  VBadge,
  VButton,
  VCheckbox,
  VEmptyState,
  VGroup,
  VGroupRow,
  VIcon,
  VIconButton,
  VInput,
  VModal,
  VSelect,
  VTextarea,
  VToggle,
  VToolbar,
  type IconName,
} from '@vance/components';

const dark = ref(document.documentElement.dataset.theme === 'dark');

function setTheme(isDark: boolean): void {
  // Local to this tab — the persistent choice lives in the profile.
  dark.value = isDark;
  document.documentElement.dataset.theme = isDark ? 'dark' : 'light';
  document.documentElement.classList.toggle('dark', isDark);
}

const iconNames = Object.keys(ICONS) as IconName[];

const name = ref('Wedding planning');
const model = ref('gpt-9');
const kind = ref<string | null>('kanban');
const notes = ref('');
const agree = ref(true);
const compact = ref(false);
const notify = ref(true);
const pinned = ref(true);
const dialogOpen = ref(false);
</script>

<template>
  <div class="preview min-h-full">
    <div class="mx-auto flex max-w-5xl flex-col gap-8 px-6 py-10">
      <header class="flex flex-wrap items-center justify-between gap-4">
        <div class="flex flex-col gap-1">
          <h1 class="preview-title">{{ $t('preview.title') }}</h1>
          <p class="preview-muted max-w-2xl">{{ $t('preview.intro') }}</p>
        </div>
        <div class="flex gap-2">
          <VButton :variant="dark ? 'secondary' : 'primary'" icon="light" @click="setTheme(false)">
            {{ $t('preview.themeLight') }}
          </VButton>
          <VButton :variant="dark ? 'primary' : 'secondary'" icon="dark" @click="setTheme(true)">
            {{ $t('preview.themeDark') }}
          </VButton>
        </div>
      </header>

      <div class="grid gap-6 md:grid-cols-2">
        <section class="preview-panel">
          <h2 class="preview-label">{{ $t('preview.buttons') }}</h2>
          <div class="flex flex-wrap items-center gap-2">
            <VButton variant="primary">{{ $t('preview.primary') }}</VButton>
            <VButton variant="secondary">{{ $t('preview.secondary') }}</VButton>
            <VButton variant="plain">{{ $t('preview.plain') }}</VButton>
            <VButton variant="danger">{{ $t('preview.danger') }}</VButton>
            <VButton variant="danger" filled>{{ $t('preview.dangerFilled') }}</VButton>
          </div>
          <h2 class="preview-label">{{ $t('preview.sizes') }}</h2>
          <div class="flex flex-wrap items-center gap-2">
            <VButton variant="primary" icon="add">{{ $t('preview.withIcon') }}</VButton>
            <VButton variant="secondary" size="sm">{{ $t('preview.secondary') }}</VButton>
            <VButton variant="secondary" size="xs">{{ $t('preview.secondary') }}</VButton>
            <VButton variant="primary" loading>{{ $t('preview.loading') }}</VButton>
            <VButton variant="secondary" disabled>{{ $t('preview.disabled') }}</VButton>
          </div>
        </section>

        <section class="preview-panel">
          <h2 class="preview-label">{{ $t('preview.iconButtons') }}</h2>
          <div class="flex flex-wrap items-center gap-1 pb-6">
            <VIconButton icon="refresh" :label="$t('preview.refresh')" />
            <VIconButton icon="download" :label="$t('preview.download')" />
            <VIconButton icon="share" :label="$t('preview.share')" />
            <VIconButton
              icon="pin"
              :label="$t('preview.pinned')"
              :active="pinned"
              @click="pinned = !pinned"
            />
            <VIconButton icon="delete" :label="$t('preview.delete')" danger />
            <VIconButton icon="close" :label="$t('preview.close')" size="sm" />
            <VIconButton icon="more" :label="$t('preview.more')" disabled />
          </div>
        </section>
      </div>

      <section class="preview-panel preview-flush">
        <h2 class="preview-label px-6 pt-6">{{ $t('preview.toolbar') }}</h2>
        <VToolbar>
          <VIconButton icon="back" :label="$t('preview.back')" />
          <span class="font-semibold">{{ $t('preview.documentTitle') }}</span>
          <template #end>
            <VIconButton icon="chat" :label="$t('preview.openInCortex')" />
            <VIconButton icon="download" :label="$t('preview.download')" />
            <VIconButton icon="share" :label="$t('preview.share')" />
            <VIconButton icon="more" :label="$t('preview.more')" />
            <VButton variant="primary" icon="add">{{ $t('preview.withIcon') }}</VButton>
          </template>
        </VToolbar>
        <div class="h-6" />
      </section>

      <div class="grid gap-6 md:grid-cols-2">
        <section class="preview-ground">
          <h2 class="preview-label">{{ $t('preview.groups') }}</h2>
          <VGroup :label="$t('preview.groupAppearance')" :note="$t('preview.groupNote')">
            <VGroupRow
              icon="language"
              :label="$t('preview.rowLanguage')"
              :value="$t('preview.rowLanguageValue')"
              navigates
            />
            <VGroupRow icon="settings" :label="$t('preview.rowCompact')">
              <VToggle v-model="compact" />
            </VGroupRow>
            <VGroupRow icon="notification" :label="$t('preview.rowNotify')">
              <VToggle v-model="notify" />
            </VGroupRow>
          </VGroup>
          <VGroup>
            <VGroupRow :label="$t('preview.rowSignOut')" danger navigates />
          </VGroup>
        </section>

        <section class="preview-panel">
          <h2 class="preview-label">{{ $t('preview.fields') }}</h2>
          <VInput v-model="name" :label="$t('preview.fieldName')" :help="$t('preview.fieldNameHelp')" />
          <VInput v-model="model" :label="$t('preview.fieldModel')" :error="$t('preview.fieldModelError')" />
          <VSelect
            v-model="kind"
            :label="$t('preview.fieldKind')"
            :options="[
              { value: 'kanban', label: 'Kanban' },
              { value: 'workbook', label: 'Workbook' },
            ]"
          />
          <VTextarea v-model="notes" :label="$t('preview.fieldNotes')" :rows="2" />
          <VCheckbox v-model="agree" :label="$t('preview.fieldAgree')" />
        </section>
      </div>

      <div class="grid gap-6 md:grid-cols-2">
        <section class="preview-panel">
          <h2 class="preview-label">{{ $t('preview.status') }}</h2>
          <VAlert variant="info">{{ $t('preview.alertInfo') }}</VAlert>
          <VAlert variant="warning">{{ $t('preview.alertWarning') }}</VAlert>
          <VAlert variant="error">{{ $t('preview.alertError') }}</VAlert>
          <VAlert variant="success">{{ $t('preview.alertSuccess') }}</VAlert>
          <div class="flex flex-wrap gap-2">
            <VBadge>{{ $t('preview.badge') }}</VBadge>
            <VBadge variant="primary">{{ $t('preview.badge') }}</VBadge>
            <VBadge variant="success">{{ $t('preview.badge') }}</VBadge>
            <VBadge variant="warning">{{ $t('preview.badge') }}</VBadge>
            <VBadge variant="error">{{ $t('preview.badge') }}</VBadge>
          </div>
        </section>

        <section class="preview-panel">
          <h2 class="preview-label">{{ $t('preview.empty') }}</h2>
          <VEmptyState :headline="$t('preview.emptyHeadline')" :body="$t('preview.emptyBody')" />
          <h2 class="preview-label">{{ $t('preview.dialog') }}</h2>
          <div>
            <VButton variant="danger" @click="dialogOpen = true">{{ $t('preview.dialogOpen') }}</VButton>
          </div>
        </section>
      </div>

      <section class="preview-panel">
        <h2 class="preview-label">{{ $t('preview.icons') }}</h2>
        <div class="preview-icons">
          <div v-for="n in iconNames" :key="n" class="flex items-center gap-2">
            <VIcon :name="n" class="preview-icon" />
            <span class="preview-mono">{{ n }}</span>
          </div>
        </div>
      </section>
    </div>

    <VModal v-model="dialogOpen" :title="$t('preview.dialogTitle')">
      <p class="text-sm">{{ $t('preview.dialogBody') }}</p>
      <template #footer>
        <VButton variant="secondary" @click="dialogOpen = false">{{ $t('preview.secondary') }}</VButton>
        <VButton variant="danger" filled @click="dialogOpen = false">{{ $t('preview.dangerFilled') }}</VButton>
      </template>
    </VModal>
  </div>
</template>

<style scoped>
.preview { background: var(--v-bg); color: var(--v-label); }
.preview-title { margin: 0; font-size: 22px; line-height: 28px; font-weight: 600; letter-spacing: -0.01em; }
.preview-muted { margin: 0; font-size: 13px; color: var(--v-label-secondary); }
.preview-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px;
  border-radius: var(--v-radius-group);
  background: var(--v-surface);
}
.preview-flush { padding: 0; gap: 12px; overflow: hidden; }
.preview-ground { display: flex; flex-direction: column; gap: 22px; }
.preview-label {
  margin: 0;
  font-size: 11px;
  line-height: 14px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--v-label-secondary);
}
.preview-icons { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px 16px; }
.preview-icon { color: var(--v-label-secondary); }
.preview-mono { font-family: var(--v-font-mono); font-size: 12px; color: var(--v-label); }
</style>
