import { computed, onMounted, reactive, ref, watch } from 'vue';
import { CodeEditor, EditorShell, MarkdownView, VAlert, VButton, VCard, VCheckbox, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useAdminSkills } from '@/composables/useAdminSkills';
import { useHelp } from '@/composables/useHelp';
import { getUsername } from '@vance/shared';
import { SkillReferenceDocLoadMode, SkillScope, SkillTriggerType, } from '@vance/generated';
const tenantProjects = useTenantProjects();
const skillsState = useAdminSkills();
const help = useHelp();
const currentUsername = getUsername() ?? '';
const scope = ref({ kind: 'tenant' });
const selectedName = ref(null);
const banner = ref(null);
const formError = ref(null);
const form = reactive({
    title: '',
    description: '',
    version: '1.0.0',
    enabled: true,
    triggers: [],
    promptExtension: '',
    toolsText: '',
    tagsText: '',
    refDocs: [],
});
// ─── New-skill modal ────────────────────────────────────────────────────
const showNewModal = ref(false);
const newName = ref('');
const newTitle = ref('');
const newDescription = ref('');
const newError = ref(null);
const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;
// ─── Derived state ──────────────────────────────────────────────────────
const projectId = computed(() => scope.value.kind === 'project' ? scope.value.projectId : null);
const userId = computed(() => scope.value.kind === 'user' ? currentUsername : null);
const currentScopeEnum = computed(() => {
    if (scope.value.kind === 'project')
        return SkillScope.PROJECT;
    if (scope.value.kind === 'user')
        return SkillScope.USER;
    return SkillScope.TENANT;
});
const scopeOptions = computed(() => {
    const list = [
        { value: 't:', label: 'Tenant' },
    ];
    for (const p of tenantProjects.projects.value) {
        list.push({
            value: 'p:' + p.name,
            label: 'Project: ' + (p.title || p.name),
            group: 'Projects',
        });
    }
    if (currentUsername) {
        list.push({
            value: 'u:' + currentUsername,
            label: 'User: ' + currentUsername + ' (you)',
            group: 'Users',
        });
    }
    return list;
});
const scopeValue = computed(() => {
    if (scope.value.kind === 'project')
        return 'p:' + scope.value.projectId;
    if (scope.value.kind === 'user')
        return 'u:' + currentUsername;
    return 't:';
});
function onScopeChange(v) {
    const s = v == null ? '' : String(v);
    if (s.startsWith('p:')) {
        scope.value = { kind: 'project', projectId: s.slice(2) };
    }
    else if (s.startsWith('u:')) {
        scope.value = { kind: 'user' };
    }
    else {
        scope.value = { kind: 'tenant' };
    }
}
const selectedSkill = computed(() => selectedName.value
    ? skillsState.skills.value.find(s => s.name === selectedName.value) ?? null
    : null);
const isOwnedAtCurrentScope = computed(() => selectedSkill.value?.scope === currentScopeEnum.value);
const isReadOnly = computed(() => selectedSkill.value?.scope === SkillScope.BUNDLED);
const breadcrumbs = computed(() => {
    const scopeLabel = scope.value.kind === 'project' ? `Project: ${scope.value.projectId}` :
        scope.value.kind === 'user' ? `User: ${currentUsername}` :
            'Tenant';
    return selectedName.value ? [scopeLabel, selectedName.value] : [scopeLabel];
});
const triggerTypeOptions = [
    { value: SkillTriggerType.PATTERN, label: 'PATTERN (regex)' },
    { value: SkillTriggerType.KEYWORDS, label: 'KEYWORDS (≥50% match)' },
];
const loadModeOptions = [
    { value: SkillReferenceDocLoadMode.INLINE, label: 'INLINE' },
    { value: SkillReferenceDocLoadMode.ON_DEMAND, label: 'ON_DEMAND' },
];
// ─── Lifecycle ──────────────────────────────────────────────────────────
onMounted(async () => {
    await Promise.all([
        tenantProjects.reload(),
        skillsState.loadEffective(null, null),
        help.load('skill-field-docs.md'),
    ]);
});
watch(scope, async (s) => {
    selectedName.value = null;
    resetForm();
    await skillsState.loadEffective(s.kind === 'project' ? s.projectId : null, s.kind === 'user' ? currentUsername : null);
}, { deep: true });
watch(selectedName, () => {
    banner.value = null;
    formError.value = null;
    if (!selectedSkill.value) {
        resetForm();
        return;
    }
    populateForm(selectedSkill.value);
});
// ─── Form helpers ───────────────────────────────────────────────────────
function resetForm() {
    form.title = '';
    form.description = '';
    form.version = '1.0.0';
    form.enabled = true;
    form.triggers = [];
    form.promptExtension = '';
    form.toolsText = '';
    form.tagsText = '';
    form.refDocs = [];
}
function populateForm(s) {
    form.title = s.title;
    form.description = s.description;
    form.version = s.version;
    form.enabled = s.enabled;
    form.triggers = (s.triggers ?? []).map(t => ({
        type: t.type,
        pattern: t.pattern ?? '',
        keywordsText: (t.keywords ?? []).join('\n'),
    }));
    form.promptExtension = s.promptExtension ?? '';
    form.toolsText = (s.tools ?? []).join('\n');
    form.tagsText = (s.tags ?? []).join('\n');
    form.refDocs = (s.referenceDocs ?? []).map(d => ({
        title: d.title,
        content: d.content,
        loadMode: d.loadMode,
    }));
}
function buildWriteRequest() {
    const title = form.title.trim();
    const description = form.description.trim();
    const version = form.version.trim();
    if (!title) {
        formError.value = 'Title is required.';
        return null;
    }
    if (!description) {
        formError.value = 'Description is required.';
        return null;
    }
    if (!version) {
        formError.value = 'Version is required.';
        return null;
    }
    const triggers = form.triggers.map(t => ({
        type: t.type,
        pattern: t.type === SkillTriggerType.PATTERN
            ? (t.pattern.trim() || undefined)
            : undefined,
        keywords: t.type === SkillTriggerType.KEYWORDS
            ? splitLines(t.keywordsText)
            : [],
    }));
    for (const t of triggers) {
        if (t.type === SkillTriggerType.PATTERN && !t.pattern) {
            formError.value = 'PATTERN trigger needs a regex.';
            return null;
        }
        if (t.type === SkillTriggerType.KEYWORDS && (!t.keywords || t.keywords.length === 0)) {
            formError.value = 'KEYWORDS trigger needs at least one keyword.';
            return null;
        }
    }
    const refDocs = form.refDocs.map(d => ({
        title: d.title.trim(),
        content: d.content,
        loadMode: d.loadMode,
    }));
    for (const d of refDocs) {
        if (!d.title) {
            formError.value = 'Reference doc title is required.';
            return null;
        }
        if (!d.content) {
            formError.value = 'Reference doc content is required.';
            return null;
        }
    }
    return {
        title,
        description,
        version,
        triggers,
        promptExtension: form.promptExtension.trim() || undefined,
        tools: splitLines(form.toolsText),
        referenceDocs: refDocs,
        tags: splitLines(form.tagsText),
        enabled: form.enabled,
    };
}
function splitLines(s) {
    return s
        .split(/[\n,]/)
        .map(x => x.trim())
        .filter(x => x.length > 0);
}
// ─── Trigger / RefDoc list mutators ─────────────────────────────────────
function addTrigger() {
    form.triggers.push({
        type: SkillTriggerType.PATTERN,
        pattern: '',
        keywordsText: '',
    });
}
function removeTrigger(idx) {
    form.triggers.splice(idx, 1);
}
function addRefDoc() {
    form.refDocs.push({
        title: '',
        content: '',
        loadMode: SkillReferenceDocLoadMode.INLINE,
    });
}
function removeRefDoc(idx) {
    form.refDocs.splice(idx, 1);
}
// ─── Save / Override / Delete ───────────────────────────────────────────
async function save() {
    if (!selectedName.value)
        return;
    formError.value = null;
    banner.value = null;
    const req = buildWriteRequest();
    if (!req)
        return;
    try {
        await skillsState.upsert(projectId.value, userId.value, selectedName.value, req);
        banner.value = isOwnedAtCurrentScope.value
            ? 'Saved.'
            : `Override created at ${scope.value.kind} scope.`;
    }
    catch {
        /* error in skillsState.error */
    }
}
async function deleteOverride() {
    if (!selectedName.value || !isOwnedAtCurrentScope.value)
        return;
    if (!confirm(`Remove the ${scope.value.kind}-scope override of "${selectedName.value}"?`))
        return;
    try {
        await skillsState.remove(projectId.value, userId.value, selectedName.value);
        banner.value = 'Override removed — skill falls back to inherited copy.';
    }
    catch {
        /* error */
    }
}
// ─── New-skill modal ────────────────────────────────────────────────────
function openNewSkill() {
    newName.value = '';
    newTitle.value = '';
    newDescription.value = '';
    newError.value = null;
    showNewModal.value = true;
}
async function submitNewSkill() {
    newError.value = null;
    const name = newName.value.trim();
    const title = newTitle.value.trim();
    const description = newDescription.value.trim();
    if (!name) {
        newError.value = 'Name is required.';
        return;
    }
    if (!NAME_PATTERN.test(name)) {
        newError.value = 'Name must be lower-case alphanumerics with optional "-" or "_".';
        return;
    }
    if (skillsState.skills.value.some(s => s.name === name)) {
        newError.value = `A skill named "${name}" is already visible at this scope. Pick a different name (or open the existing one to override it).`;
        return;
    }
    if (!title) {
        newError.value = 'Title is required.';
        return;
    }
    if (!description) {
        newError.value = 'Description is required.';
        return;
    }
    const stub = {
        title,
        description,
        version: '1.0.0',
        triggers: [],
        tools: [],
        referenceDocs: [],
        tags: [],
        enabled: true,
    };
    try {
        await skillsState.upsert(projectId.value, userId.value, name, stub);
        showNewModal.value = false;
        selectedName.value = name;
        banner.value = `Created skill "${name}" at ${scope.value.kind} scope.`;
    }
    catch (e) {
        newError.value = e instanceof Error ? e.message : 'Failed to create skill.';
    }
}
// ─── Sidebar selection ──────────────────────────────────────────────────
function selectSkill(name) {
    selectedName.value = name;
}
function scopeBadgeClass(s) {
    if (s === SkillScope.BUNDLED)
        return 'badge-bundled';
    if (s === SkillScope.TENANT)
        return 'badge-tenant';
    if (s === SkillScope.PROJECT)
        return 'badge-project';
    if (s === SkillScope.USER)
        return 'badge-user';
    return '';
}
function scopeLabel(s) {
    if (s === SkillScope.BUNDLED)
        return 'bundled';
    if (s === SkillScope.TENANT)
        return 'tenant';
    if (s === SkillScope.PROJECT)
        return 'project';
    if (s === SkillScope.USER)
        return 'user';
    return '';
}
const combinedError = computed(() => skillsState.error.value || tenantProjects.error.value);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['skill-item']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: "Skills",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: "Skills",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
{
    const { 'topbar-extra': __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_5 = {}.VSelect;
    /** @type {[typeof __VLS_components.VSelect, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.scopeValue),
        options: (__VLS_ctx.scopeOptions),
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.scopeValue),
        options: (__VLS_ctx.scopeOptions),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_9;
    let __VLS_10;
    let __VLS_11;
    const __VLS_12 = {
        'onUpdate:modelValue': (__VLS_ctx.onScopeChange)
    };
    var __VLS_8;
}
{
    const { sidebar: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
        ...{ class: "flex flex-col gap-1 p-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between px-2 mb-1" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs uppercase opacity-50" },
    });
    (__VLS_ctx.scope.kind === 'project' ? `Project: ${__VLS_ctx.scope.projectId}` :
        __VLS_ctx.scope.kind === 'user' ? `User: ${__VLS_ctx.currentUsername}` :
            'Tenant scope');
    const __VLS_13 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_15 = __VLS_14({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
    let __VLS_17;
    let __VLS_18;
    let __VLS_19;
    const __VLS_20 = {
        onClick: (__VLS_ctx.openNewSkill)
    };
    __VLS_16.slots.default;
    var __VLS_16;
    if (__VLS_ctx.skillsState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
    }
    else if (__VLS_ctx.skillsState.skills.value.length === 0) {
        const __VLS_21 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            headline: "No skills",
            body: "Click + New to create one.",
        }));
        const __VLS_23 = __VLS_22({
            headline: "No skills",
            body: "Click + New to create one.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    }
    for (const [s] of __VLS_getVForSourceType((__VLS_ctx.skillsState.skills.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectSkill(s.name);
                } },
            key: (s.name),
            ...{ class: "skill-item" },
            ...{ class: ({ 'skill-item--active': __VLS_ctx.selectedName === s.name }) },
            type: "button",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm truncate" },
        });
        (s.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-1.5 py-0.5 rounded" },
            ...{ class: (__VLS_ctx.scopeBadgeClass(s.scope)) },
        });
        (__VLS_ctx.scopeLabel(s.scope));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 truncate" },
        });
        (s.title);
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-3 max-w-4xl" },
});
if (__VLS_ctx.combinedError) {
    const __VLS_25 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
        variant: "error",
    }));
    const __VLS_27 = __VLS_26({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_26));
    __VLS_28.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.combinedError);
    var __VLS_28;
}
if (__VLS_ctx.banner) {
    const __VLS_29 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_30 = __VLS_asFunctionalComponent(__VLS_29, new __VLS_29({
        variant: "success",
    }));
    const __VLS_31 = __VLS_30({
        variant: "success",
    }, ...__VLS_functionalComponentArgsRest(__VLS_30));
    __VLS_32.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.banner);
    var __VLS_32;
}
if (__VLS_ctx.formError) {
    const __VLS_33 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
        variant: "error",
    }));
    const __VLS_35 = __VLS_34({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_34));
    __VLS_36.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.formError);
    var __VLS_36;
}
if (!__VLS_ctx.selectedSkill) {
    const __VLS_37 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
        headline: "Select a skill",
        body: "Pick one from the list, or click + New to create one at the current scope.",
    }));
    const __VLS_39 = __VLS_38({
        headline: "Select a skill",
        body: "Pick one from the list, or click + New to create one at the current scope.",
    }, ...__VLS_functionalComponentArgsRest(__VLS_38));
}
else {
    const __VLS_41 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({}));
    const __VLS_43 = __VLS_42({}, ...__VLS_functionalComponentArgsRest(__VLS_42));
    __VLS_44.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between gap-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "font-mono text-lg" },
    });
    (__VLS_ctx.selectedSkill.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.scope.kind);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.scopeLabel(__VLS_ctx.selectedSkill.scope));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex gap-2" },
    });
    if (__VLS_ctx.isOwnedAtCurrentScope) {
        const __VLS_45 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_46 = __VLS_asFunctionalComponent(__VLS_45, new __VLS_45({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.skillsState.busy.value),
        }));
        const __VLS_47 = __VLS_46({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.skillsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_46));
        let __VLS_49;
        let __VLS_50;
        let __VLS_51;
        const __VLS_52 = {
            onClick: (__VLS_ctx.deleteOverride)
        };
        __VLS_48.slots.default;
        var __VLS_48;
    }
    if (!__VLS_ctx.isReadOnly) {
        const __VLS_53 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.skillsState.busy.value),
        }));
        const __VLS_55 = __VLS_54({
            ...{ 'onClick': {} },
            variant: "primary",
            loading: (__VLS_ctx.skillsState.busy.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_54));
        let __VLS_57;
        let __VLS_58;
        let __VLS_59;
        const __VLS_60 = {
            onClick: (__VLS_ctx.save)
        };
        __VLS_56.slots.default;
        (__VLS_ctx.isOwnedAtCurrentScope ? 'Save' : 'Override here');
        var __VLS_56;
    }
    if (!__VLS_ctx.isOwnedAtCurrentScope && !__VLS_ctx.isReadOnly) {
        const __VLS_61 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
            variant: "info",
            ...{ class: "mt-3" },
        }));
        const __VLS_63 = __VLS_62({
            variant: "info",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_62));
        __VLS_64.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        (__VLS_ctx.scopeLabel(__VLS_ctx.selectedSkill.scope));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
        (__VLS_ctx.scope.kind);
        var __VLS_64;
    }
    if (__VLS_ctx.isReadOnly) {
        const __VLS_65 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
            variant: "info",
            ...{ class: "mt-3" },
        }));
        const __VLS_67 = __VLS_66({
            variant: "info",
            ...{ class: "mt-3" },
        }, ...__VLS_functionalComponentArgsRest(__VLS_66));
        __VLS_68.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        var __VLS_68;
    }
    var __VLS_44;
    const __VLS_69 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({
        title: "Identity",
    }));
    const __VLS_71 = __VLS_70({
        title: "Identity",
    }, ...__VLS_functionalComponentArgsRest(__VLS_70));
    __VLS_72.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    const __VLS_73 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
        modelValue: (__VLS_ctx.form.title),
        label: "Title",
        required: true,
    }));
    const __VLS_75 = __VLS_74({
        modelValue: (__VLS_ctx.form.title),
        label: "Title",
        required: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_74));
    const __VLS_77 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
        modelValue: (__VLS_ctx.form.description),
        label: "Description",
        required: true,
    }));
    const __VLS_79 = __VLS_78({
        modelValue: (__VLS_ctx.form.description),
        label: "Description",
        required: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_78));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "grid grid-cols-2 gap-3" },
    });
    const __VLS_81 = {}.VInput;
    /** @type {[typeof __VLS_components.VInput, ]} */ ;
    // @ts-ignore
    const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
        modelValue: (__VLS_ctx.form.version),
        label: "Version",
        required: true,
    }));
    const __VLS_83 = __VLS_82({
        modelValue: (__VLS_ctx.form.version),
        label: "Version",
        required: true,
    }, ...__VLS_functionalComponentArgsRest(__VLS_82));
    const __VLS_85 = {}.VCheckbox;
    /** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
    // @ts-ignore
    const __VLS_86 = __VLS_asFunctionalComponent(__VLS_85, new __VLS_85({
        modelValue: (__VLS_ctx.form.enabled),
        label: "Enabled",
    }));
    const __VLS_87 = __VLS_86({
        modelValue: (__VLS_ctx.form.enabled),
        label: "Enabled",
    }, ...__VLS_functionalComponentArgsRest(__VLS_86));
    var __VLS_72;
    const __VLS_89 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
        title: "Triggers",
    }));
    const __VLS_91 = __VLS_90({
        title: "Triggers",
    }, ...__VLS_functionalComponentArgsRest(__VLS_90));
    __VLS_92.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    for (const [t, idx] of __VLS_getVForSourceType((__VLS_ctx.form.triggers))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: ('trig-' + idx),
            ...{ class: "trigger-row" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2 mb-2" },
        });
        const __VLS_93 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
            modelValue: (t.type),
            options: (__VLS_ctx.triggerTypeOptions),
            label: "Type",
        }));
        const __VLS_95 = __VLS_94({
            modelValue: (t.type),
            options: (__VLS_ctx.triggerTypeOptions),
            label: "Type",
        }, ...__VLS_functionalComponentArgsRest(__VLS_94));
        const __VLS_97 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }));
        const __VLS_99 = __VLS_98({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }, ...__VLS_functionalComponentArgsRest(__VLS_98));
        let __VLS_101;
        let __VLS_102;
        let __VLS_103;
        const __VLS_104 = {
            onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.selectedSkill))
                    return;
                __VLS_ctx.removeTrigger(idx);
            }
        };
        __VLS_100.slots.default;
        var __VLS_100;
        if (t.type === __VLS_ctx.SkillTriggerType.PATTERN) {
            const __VLS_105 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
                modelValue: (t.pattern),
                label: "Pattern (regex)",
                placeholder: "e.g. schau.*(diff|PR)",
            }));
            const __VLS_107 = __VLS_106({
                modelValue: (t.pattern),
                label: "Pattern (regex)",
                placeholder: "e.g. schau.*(diff|PR)",
            }, ...__VLS_functionalComponentArgsRest(__VLS_106));
        }
        else {
            const __VLS_109 = {}.VTextarea;
            /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
            // @ts-ignore
            const __VLS_110 = __VLS_asFunctionalComponent(__VLS_109, new __VLS_109({
                modelValue: (t.keywordsText),
                label: "Keywords",
                help: "One per line (or comma-separated). Trigger fires when ≥50% of keywords appear.",
                rows: (3),
            }));
            const __VLS_111 = __VLS_110({
                modelValue: (t.keywordsText),
                label: "Keywords",
                help: "One per line (or comma-separated). Trigger fires when ≥50% of keywords appear.",
                rows: (3),
            }, ...__VLS_functionalComponentArgsRest(__VLS_110));
        }
    }
    const __VLS_113 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_115 = __VLS_114({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_114));
    let __VLS_117;
    let __VLS_118;
    let __VLS_119;
    const __VLS_120 = {
        onClick: (__VLS_ctx.addTrigger)
    };
    __VLS_116.slots.default;
    var __VLS_116;
    var __VLS_92;
    const __VLS_121 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
        title: "Prompt extension",
    }));
    const __VLS_123 = __VLS_122({
        title: "Prompt extension",
    }, ...__VLS_functionalComponentArgsRest(__VLS_122));
    __VLS_124.slots.default;
    const __VLS_125 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_126 = __VLS_asFunctionalComponent(__VLS_125, new __VLS_125({
        modelValue: (__VLS_ctx.form.promptExtension),
        mimeType: "text/markdown",
        rows: (14),
    }));
    const __VLS_127 = __VLS_126({
        modelValue: (__VLS_ctx.form.promptExtension),
        mimeType: "text/markdown",
        rows: (14),
    }, ...__VLS_functionalComponentArgsRest(__VLS_126));
    var __VLS_124;
    const __VLS_129 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_130 = __VLS_asFunctionalComponent(__VLS_129, new __VLS_129({
        title: "Tools & tags",
    }));
    const __VLS_131 = __VLS_130({
        title: "Tools & tags",
    }, ...__VLS_functionalComponentArgsRest(__VLS_130));
    __VLS_132.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "grid grid-cols-2 gap-4" },
    });
    const __VLS_133 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_134 = __VLS_asFunctionalComponent(__VLS_133, new __VLS_133({
        modelValue: (__VLS_ctx.form.toolsText),
        label: "Tools",
        help: "One per line. Added to the engine/recipe allow-list.",
        rows: (4),
    }));
    const __VLS_135 = __VLS_134({
        modelValue: (__VLS_ctx.form.toolsText),
        label: "Tools",
        help: "One per line. Added to the engine/recipe allow-list.",
        rows: (4),
    }, ...__VLS_functionalComponentArgsRest(__VLS_134));
    const __VLS_137 = {}.VTextarea;
    /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
    // @ts-ignore
    const __VLS_138 = __VLS_asFunctionalComponent(__VLS_137, new __VLS_137({
        modelValue: (__VLS_ctx.form.tagsText),
        label: "Tags",
        help: "One per line.",
        rows: (4),
    }));
    const __VLS_139 = __VLS_138({
        modelValue: (__VLS_ctx.form.tagsText),
        label: "Tags",
        help: "One per line.",
        rows: (4),
    }, ...__VLS_functionalComponentArgsRest(__VLS_138));
    var __VLS_132;
    const __VLS_141 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
        title: "Reference docs",
    }));
    const __VLS_143 = __VLS_142({
        title: "Reference docs",
    }, ...__VLS_functionalComponentArgsRest(__VLS_142));
    __VLS_144.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3" },
    });
    for (const [d, idx] of __VLS_getVForSourceType((__VLS_ctx.form.refDocs))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: ('doc-' + idx),
            ...{ class: "refdoc-row" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "grid grid-cols-3 gap-2 mb-2" },
        });
        const __VLS_145 = {}.VInput;
        /** @type {[typeof __VLS_components.VInput, ]} */ ;
        // @ts-ignore
        const __VLS_146 = __VLS_asFunctionalComponent(__VLS_145, new __VLS_145({
            modelValue: (d.title),
            label: "Title",
            required: true,
        }));
        const __VLS_147 = __VLS_146({
            modelValue: (d.title),
            label: "Title",
            required: true,
        }, ...__VLS_functionalComponentArgsRest(__VLS_146));
        const __VLS_149 = {}.VSelect;
        /** @type {[typeof __VLS_components.VSelect, ]} */ ;
        // @ts-ignore
        const __VLS_150 = __VLS_asFunctionalComponent(__VLS_149, new __VLS_149({
            modelValue: (d.loadMode),
            options: (__VLS_ctx.loadModeOptions),
            label: "Load mode",
        }));
        const __VLS_151 = __VLS_150({
            modelValue: (d.loadMode),
            options: (__VLS_ctx.loadModeOptions),
            label: "Load mode",
        }, ...__VLS_functionalComponentArgsRest(__VLS_150));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-end justify-end" },
        });
        const __VLS_153 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_154 = __VLS_asFunctionalComponent(__VLS_153, new __VLS_153({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }));
        const __VLS_155 = __VLS_154({
            ...{ 'onClick': {} },
            variant: "ghost",
            size: "sm",
        }, ...__VLS_functionalComponentArgsRest(__VLS_154));
        let __VLS_157;
        let __VLS_158;
        let __VLS_159;
        const __VLS_160 = {
            onClick: (...[$event]) => {
                if (!!(!__VLS_ctx.selectedSkill))
                    return;
                __VLS_ctx.removeRefDoc(idx);
            }
        };
        __VLS_156.slots.default;
        var __VLS_156;
        const __VLS_161 = {}.CodeEditor;
        /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
        // @ts-ignore
        const __VLS_162 = __VLS_asFunctionalComponent(__VLS_161, new __VLS_161({
            modelValue: (d.content),
            mimeType: "text/markdown",
            rows: (10),
        }));
        const __VLS_163 = __VLS_162({
            modelValue: (d.content),
            mimeType: "text/markdown",
            rows: (10),
        }, ...__VLS_functionalComponentArgsRest(__VLS_162));
    }
    const __VLS_165 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_166 = __VLS_asFunctionalComponent(__VLS_165, new __VLS_165({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }));
    const __VLS_167 = __VLS_166({
        ...{ 'onClick': {} },
        variant: "ghost",
        size: "sm",
    }, ...__VLS_functionalComponentArgsRest(__VLS_166));
    let __VLS_169;
    let __VLS_170;
    let __VLS_171;
    const __VLS_172 = {
        onClick: (__VLS_ctx.addRefDoc)
    };
    __VLS_168.slots.default;
    var __VLS_168;
    var __VLS_144;
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 flex flex-col gap-4" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
    __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
        ...{ class: "text-xs uppercase opacity-60 mb-2" },
    });
    if (__VLS_ctx.help.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
    }
    else if (__VLS_ctx.help.error.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.help.error.value);
    }
    else if (!__VLS_ctx.help.content.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60" },
        });
    }
    else {
        const __VLS_173 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_174 = __VLS_asFunctionalComponent(__VLS_173, new __VLS_173({
            source: (__VLS_ctx.help.content.value),
        }));
        const __VLS_175 = __VLS_174({
            source: (__VLS_ctx.help.content.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_174));
    }
}
const __VLS_177 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_178 = __VLS_asFunctionalComponent(__VLS_177, new __VLS_177({
    modelValue: (__VLS_ctx.showNewModal),
    title: "New skill",
}));
const __VLS_179 = __VLS_178({
    modelValue: (__VLS_ctx.showNewModal),
    title: "New skill",
}, ...__VLS_functionalComponentArgsRest(__VLS_178));
__VLS_180.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newError) {
    const __VLS_181 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_182 = __VLS_asFunctionalComponent(__VLS_181, new __VLS_181({
        variant: "error",
    }));
    const __VLS_183 = __VLS_182({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_182));
    __VLS_184.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newError);
    var __VLS_184;
}
const __VLS_185 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_186 = __VLS_asFunctionalComponent(__VLS_185, new __VLS_185({
    modelValue: (__VLS_ctx.newName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed. Must not collide with an existing skill at this scope.",
}));
const __VLS_187 = __VLS_186({
    modelValue: (__VLS_ctx.newName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed. Must not collide with an existing skill at this scope.",
}, ...__VLS_functionalComponentArgsRest(__VLS_186));
const __VLS_189 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_190 = __VLS_asFunctionalComponent(__VLS_189, new __VLS_189({
    modelValue: (__VLS_ctx.newTitle),
    label: "Title",
    required: true,
}));
const __VLS_191 = __VLS_190({
    modelValue: (__VLS_ctx.newTitle),
    label: "Title",
    required: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_190));
const __VLS_193 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_194 = __VLS_asFunctionalComponent(__VLS_193, new __VLS_193({
    modelValue: (__VLS_ctx.newDescription),
    label: "Description",
    required: true,
}));
const __VLS_195 = __VLS_194({
    modelValue: (__VLS_ctx.newDescription),
    label: "Description",
    required: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_194));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs opacity-70" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
(__VLS_ctx.scope.kind);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_197 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_198 = __VLS_asFunctionalComponent(__VLS_197, new __VLS_197({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_199 = __VLS_198({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_198));
let __VLS_201;
let __VLS_202;
let __VLS_203;
const __VLS_204 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showNewModal = false;
    }
};
__VLS_200.slots.default;
var __VLS_200;
const __VLS_205 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_206 = __VLS_asFunctionalComponent(__VLS_205, new __VLS_205({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.skillsState.busy.value),
}));
const __VLS_207 = __VLS_206({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.skillsState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_206));
let __VLS_209;
let __VLS_210;
let __VLS_211;
const __VLS_212 = {
    onClick: (__VLS_ctx.submitNewSkill)
};
__VLS_208.slots.default;
var __VLS_208;
var __VLS_180;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['skill-item']} */ ;
/** @type {__VLS_StyleScopedClasses['skill-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['p-6']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['max-w-4xl']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-lg']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['trigger-row']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-2']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['refdoc-row']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-end']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['p-4']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-end']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            CodeEditor: CodeEditor,
            EditorShell: EditorShell,
            MarkdownView: MarkdownView,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            VTextarea: VTextarea,
            SkillTriggerType: SkillTriggerType,
            skillsState: skillsState,
            help: help,
            currentUsername: currentUsername,
            scope: scope,
            selectedName: selectedName,
            banner: banner,
            formError: formError,
            form: form,
            showNewModal: showNewModal,
            newName: newName,
            newTitle: newTitle,
            newDescription: newDescription,
            newError: newError,
            scopeOptions: scopeOptions,
            scopeValue: scopeValue,
            onScopeChange: onScopeChange,
            selectedSkill: selectedSkill,
            isOwnedAtCurrentScope: isOwnedAtCurrentScope,
            isReadOnly: isReadOnly,
            breadcrumbs: breadcrumbs,
            triggerTypeOptions: triggerTypeOptions,
            loadModeOptions: loadModeOptions,
            addTrigger: addTrigger,
            removeTrigger: removeTrigger,
            addRefDoc: addRefDoc,
            removeRefDoc: removeRefDoc,
            save: save,
            deleteOverride: deleteOverride,
            openNewSkill: openNewSkill,
            submitNewSkill: submitNewSkill,
            selectSkill: selectSkill,
            scopeBadgeClass: scopeBadgeClass,
            scopeLabel: scopeLabel,
            combinedError: combinedError,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SkillsApp.vue.js.map