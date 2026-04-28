import { computed, onMounted, ref, watch } from 'vue';
import yaml from 'js-yaml';
import { CodeEditor, EditorShell, MarkdownView, VAlert, VButton, VCard, VEmptyState, VInput, VModal, VSelect, } from '@/components';
import { useTenantProjects } from '@/composables/useTenantProjects';
import { useAdminRecipes } from '@/composables/useAdminRecipes';
import { useHelp } from '@/composables/useHelp';
import { PromptMode, RecipeSource, } from '@vance/generated';
const tenantProjects = useTenantProjects();
const recipesState = useAdminRecipes();
const help = useHelp();
const SNIPPETS = [
    { key: 'params', label: 'params', defaultValue: { model: 'default:fast' } },
    { key: 'allowedToolsAdd', label: 'allowedToolsAdd', defaultValue: [] },
    { key: 'allowedToolsRemove', label: 'allowedToolsRemove', defaultValue: [] },
    { key: 'tags', label: 'tags', defaultValue: [] },
    { key: 'promptPrefix', label: 'promptPrefix', defaultValue: 'Your prompt prefix here.\n' },
    { key: 'promptPrefixSmall', label: 'promptPrefixSmall', defaultValue: 'Variant for small models.\n' },
    { key: 'intentCorrection', label: 'intentCorrection', defaultValue: 'Correction message here.\n' },
    { key: 'dataRelayCorrection', label: 'dataRelayCorrection', defaultValue: 'Correction message here.\n' },
    { key: 'locked', label: 'locked', defaultValue: false },
];
/** Currently-selected scope. {@code null} = tenant, otherwise project name. */
const currentProject = ref(null);
/** Name of the recipe shown in the editor pane, or null when none. */
const selectedName = ref(null);
/** YAML buffer being edited. */
const yamlBuffer = ref('');
const banner = ref(null);
const yamlError = ref(null);
// ─── New-recipe modal ───
const showNewModal = ref(false);
const newName = ref('');
const newEngine = ref('ford');
const newDescription = ref('');
const newError = ref(null);
const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;
const projectOptions = computed(() => [
    { value: '', label: '(Tenant)' },
    ...tenantProjects.projects.value.map(p => ({
        value: p.name,
        label: p.title || p.name,
        group: p.projectGroupId ?? 'Ungrouped',
    })),
]);
/** Selected scope's owning side, used to decide Save target + Delete eligibility. */
const currentSourceForScope = computed(() => currentProject.value ? RecipeSource.PROJECT : RecipeSource.TENANT);
const selectedRecipe = computed(() => {
    if (!selectedName.value)
        return null;
    return recipesState.recipes.value.find(r => r.name === selectedName.value) ?? null;
});
const isOwnedAtCurrentScope = computed(() => selectedRecipe.value?.source === currentSourceForScope.value);
function sourceLabelFor(s) {
    if (s === RecipeSource.BUNDLED)
        return 'bundled';
    if (s === RecipeSource.TENANT)
        return 'tenant';
    if (s === RecipeSource.PROJECT)
        return 'project';
    return '';
}
const sourceLabel = computed(() => sourceLabelFor(selectedRecipe.value?.source));
const breadcrumbs = computed(() => {
    const scope = currentProject.value ? `Project: ${currentProject.value}` : 'Tenant';
    return selectedName.value ? [scope, selectedName.value] : [scope];
});
onMounted(async () => {
    await Promise.all([
        tenantProjects.reload(),
        recipesState.loadEffective(null),
        help.load('recipe-field-docs.md'),
    ]);
});
watch(currentProject, async (nv) => {
    selectedName.value = null;
    yamlBuffer.value = '';
    await recipesState.loadEffective(nv);
});
watch(selectedName, () => {
    banner.value = null;
    yamlError.value = null;
    if (!selectedRecipe.value) {
        yamlBuffer.value = '';
        return;
    }
    yamlBuffer.value = serialize(selectedRecipe.value);
});
function selectRecipe(name) {
    selectedName.value = name;
}
function openNewRecipe() {
    newName.value = '';
    newEngine.value = 'ford';
    newDescription.value = '';
    newError.value = null;
    showNewModal.value = true;
}
async function submitNewRecipe() {
    newError.value = null;
    const name = newName.value.trim();
    const engine = newEngine.value.trim();
    const description = newDescription.value.trim();
    if (!name) {
        newError.value = 'Name is required.';
        return;
    }
    if (!NAME_PATTERN.test(name)) {
        newError.value = 'Name must be lower-case alphanumerics with optional "-" or "_".';
        return;
    }
    if (recipesState.recipes.value.some(r => r.name === name)) {
        newError.value = `A recipe named "${name}" already exists in the cascade. Pick a different name (or open the existing one to override it).`;
        return;
    }
    if (!engine) {
        newError.value = 'Engine is required.';
        return;
    }
    if (!description) {
        newError.value = 'Description is required.';
        return;
    }
    const stub = {
        description,
        engine,
        params: {},
        promptMode: PromptMode.APPEND,
        locked: false,
        tags: [],
        allowedToolsAdd: [],
        allowedToolsRemove: [],
    };
    try {
        await recipesState.upsert(currentProject.value, name, stub);
        showNewModal.value = false;
        selectedName.value = name;
        banner.value = `Created recipe "${name}" at ${currentProject.value ? 'project' : 'tenant'} scope.`;
    }
    catch (e) {
        newError.value = e instanceof Error ? e.message : 'Failed to create recipe.';
    }
}
function serialize(r) {
    // Build the YAML body. {@code name} stays out — it's the path key,
    // edited via "rename" elsewhere if ever. {@code source} is metadata
    // for the UI, not for the writer.
    const body = {
        description: r.description,
        engine: r.engine,
        promptMode: r.promptMode,
        locked: r.locked,
        tags: r.tags ?? [],
        params: r.params ?? {},
        allowedToolsAdd: r.allowedToolsAdd ?? [],
        allowedToolsRemove: r.allowedToolsRemove ?? [],
    };
    if (r.intentCorrection)
        body.intentCorrection = r.intentCorrection;
    if (r.dataRelayCorrection)
        body.dataRelayCorrection = r.dataRelayCorrection;
    if (r.promptPrefix)
        body.promptPrefix = r.promptPrefix;
    if (r.promptPrefixSmall)
        body.promptPrefixSmall = r.promptPrefixSmall;
    return yaml.dump(body, {
        lineWidth: 100,
        noRefs: true,
        sortKeys: false,
    });
}
function parse() {
    yamlError.value = null;
    let parsed;
    try {
        parsed = yaml.load(yamlBuffer.value);
    }
    catch (e) {
        yamlError.value = e instanceof Error ? e.message : 'YAML parse error.';
        return null;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        yamlError.value = 'YAML root must be a map.';
        return null;
    }
    const obj = parsed;
    const description = stringOrEmpty(obj.description);
    const engine = stringOrEmpty(obj.engine);
    if (!description) {
        yamlError.value = '`description` is required.';
        return null;
    }
    if (!engine) {
        yamlError.value = '`engine` is required.';
        return null;
    }
    const promptMode = parsePromptMode(obj.promptMode);
    if (!promptMode) {
        yamlError.value = '`promptMode` must be APPEND or OVERWRITE.';
        return null;
    }
    return {
        description,
        engine,
        params: obj.params ?? {},
        promptPrefix: stringOrUndef(obj.promptPrefix),
        promptPrefixSmall: stringOrUndef(obj.promptPrefixSmall),
        promptMode,
        intentCorrection: stringOrUndef(obj.intentCorrection),
        dataRelayCorrection: stringOrUndef(obj.dataRelayCorrection),
        allowedToolsAdd: stringList(obj.allowedToolsAdd),
        allowedToolsRemove: stringList(obj.allowedToolsRemove),
        locked: obj.locked === true,
        tags: stringList(obj.tags),
    };
}
function stringOrEmpty(v) {
    return typeof v === 'string' ? v : '';
}
function stringOrUndef(v) {
    return typeof v === 'string' && v.length > 0 ? v : undefined;
}
function stringList(v) {
    if (!Array.isArray(v))
        return [];
    return v.filter((x) => typeof x === 'string');
}
function parsePromptMode(v) {
    if (v === PromptMode.APPEND || v === PromptMode.OVERWRITE)
        return v;
    if (typeof v === 'string') {
        const u = v.trim().toUpperCase();
        if (u === 'APPEND')
            return PromptMode.APPEND;
        if (u === 'OVERWRITE')
            return PromptMode.OVERWRITE;
    }
    return null;
}
async function saveOverride() {
    if (!selectedName.value)
        return;
    const req = parse();
    if (!req)
        return;
    banner.value = null;
    try {
        await recipesState.upsert(currentProject.value, selectedName.value, req);
        banner.value = isOwnedAtCurrentScope.value
            ? `Saved.`
            : `Override created at ${currentProject.value ? 'project' : 'tenant'} scope.`;
    }
    catch {
        /* error already in recipesState.error */
    }
}
/**
 * Inserts a snippet for {@code spec.key} into the current YAML buffer
 * if not already present. Idempotent: if the key already exists, the
 * call is a no-op and the button hides.
 */
function insertSnippet(spec) {
    yamlError.value = null;
    let parsed;
    try {
        const raw = yaml.load(yamlBuffer.value);
        parsed = (raw && typeof raw === 'object' && !Array.isArray(raw))
            ? raw
            : {};
    }
    catch (e) {
        yamlError.value = e instanceof Error ? e.message : 'YAML parse error.';
        return;
    }
    if (spec.key in parsed)
        return;
    parsed[spec.key] = structuredClone(spec.defaultValue);
    yamlBuffer.value = yaml.dump(parsed, { lineWidth: 100, noRefs: true, sortKeys: false });
}
/** Returns the keys currently present in the YAML buffer (best-effort). */
function presentKeys() {
    try {
        const raw = yaml.load(yamlBuffer.value);
        if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
            return new Set(Object.keys(raw));
        }
    }
    catch {
        /* parse error — show all snippets as available */
    }
    return new Set();
}
const availableSnippets = computed(() => {
    const present = presentKeys();
    return SNIPPETS.filter(s => !present.has(s.key));
});
async function deleteOverride() {
    if (!selectedName.value || !isOwnedAtCurrentScope.value)
        return;
    if (!confirm(`Remove the ${currentProject.value ? 'project' : 'tenant'}-scope override of "${selectedName.value}"?`))
        return;
    try {
        await recipesState.remove(currentProject.value, selectedName.value);
        banner.value = `Override removed — recipe falls back to inherited copy.`;
    }
    catch {
        /* error already in recipesState.error */
    }
}
const combinedError = computed(() => recipesState.error.value || tenantProjects.error.value);
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['recipe-item']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: "Recipes",
    breadcrumbs: (__VLS_ctx.breadcrumbs),
    wideRightPanel: true,
}));
const __VLS_2 = __VLS_1({
    title: "Recipes",
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
        modelValue: (__VLS_ctx.currentProject ?? ''),
        options: (__VLS_ctx.projectOptions),
    }));
    const __VLS_7 = __VLS_6({
        ...{ 'onUpdate:modelValue': {} },
        modelValue: (__VLS_ctx.currentProject ?? ''),
        options: (__VLS_ctx.projectOptions),
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    let __VLS_9;
    let __VLS_10;
    let __VLS_11;
    const __VLS_12 = {
        'onUpdate:modelValue': ((v) => __VLS_ctx.currentProject = (v ? String(v) : null))
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
    (__VLS_ctx.currentProject ? `Project: ${__VLS_ctx.currentProject}` : 'Tenant scope');
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
        onClick: (__VLS_ctx.openNewRecipe)
    };
    __VLS_16.slots.default;
    var __VLS_16;
    if (__VLS_ctx.recipesState.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "px-2 text-xs opacity-60" },
        });
    }
    else if (__VLS_ctx.recipesState.recipes.value.length === 0) {
        const __VLS_21 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            headline: "No recipes",
            body: "Bundled catalog is empty — check brain logs.",
        }));
        const __VLS_23 = __VLS_22({
            headline: "No recipes",
            body: "Bundled catalog is empty — check brain logs.",
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    }
    for (const [r] of __VLS_getVForSourceType((__VLS_ctx.recipesState.recipes.value))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
            ...{ onClick: (...[$event]) => {
                    __VLS_ctx.selectRecipe(r.name);
                } },
            key: (r.name),
            ...{ class: "recipe-item" },
            ...{ class: ({ 'recipe-item--active': __VLS_ctx.selectedName === r.name }) },
            type: "button",
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-mono text-sm truncate" },
        });
        (r.name);
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs px-1.5 py-0.5 rounded" },
            ...{ class: ([
                    r.source === __VLS_ctx.RecipeSource.BUNDLED ? 'badge-bundled' : '',
                    r.source === __VLS_ctx.RecipeSource.TENANT ? 'badge-tenant' : '',
                    r.source === __VLS_ctx.RecipeSource.PROJECT ? 'badge-project' : '',
                ]) },
        });
        (__VLS_ctx.sourceLabelFor(r.source));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-xs opacity-60 truncate" },
        });
        (r.engine);
        (r.description);
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "p-6 flex flex-col gap-3 max-w-5xl" },
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
if (__VLS_ctx.yamlError) {
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
    (__VLS_ctx.yamlError);
    var __VLS_36;
}
if (!__VLS_ctx.selectedRecipe) {
    const __VLS_37 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_38 = __VLS_asFunctionalComponent(__VLS_37, new __VLS_37({
        headline: "Select a recipe",
        body: "Pick an entry on the left to view or edit. The YAML carries every field the brain understands.",
    }));
    const __VLS_39 = __VLS_38({
        headline: "Select a recipe",
        body: "Pick an entry on the left to view or edit. The YAML carries every field the brain understands.",
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
    (__VLS_ctx.selectedRecipe.name);
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.currentProject ? `project ${__VLS_ctx.currentProject}` : 'tenant');
    __VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
    (__VLS_ctx.sourceLabel);
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
            loading: (__VLS_ctx.recipesState.busy.value),
        }));
        const __VLS_47 = __VLS_46({
            ...{ 'onClick': {} },
            variant: "danger",
            loading: (__VLS_ctx.recipesState.busy.value),
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
    const __VLS_53 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.recipesState.busy.value),
    }));
    const __VLS_55 = __VLS_54({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.recipesState.busy.value),
    }, ...__VLS_functionalComponentArgsRest(__VLS_54));
    let __VLS_57;
    let __VLS_58;
    let __VLS_59;
    const __VLS_60 = {
        onClick: (__VLS_ctx.saveOverride)
    };
    __VLS_56.slots.default;
    (__VLS_ctx.isOwnedAtCurrentScope ? 'Save' : 'Override here');
    var __VLS_56;
    if (!__VLS_ctx.isOwnedAtCurrentScope) {
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
        (__VLS_ctx.sourceLabel);
        (__VLS_ctx.currentProject ? 'project' : 'tenant');
        var __VLS_64;
    }
    var __VLS_44;
    const __VLS_65 = {}.CodeEditor;
    /** @type {[typeof __VLS_components.CodeEditor, ]} */ ;
    // @ts-ignore
    const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
        modelValue: (__VLS_ctx.yamlBuffer),
        mimeType: "application/yaml",
        rows: (32),
    }));
    const __VLS_67 = __VLS_66({
        modelValue: (__VLS_ctx.yamlBuffer),
        mimeType: "application/yaml",
        rows: (32),
    }, ...__VLS_functionalComponentArgsRest(__VLS_66));
}
{
    const { 'right-panel': __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-4 flex flex-col gap-4" },
    });
    if (__VLS_ctx.selectedRecipe) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h3, __VLS_intrinsicElements.h3)({
            ...{ class: "text-xs uppercase opacity-60 mb-2" },
        });
        if (__VLS_ctx.availableSnippets.length === 0) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60" },
            });
        }
        else {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex flex-wrap gap-2" },
            });
            for (const [s] of __VLS_getVForSourceType((__VLS_ctx.availableSnippets))) {
                const __VLS_69 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_70 = __VLS_asFunctionalComponent(__VLS_69, new __VLS_69({
                    ...{ 'onClick': {} },
                    key: (s.key),
                    variant: "ghost",
                    size: "sm",
                }));
                const __VLS_71 = __VLS_70({
                    ...{ 'onClick': {} },
                    key: (s.key),
                    variant: "ghost",
                    size: "sm",
                }, ...__VLS_functionalComponentArgsRest(__VLS_70));
                let __VLS_73;
                let __VLS_74;
                let __VLS_75;
                const __VLS_76 = {
                    onClick: (...[$event]) => {
                        if (!(__VLS_ctx.selectedRecipe))
                            return;
                        if (!!(__VLS_ctx.availableSnippets.length === 0))
                            return;
                        __VLS_ctx.insertSnippet(s);
                    }
                };
                __VLS_72.slots.default;
                (s.label);
                var __VLS_72;
            }
        }
    }
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
        const __VLS_77 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_78 = __VLS_asFunctionalComponent(__VLS_77, new __VLS_77({
            source: (__VLS_ctx.help.content.value),
        }));
        const __VLS_79 = __VLS_78({
            source: (__VLS_ctx.help.content.value),
        }, ...__VLS_functionalComponentArgsRest(__VLS_78));
    }
}
const __VLS_81 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
    modelValue: (__VLS_ctx.showNewModal),
    title: "New recipe",
}));
const __VLS_83 = __VLS_82({
    modelValue: (__VLS_ctx.showNewModal),
    title: "New recipe",
}, ...__VLS_functionalComponentArgsRest(__VLS_82));
__VLS_84.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
if (__VLS_ctx.newError) {
    const __VLS_85 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_86 = __VLS_asFunctionalComponent(__VLS_85, new __VLS_85({
        variant: "error",
    }));
    const __VLS_87 = __VLS_86({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_86));
    __VLS_88.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.newError);
    var __VLS_88;
}
const __VLS_89 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
    modelValue: (__VLS_ctx.newName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed. Must not collide with an existing recipe.",
}));
const __VLS_91 = __VLS_90({
    modelValue: (__VLS_ctx.newName),
    label: "Name",
    required: true,
    help: "Lower-case alphanumerics, '-' or '_' allowed. Must not collide with an existing recipe.",
}, ...__VLS_functionalComponentArgsRest(__VLS_90));
const __VLS_93 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_94 = __VLS_asFunctionalComponent(__VLS_93, new __VLS_93({
    modelValue: (__VLS_ctx.newEngine),
    label: "Engine",
    required: true,
    help: "Engine name from the registry — e.g. ford, arthur, marvin, vogon, zaphod.",
}));
const __VLS_95 = __VLS_94({
    modelValue: (__VLS_ctx.newEngine),
    label: "Engine",
    required: true,
    help: "Engine name from the registry — e.g. ford, arthur, marvin, vogon, zaphod.",
}, ...__VLS_functionalComponentArgsRest(__VLS_94));
const __VLS_97 = {}.VInput;
/** @type {[typeof __VLS_components.VInput, ]} */ ;
// @ts-ignore
const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
    modelValue: (__VLS_ctx.newDescription),
    label: "Description",
    required: true,
}));
const __VLS_99 = __VLS_98({
    modelValue: (__VLS_ctx.newDescription),
    label: "Description",
    required: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_98));
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-xs opacity-70" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.strong, __VLS_intrinsicElements.strong)({});
(__VLS_ctx.currentProject ? `project "${__VLS_ctx.currentProject}"` : 'tenant');
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex justify-end gap-2" },
});
const __VLS_101 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_102 = __VLS_asFunctionalComponent(__VLS_101, new __VLS_101({
    ...{ 'onClick': {} },
    variant: "ghost",
}));
const __VLS_103 = __VLS_102({
    ...{ 'onClick': {} },
    variant: "ghost",
}, ...__VLS_functionalComponentArgsRest(__VLS_102));
let __VLS_105;
let __VLS_106;
let __VLS_107;
const __VLS_108 = {
    onClick: (...[$event]) => {
        __VLS_ctx.showNewModal = false;
    }
};
__VLS_104.slots.default;
var __VLS_104;
const __VLS_109 = {}.VButton;
/** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
// @ts-ignore
const __VLS_110 = __VLS_asFunctionalComponent(__VLS_109, new __VLS_109({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.recipesState.busy.value),
}));
const __VLS_111 = __VLS_110({
    ...{ 'onClick': {} },
    variant: "primary",
    loading: (__VLS_ctx.recipesState.busy.value),
}, ...__VLS_functionalComponentArgsRest(__VLS_110));
let __VLS_113;
let __VLS_114;
let __VLS_115;
const __VLS_116 = {
    onClick: (__VLS_ctx.submitNewRecipe)
};
__VLS_112.slots.default;
var __VLS_112;
var __VLS_84;
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
/** @type {__VLS_StyleScopedClasses['recipe-item']} */ ;
/** @type {__VLS_StyleScopedClasses['recipe-item--active']} */ ;
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
/** @type {__VLS_StyleScopedClasses['max-w-5xl']} */ ;
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
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
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
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            RecipeSource: RecipeSource,
            recipesState: recipesState,
            help: help,
            currentProject: currentProject,
            selectedName: selectedName,
            yamlBuffer: yamlBuffer,
            banner: banner,
            yamlError: yamlError,
            showNewModal: showNewModal,
            newName: newName,
            newEngine: newEngine,
            newDescription: newDescription,
            newError: newError,
            projectOptions: projectOptions,
            selectedRecipe: selectedRecipe,
            isOwnedAtCurrentScope: isOwnedAtCurrentScope,
            sourceLabelFor: sourceLabelFor,
            sourceLabel: sourceLabel,
            breadcrumbs: breadcrumbs,
            selectRecipe: selectRecipe,
            openNewRecipe: openNewRecipe,
            submitNewRecipe: submitNewRecipe,
            saveOverride: saveOverride,
            insertSnippet: insertSnippet,
            availableSnippets: availableSnippets,
            deleteOverride: deleteOverride,
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
//# sourceMappingURL=RecipesApp.vue.js.map