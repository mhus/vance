import { computed, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { EditorShell, MarkdownView, VAlert, VButton, VCard, VEmptyState, VInput, VModal, VSelect, VTextarea, } from '@/components';
import { useInbox } from '@/composables/useInbox';
import { useTeams } from '@/composables/useTeams';
import { getUsername, setDocumentDraft } from '@vance/shared';
import { AnswerOutcome, Criticality, InboxItemStatus, InboxItemType, } from '@vance/generated';
const { t } = useI18n();
const inbox = useInbox();
const teamsState = useTeams();
const currentUser = getUsername() ?? 'unknown';
const selection = ref({ kind: 'inbox', tag: null });
function isSelected(other) {
    const s = selection.value;
    if (s.kind !== other.kind)
        return false;
    if (s.kind === 'inbox')
        return s.tag === other.tag;
    if (s.kind === 'team')
        return s.teamName === other.teamName;
    return true;
}
function selectInbox(tag = null) {
    selection.value = { kind: 'inbox', tag };
}
function selectArchive() {
    selection.value = { kind: 'archive' };
}
function selectTeam(teamName) {
    selection.value = { kind: 'team', teamName };
}
// Translate sidebar selection → backend filter.
function selectionToFilter(s) {
    if (s.kind === 'inbox') {
        return {
            assignedTo: { kind: 'self' },
            status: InboxItemStatus.PENDING,
            tag: s.tag,
        };
    }
    if (s.kind === 'archive') {
        return {
            assignedTo: { kind: 'self' },
            status: InboxItemStatus.ARCHIVED,
            tag: null,
        };
    }
    // team
    return {
        assignedTo: { kind: 'team', teamName: s.teamName },
        status: null,
        tag: null,
    };
}
// ─────── Lifecycle ───────
onMounted(async () => {
    await Promise.all([teamsState.reload(), inbox.loadTags()]);
    await inbox.loadList(selectionToFilter(selection.value));
});
watch(selection, async (next) => {
    inbox.clearSelection();
    await inbox.loadList(selectionToFilter(next));
}, { deep: true });
// ─────── Item open ───────
async function openItem(item) {
    if (!item.id)
        return;
    await inbox.loadOne(item.id);
}
function formatTimestamp(value) {
    if (!value)
        return '';
    const d = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(d.getTime()))
        return String(value);
    return d.toLocaleString();
}
function shortPreview(text, max = 100) {
    if (!text)
        return '';
    return text.length > max ? text.substring(0, max) + '…' : text;
}
// ─────── Answer flows ───────
const feedbackText = ref('');
const reasonText = ref('');
const submitting = ref(false);
watch(() => inbox.selected.value?.id, () => {
    feedbackText.value = '';
    reasonText.value = '';
});
async function submitApproval(approved) {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.DECIDED, { approved });
    }
    finally {
        submitting.value = false;
    }
}
async function submitDecision(chosen) {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.DECIDED, { chosen });
    }
    finally {
        submitting.value = false;
    }
}
async function submitFeedback() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    if (!feedbackText.value.trim())
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.DECIDED, { text: feedbackText.value.trim() });
    }
    finally {
        submitting.value = false;
    }
}
async function submitInsufficientInfo() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.INSUFFICIENT_INFO, null, reasonText.value.trim() || null);
    }
    finally {
        submitting.value = false;
    }
}
async function submitUndecidable() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.answer(sel.id, AnswerOutcome.UNDECIDABLE, null, reasonText.value.trim() || null);
    }
    finally {
        submitting.value = false;
    }
}
async function archiveItem() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.archive(sel.id);
    }
    finally {
        submitting.value = false;
    }
}
async function unarchiveItem() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.unarchive(sel.id);
    }
    finally {
        submitting.value = false;
    }
}
/**
 * Hand the current item over to the document editor as a fresh
 * draft. The Document editor reads the draft on mount (one-shot)
 * and opens its create-modal prefilled with title / path / content
 * — see specification/web-ui.md §… (Inbox → Document handoff).
 */
async function toDocument() {
    const sel = inbox.selected.value;
    if (!sel)
        return;
    const ts = sel.createdAt
        ? new Date(sel.createdAt).toISOString().slice(0, 10)
        : new Date().toISOString().slice(0, 10);
    // Slug for the suggested file path. Keep it conservative — the
    // user can edit before saving.
    const slug = (sel.title || sel.id || 'inbox-item')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 60) || 'inbox-item';
    setDocumentDraft({
        title: sel.title ?? '',
        path: `inbox/${ts}-${slug}.md`,
        content: sel.body ?? '',
        mimeType: 'text/markdown',
        source: `Inbox item «${sel.title ?? '(no title)'}» from ${sel.originatorUserId}`,
    });
    // Archive the item — once it's been promoted to a document, it's
    // no longer pending in the inbox. Skip if already archived.
    if (sel.id && sel.status !== InboxItemStatus.ARCHIVED) {
        submitting.value = true;
        try {
            await inbox.archive(sel.id);
        }
        finally {
            submitting.value = false;
        }
    }
    // Same-tab navigation — the Document editor mounts fresh and
    // consumes the draft on its first onMounted.
    window.location.href = '/documents.html?createDraft=1';
}
async function dismissItem() {
    const sel = inbox.selected.value;
    if (!sel?.id)
        return;
    submitting.value = true;
    try {
        await inbox.dismiss(sel.id);
    }
    finally {
        submitting.value = false;
    }
}
// ─────── Delegate modal ───────
const delegateOpen = ref(false);
const delegateTarget = ref('');
const delegateNote = ref('');
const delegating = ref(false);
const delegateOptions = computed(() => {
    // Build the union of all team-mates the current user can delegate
    // to, deduped, sorted, self excluded. Username strings only.
    const set = new Set();
    for (const t of teamsState.teams.value) {
        for (const m of t.members) {
            if (m && m !== currentUser)
                set.add(m);
        }
    }
    return [...set].sort().map((u) => ({ value: u, label: u }));
});
function openDelegateModal() {
    delegateTarget.value = delegateOptions.value[0]?.value ?? '';
    delegateNote.value = '';
    delegateOpen.value = true;
}
async function confirmDelegate() {
    const sel = inbox.selected.value;
    if (!sel?.id || !delegateTarget.value)
        return;
    delegating.value = true;
    try {
        const ok = await inbox.delegate(sel.id, delegateTarget.value, delegateNote.value || null);
        if (ok)
            delegateOpen.value = false;
    }
    finally {
        delegating.value = false;
    }
}
// ─────── Item-type detection ───────
function isAsk(item) {
    return item.requiresAction === true && item.status === InboxItemStatus.PENDING;
}
function decisionOptions(item) {
    const raw = item.payload?.options;
    if (Array.isArray(raw))
        return raw.map((o) => String(o));
    return [];
}
const breadcrumbs = computed(() => {
    const c = [t('inbox.breadcrumbInbox')];
    const s = selection.value;
    if (s.kind === 'inbox' && s.tag)
        c.push('#' + s.tag);
    if (s.kind === 'archive')
        c.push(t('inbox.breadcrumbArchive'));
    if (s.kind === 'team')
        c.push(t('inbox.breadcrumbTeam', { team: s.teamName }));
    if (inbox.selected.value)
        c.push(shortPreview(inbox.selected.value.title, 40));
    return c;
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['inbox-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['list-row']} */ ;
// CSS variable injection 
// CSS variable injection end 
const __VLS_0 = {}.EditorShell;
/** @type {[typeof __VLS_components.EditorShell, typeof __VLS_components.EditorShell, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    title: (__VLS_ctx.$t('inbox.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
}));
const __VLS_2 = __VLS_1({
    title: (__VLS_ctx.$t('inbox.pageTitle')),
    breadcrumbs: (__VLS_ctx.breadcrumbs),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
var __VLS_4 = {};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "inbox-grid" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
    ...{ class: "inbox-sidebar" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
    ...{ class: "flex flex-col gap-1" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.selectInbox(null);
        } },
    ...{ class: "sidebar-item" },
    ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'inbox', tag: null }) }) },
    type: "button",
});
(__VLS_ctx.$t('inbox.sidebar.inbox'));
for (const [tag] of __VLS_getVForSourceType((__VLS_ctx.inbox.tags.value))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.selectInbox(tag);
            } },
        key: ('t-' + tag),
        ...{ class: "sidebar-item sidebar-item--child" },
        ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'inbox', tag }) }) },
        type: "button",
    });
    (tag);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.selectArchive) },
    ...{ class: "sidebar-item mt-2" },
    ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'archive' }) }) },
    type: "button",
});
(__VLS_ctx.$t('inbox.sidebar.archive'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mt-3 text-xs uppercase opacity-50 px-2" },
});
(__VLS_ctx.$t('inbox.sidebar.teamInbox'));
if (__VLS_ctx.teamsState.loading.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-2 text-xs opacity-60" },
    });
    (__VLS_ctx.$t('inbox.sidebar.loadingTeams'));
}
else if (__VLS_ctx.teamsState.teams.value.length === 0) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "px-2 text-xs opacity-60" },
    });
    (__VLS_ctx.$t('inbox.sidebar.noTeams'));
}
for (const [team] of __VLS_getVForSourceType((__VLS_ctx.teamsState.teams.value))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.selectTeam(team.name);
            } },
        key: ('team-' + team.id),
        ...{ class: "sidebar-item sidebar-item--child" },
        ...{ class: ({ 'sidebar-item--active': __VLS_ctx.isSelected({ kind: 'team', teamName: team.name }) }) },
        type: "button",
    });
    (team.title || team.name);
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
    ...{ class: "inbox-list" },
});
if (__VLS_ctx.inbox.error.value) {
    const __VLS_5 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_6 = __VLS_asFunctionalComponent(__VLS_5, new __VLS_5({
        variant: "error",
        ...{ class: "mb-3" },
    }));
    const __VLS_7 = __VLS_6({
        variant: "error",
        ...{ class: "mb-3" },
    }, ...__VLS_functionalComponentArgsRest(__VLS_6));
    __VLS_8.slots.default;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.inbox.error.value);
    var __VLS_8;
}
if (!__VLS_ctx.inbox.loading.value && __VLS_ctx.inbox.items.value.length === 0) {
    const __VLS_9 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_10 = __VLS_asFunctionalComponent(__VLS_9, new __VLS_9({
        headline: (__VLS_ctx.$t('inbox.list.emptyHeadline')),
        body: (__VLS_ctx.$t('inbox.list.emptyBody')),
    }));
    const __VLS_11 = __VLS_10({
        headline: (__VLS_ctx.$t('inbox.list.emptyHeadline')),
        body: (__VLS_ctx.$t('inbox.list.emptyBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_10));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
    ...{ class: "flex flex-col gap-1" },
});
for (const [item] of __VLS_getVForSourceType((__VLS_ctx.inbox.items.value))) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
        ...{ onClick: (...[$event]) => {
                __VLS_ctx.openItem(item);
            } },
        key: (item.id ?? ''),
        ...{ class: "list-row" },
        ...{ class: ({ 'list-row--active': __VLS_ctx.inbox.selected.value?.id === item.id }) },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center justify-between gap-2" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "font-medium truncate" },
    });
    (item.title || __VLS_ctx.$t('inbox.list.noTitle'));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
        ...{ class: "text-xs opacity-60 shrink-0" },
    });
    (__VLS_ctx.formatTimestamp(item.createdAt));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex items-center gap-2 text-xs opacity-70" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (item.type);
    if (item.criticality !== __VLS_ctx.Criticality.NORMAL) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "badge badge-warning badge-sm" },
        });
        (item.criticality);
    }
    if (item.assignedToUserId !== __VLS_ctx.currentUser) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-60" },
        });
        (item.assignedToUserId);
    }
    if (item.status !== __VLS_ctx.InboxItemStatus.PENDING) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "opacity-60" },
        });
        (item.status);
    }
    if (item.tags && item.tags.length) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-1 flex gap-1 flex-wrap" },
        });
        for (const [t] of __VLS_getVForSourceType((item.tags))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                key: (t),
                ...{ class: "badge badge-ghost badge-sm" },
            });
            (t);
        }
    }
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
    ...{ class: "inbox-detail" },
});
if (!__VLS_ctx.inbox.selected.value) {
    const __VLS_13 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_14 = __VLS_asFunctionalComponent(__VLS_13, new __VLS_13({
        headline: (__VLS_ctx.$t('inbox.detail.pickAnItem')),
        body: (__VLS_ctx.$t('inbox.detail.pickAnItemBody')),
    }));
    const __VLS_15 = __VLS_14({
        headline: (__VLS_ctx.$t('inbox.detail.pickAnItem')),
        body: (__VLS_ctx.$t('inbox.detail.pickAnItemBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_14));
}
else {
    const __VLS_17 = {}.VCard;
    /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
    // @ts-ignore
    const __VLS_18 = __VLS_asFunctionalComponent(__VLS_17, new __VLS_17({}));
    const __VLS_19 = __VLS_18({}, ...__VLS_functionalComponentArgsRest(__VLS_18));
    __VLS_20.slots.default;
    {
        const { header: __VLS_thisSlot } = __VLS_20.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex items-center justify-between gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "font-semibold truncate" },
        });
        (__VLS_ctx.inbox.selected.value.title || __VLS_ctx.$t('inbox.detail.noTitle'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
            ...{ class: "text-xs opacity-60" },
        });
        (__VLS_ctx.inbox.selected.value.type);
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-60 flex flex-wrap gap-3 mb-3" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.detail.fromLabel', { user: __VLS_ctx.inbox.selected.value.originatorUserId }));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.detail.toLabel', { user: __VLS_ctx.inbox.selected.value.assignedToUserId }));
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.$t('inbox.detail.statusLabel', { status: __VLS_ctx.inbox.selected.value.status }));
    if (__VLS_ctx.inbox.selected.value.criticality !== __VLS_ctx.Criticality.NORMAL) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.$t('inbox.detail.criticalityLabel', { criticality: __VLS_ctx.inbox.selected.value.criticality }));
    }
    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
    (__VLS_ctx.formatTimestamp(__VLS_ctx.inbox.selected.value.createdAt));
    if (__VLS_ctx.inbox.selected.value.body) {
        const __VLS_21 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_22 = __VLS_asFunctionalComponent(__VLS_21, new __VLS_21({
            source: (__VLS_ctx.inbox.selected.value.body),
        }));
        const __VLS_23 = __VLS_22({
            source: (__VLS_ctx.inbox.selected.value.body),
        }, ...__VLS_functionalComponentArgsRest(__VLS_22));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-60 italic" },
        });
        (__VLS_ctx.$t('inbox.detail.noBody'));
    }
    if (__VLS_ctx.inbox.selected.value.payload && Object.keys(__VLS_ctx.inbox.selected.value.payload).length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 text-xs" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({});
        __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({
            ...{ class: "cursor-pointer opacity-70" },
        });
        (__VLS_ctx.$t('inbox.detail.payload'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
            ...{ class: "text-xs bg-base-200 p-2 rounded mt-1 overflow-auto" },
        });
        (JSON.stringify(__VLS_ctx.inbox.selected.value.payload, null, 2));
    }
    if (__VLS_ctx.inbox.selected.value.answer) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "mt-3 p-3 rounded bg-base-200 text-sm" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-70 text-xs mb-1" },
        });
        (__VLS_ctx.$t('inbox.detail.answer'));
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
        (__VLS_ctx.$t('inbox.detail.answerOutcome', { outcome: __VLS_ctx.inbox.selected.value.answer.outcome }));
        if (__VLS_ctx.inbox.selected.value.answer.value) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            (__VLS_ctx.$t('inbox.detail.answerValue'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.code, __VLS_intrinsicElements.code)({});
            (JSON.stringify(__VLS_ctx.inbox.selected.value.answer.value));
        }
        if (__VLS_ctx.inbox.selected.value.answer.reason) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({});
            (__VLS_ctx.$t('inbox.detail.answerReason', { reason: __VLS_ctx.inbox.selected.value.answer.reason }));
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "opacity-60 text-xs mt-1" },
        });
        (__VLS_ctx.$t('inbox.detail.answerBy', { user: __VLS_ctx.inbox.selected.value.answer.answeredBy }));
    }
    {
        const { actions: __VLS_thisSlot } = __VLS_20.slots;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "flex flex-wrap gap-2 w-full" },
        });
        if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL) {
            const __VLS_25 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_26 = __VLS_asFunctionalComponent(__VLS_25, new __VLS_25({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_27 = __VLS_26({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_26));
            let __VLS_29;
            let __VLS_30;
            let __VLS_31;
            const __VLS_32 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.inbox.selected.value))
                        return;
                    if (!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL))
                        return;
                    __VLS_ctx.submitApproval(true);
                }
            };
            __VLS_28.slots.default;
            (__VLS_ctx.$t('inbox.actions.yes'));
            var __VLS_28;
            const __VLS_33 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_34 = __VLS_asFunctionalComponent(__VLS_33, new __VLS_33({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_35 = __VLS_34({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_34));
            let __VLS_37;
            let __VLS_38;
            let __VLS_39;
            const __VLS_40 = {
                onClick: (...[$event]) => {
                    if (!!(!__VLS_ctx.inbox.selected.value))
                        return;
                    if (!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL))
                        return;
                    __VLS_ctx.submitApproval(false);
                }
            };
            __VLS_36.slots.default;
            (__VLS_ctx.$t('inbox.actions.no'));
            var __VLS_36;
        }
        else if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.DECISION) {
            for (const [opt] of __VLS_getVForSourceType((__VLS_ctx.decisionOptions(__VLS_ctx.inbox.selected.value)))) {
                const __VLS_41 = {}.VButton;
                /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
                // @ts-ignore
                const __VLS_42 = __VLS_asFunctionalComponent(__VLS_41, new __VLS_41({
                    ...{ 'onClick': {} },
                    key: (opt),
                    variant: "primary",
                    loading: (__VLS_ctx.submitting),
                }));
                const __VLS_43 = __VLS_42({
                    ...{ 'onClick': {} },
                    key: (opt),
                    variant: "primary",
                    loading: (__VLS_ctx.submitting),
                }, ...__VLS_functionalComponentArgsRest(__VLS_42));
                let __VLS_45;
                let __VLS_46;
                let __VLS_47;
                const __VLS_48 = {
                    onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.inbox.selected.value))
                            return;
                        if (!!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.APPROVAL))
                            return;
                        if (!(__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.DECISION))
                            return;
                        __VLS_ctx.submitDecision(opt);
                    }
                };
                __VLS_44.slots.default;
                (opt);
                var __VLS_44;
            }
            if (__VLS_ctx.decisionOptions(__VLS_ctx.inbox.selected.value).length === 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-60" },
                });
                (__VLS_ctx.$t('inbox.actions.noOptionsHint'));
            }
        }
        else if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value) && __VLS_ctx.inbox.selected.value.type === __VLS_ctx.InboxItemType.FEEDBACK) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex-1 min-w-0" },
            });
            const __VLS_49 = {}.VTextarea;
            /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
            // @ts-ignore
            const __VLS_50 = __VLS_asFunctionalComponent(__VLS_49, new __VLS_49({
                modelValue: (__VLS_ctx.feedbackText),
                label: "",
                rows: (3),
                disabled: (__VLS_ctx.submitting),
            }));
            const __VLS_51 = __VLS_50({
                modelValue: (__VLS_ctx.feedbackText),
                label: "",
                rows: (3),
                disabled: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_50));
            const __VLS_53 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_54 = __VLS_asFunctionalComponent(__VLS_53, new __VLS_53({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
                disabled: (!__VLS_ctx.feedbackText.trim()),
            }));
            const __VLS_55 = __VLS_54({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
                disabled: (!__VLS_ctx.feedbackText.trim()),
            }, ...__VLS_functionalComponentArgsRest(__VLS_54));
            let __VLS_57;
            let __VLS_58;
            let __VLS_59;
            const __VLS_60 = {
                onClick: (__VLS_ctx.submitFeedback)
            };
            __VLS_56.slots.default;
            (__VLS_ctx.$t('inbox.actions.send'));
            var __VLS_56;
        }
        if (__VLS_ctx.isAsk(__VLS_ctx.inbox.selected.value)) {
            const __VLS_61 = {}.VInput;
            /** @type {[typeof __VLS_components.VInput, ]} */ ;
            // @ts-ignore
            const __VLS_62 = __VLS_asFunctionalComponent(__VLS_61, new __VLS_61({
                modelValue: (__VLS_ctx.reasonText),
                placeholder: (__VLS_ctx.$t('inbox.actions.reasonPlaceholder')),
                disabled: (__VLS_ctx.submitting),
                ...{ class: "flex-1 min-w-[14rem]" },
            }));
            const __VLS_63 = __VLS_62({
                modelValue: (__VLS_ctx.reasonText),
                placeholder: (__VLS_ctx.$t('inbox.actions.reasonPlaceholder')),
                disabled: (__VLS_ctx.submitting),
                ...{ class: "flex-1 min-w-[14rem]" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_62));
            const __VLS_65 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_66 = __VLS_asFunctionalComponent(__VLS_65, new __VLS_65({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_67 = __VLS_66({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_66));
            let __VLS_69;
            let __VLS_70;
            let __VLS_71;
            const __VLS_72 = {
                onClick: (__VLS_ctx.submitInsufficientInfo)
            };
            __VLS_68.slots.default;
            (__VLS_ctx.$t('inbox.actions.insufficientInfo'));
            var __VLS_68;
            const __VLS_73 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_74 = __VLS_asFunctionalComponent(__VLS_73, new __VLS_73({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_75 = __VLS_74({
                ...{ 'onClick': {} },
                variant: "ghost",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_74));
            let __VLS_77;
            let __VLS_78;
            let __VLS_79;
            const __VLS_80 = {
                onClick: (__VLS_ctx.submitUndecidable)
            };
            __VLS_76.slots.default;
            (__VLS_ctx.$t('inbox.actions.undecidable'));
            var __VLS_76;
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span)({
            ...{ class: "grow" },
        });
        const __VLS_81 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_82 = __VLS_asFunctionalComponent(__VLS_81, new __VLS_81({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }));
        const __VLS_83 = __VLS_82({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_82));
        let __VLS_85;
        let __VLS_86;
        let __VLS_87;
        const __VLS_88 = {
            onClick: (__VLS_ctx.toDocument)
        };
        __VLS_84.slots.default;
        (__VLS_ctx.$t('inbox.actions.toDocument'));
        var __VLS_84;
        const __VLS_89 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_90 = __VLS_asFunctionalComponent(__VLS_89, new __VLS_89({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }));
        const __VLS_91 = __VLS_90({
            ...{ 'onClick': {} },
            variant: "ghost",
            disabled: (__VLS_ctx.submitting),
        }, ...__VLS_functionalComponentArgsRest(__VLS_90));
        let __VLS_93;
        let __VLS_94;
        let __VLS_95;
        const __VLS_96 = {
            onClick: (__VLS_ctx.openDelegateModal)
        };
        __VLS_92.slots.default;
        (__VLS_ctx.$t('inbox.actions.delegate'));
        var __VLS_92;
        if (__VLS_ctx.inbox.selected.value.status !== __VLS_ctx.InboxItemStatus.DISMISSED) {
            const __VLS_97 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_98 = __VLS_asFunctionalComponent(__VLS_97, new __VLS_97({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.submitting),
            }));
            const __VLS_99 = __VLS_98({
                ...{ 'onClick': {} },
                variant: "ghost",
                disabled: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_98));
            let __VLS_101;
            let __VLS_102;
            let __VLS_103;
            const __VLS_104 = {
                onClick: (__VLS_ctx.dismissItem)
            };
            __VLS_100.slots.default;
            (__VLS_ctx.$t('inbox.actions.dismiss'));
            var __VLS_100;
        }
        if (__VLS_ctx.inbox.selected.value.status === __VLS_ctx.InboxItemStatus.ARCHIVED) {
            const __VLS_105 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_106 = __VLS_asFunctionalComponent(__VLS_105, new __VLS_105({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_107 = __VLS_106({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_106));
            let __VLS_109;
            let __VLS_110;
            let __VLS_111;
            const __VLS_112 = {
                onClick: (__VLS_ctx.unarchiveItem)
            };
            __VLS_108.slots.default;
            (__VLS_ctx.$t('inbox.actions.unarchive'));
            var __VLS_108;
        }
        else {
            const __VLS_113 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_114 = __VLS_asFunctionalComponent(__VLS_113, new __VLS_113({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }));
            const __VLS_115 = __VLS_114({
                ...{ 'onClick': {} },
                variant: "primary",
                loading: (__VLS_ctx.submitting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_114));
            let __VLS_117;
            let __VLS_118;
            let __VLS_119;
            const __VLS_120 = {
                onClick: (__VLS_ctx.archiveItem)
            };
            __VLS_116.slots.default;
            (__VLS_ctx.$t('inbox.actions.archive'));
            var __VLS_116;
        }
    }
    var __VLS_20;
}
const __VLS_121 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_122 = __VLS_asFunctionalComponent(__VLS_121, new __VLS_121({
    modelValue: (__VLS_ctx.delegateOpen),
    title: (__VLS_ctx.$t('inbox.delegate.title')),
    closeOnBackdrop: (!__VLS_ctx.delegating),
}));
const __VLS_123 = __VLS_122({
    modelValue: (__VLS_ctx.delegateOpen),
    title: (__VLS_ctx.$t('inbox.delegate.title')),
    closeOnBackdrop: (!__VLS_ctx.delegating),
}, ...__VLS_functionalComponentArgsRest(__VLS_122));
__VLS_124.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm opacity-80 mb-3" },
});
(__VLS_ctx.$t('inbox.delegate.body'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
const __VLS_125 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_126 = __VLS_asFunctionalComponent(__VLS_125, new __VLS_125({
    modelValue: (__VLS_ctx.delegateTarget),
    options: (__VLS_ctx.delegateOptions),
    label: (__VLS_ctx.$t('inbox.delegate.recipient')),
    disabled: (__VLS_ctx.delegating || __VLS_ctx.delegateOptions.length === 0),
}));
const __VLS_127 = __VLS_126({
    modelValue: (__VLS_ctx.delegateTarget),
    options: (__VLS_ctx.delegateOptions),
    label: (__VLS_ctx.$t('inbox.delegate.recipient')),
    disabled: (__VLS_ctx.delegating || __VLS_ctx.delegateOptions.length === 0),
}, ...__VLS_functionalComponentArgsRest(__VLS_126));
const __VLS_129 = {}.VTextarea;
/** @type {[typeof __VLS_components.VTextarea, ]} */ ;
// @ts-ignore
const __VLS_130 = __VLS_asFunctionalComponent(__VLS_129, new __VLS_129({
    modelValue: (__VLS_ctx.delegateNote),
    label: (__VLS_ctx.$t('inbox.delegate.note')),
    rows: (3),
    disabled: (__VLS_ctx.delegating),
}));
const __VLS_131 = __VLS_130({
    modelValue: (__VLS_ctx.delegateNote),
    label: (__VLS_ctx.$t('inbox.delegate.note')),
    rows: (3),
    disabled: (__VLS_ctx.delegating),
}, ...__VLS_functionalComponentArgsRest(__VLS_130));
{
    const { actions: __VLS_thisSlot } = __VLS_124.slots;
    const __VLS_133 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_134 = __VLS_asFunctionalComponent(__VLS_133, new __VLS_133({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.delegating),
    }));
    const __VLS_135 = __VLS_134({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.delegating),
    }, ...__VLS_functionalComponentArgsRest(__VLS_134));
    let __VLS_137;
    let __VLS_138;
    let __VLS_139;
    const __VLS_140 = {
        onClick: (...[$event]) => {
            __VLS_ctx.delegateOpen = false;
        }
    };
    __VLS_136.slots.default;
    (__VLS_ctx.$t('inbox.delegate.cancel'));
    var __VLS_136;
    const __VLS_141 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_142 = __VLS_asFunctionalComponent(__VLS_141, new __VLS_141({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.delegating),
        disabled: (!__VLS_ctx.delegateTarget || __VLS_ctx.delegateOptions.length === 0),
    }));
    const __VLS_143 = __VLS_142({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.delegating),
        disabled: (!__VLS_ctx.delegateTarget || __VLS_ctx.delegateOptions.length === 0),
    }, ...__VLS_functionalComponentArgsRest(__VLS_142));
    let __VLS_145;
    let __VLS_146;
    let __VLS_147;
    const __VLS_148 = {
        onClick: (__VLS_ctx.confirmDelegate)
    };
    __VLS_144.slots.default;
    (__VLS_ctx.$t('inbox.delegate.confirm'));
    var __VLS_144;
}
var __VLS_124;
var __VLS_3;
/** @type {__VLS_StyleScopedClasses['inbox-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['inbox-sidebar']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--child']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-50']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['px-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--child']} */ ;
/** @type {__VLS_StyleScopedClasses['sidebar-item--active']} */ ;
/** @type {__VLS_StyleScopedClasses['inbox-list']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['list-row']} */ ;
/** @type {__VLS_StyleScopedClasses['list-row--active']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['badge']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-ghost']} */ ;
/** @type {__VLS_StyleScopedClasses['badge-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['inbox-detail']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-[14rem]']} */ ;
/** @type {__VLS_StyleScopedClasses['grow']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            EditorShell: EditorShell,
            MarkdownView: MarkdownView,
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VInput: VInput,
            VModal: VModal,
            VSelect: VSelect,
            VTextarea: VTextarea,
            Criticality: Criticality,
            InboxItemStatus: InboxItemStatus,
            InboxItemType: InboxItemType,
            inbox: inbox,
            teamsState: teamsState,
            currentUser: currentUser,
            isSelected: isSelected,
            selectInbox: selectInbox,
            selectArchive: selectArchive,
            selectTeam: selectTeam,
            openItem: openItem,
            formatTimestamp: formatTimestamp,
            feedbackText: feedbackText,
            reasonText: reasonText,
            submitting: submitting,
            submitApproval: submitApproval,
            submitDecision: submitDecision,
            submitFeedback: submitFeedback,
            submitInsufficientInfo: submitInsufficientInfo,
            submitUndecidable: submitUndecidable,
            archiveItem: archiveItem,
            unarchiveItem: unarchiveItem,
            toDocument: toDocument,
            dismissItem: dismissItem,
            delegateOpen: delegateOpen,
            delegateTarget: delegateTarget,
            delegateNote: delegateNote,
            delegating: delegating,
            delegateOptions: delegateOptions,
            openDelegateModal: openDelegateModal,
            confirmDelegate: confirmDelegate,
            isAsk: isAsk,
            decisionOptions: decisionOptions,
            breadcrumbs: breadcrumbs,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=InboxApp.vue.js.map