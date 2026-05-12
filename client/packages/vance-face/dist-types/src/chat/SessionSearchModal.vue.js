import { SessionSearchScope, SessionStatus, } from '@vance/generated';
import { getSessionMessages, searchSessions } from '@vance/shared';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { MarkdownView, VAlert, VButton, VCheckbox, VEmptyState, VModal, VSelect, } from '../components/index';
const { t } = useI18n();
const emit = defineEmits();
const open = ref(true);
const query = ref('');
const scope = ref(SessionSearchScope.BOTH);
const includeArchived = ref(true);
const hits = ref([]);
const loading = ref(false);
const error = ref(null);
const searched = ref(false);
// Preview-modal state.
const previewSessionId = ref(null);
const previewSession = ref(null);
const previewMessages = ref([]);
const previewLoading = ref(false);
const previewError = ref(null);
const metadataHits = computed(() => hits.value.filter((h) => h.matchedIn === SessionSearchScope.METADATA));
const contentHits = computed(() => hits.value.filter((h) => h.matchedIn === SessionSearchScope.CONTENT));
let debounceTimer = null;
watch([query, scope, includeArchived], () => {
    if (debounceTimer)
        clearTimeout(debounceTimer);
    const q = query.value.trim();
    if (q.length === 0) {
        hits.value = [];
        searched.value = false;
        return;
    }
    debounceTimer = setTimeout(() => void runSearch(), 220);
});
async function runSearch() {
    const q = query.value.trim();
    if (q.length === 0)
        return;
    loading.value = true;
    error.value = null;
    try {
        hits.value = await searchSessions({
            q,
            scope: scope.value,
            includeArchived: includeArchived.value,
            limit: 50,
        });
        searched.value = true;
    }
    catch (e) {
        error.value = t('chat.search.failed') + ' ' + e.message;
        hits.value = [];
    }
    finally {
        loading.value = false;
    }
}
function close() {
    open.value = false;
    emit('close');
}
function onModalChange(value) {
    open.value = value;
    if (!value)
        emit('close');
}
function pick(hit) {
    emit('pick', hit.session.sessionId);
}
async function showPreview(hit) {
    previewSession.value = hit.session;
    previewSessionId.value = hit.session.sessionId;
    previewLoading.value = true;
    previewError.value = null;
    previewMessages.value = [];
    try {
        previewMessages.value = await getSessionMessages(hit.session.sessionId, 200);
    }
    catch (e) {
        previewError.value =
            t('chat.search.previewError') + ' ' + e.message;
    }
    finally {
        previewLoading.value = false;
    }
}
function closePreview() {
    previewSessionId.value = null;
    previewSession.value = null;
    previewMessages.value = [];
    previewError.value = null;
}
function scopeLabel(value) {
    switch (value) {
        case SessionSearchScope.METADATA: return t('chat.search.scopeMetadata');
        case SessionSearchScope.CONTENT: return t('chat.search.scopeContent');
        case SessionSearchScope.BOTH:
        default: return t('chat.search.scopeBoth');
    }
}
const SCOPE_OPTIONS = [
    { label: scopeLabel(SessionSearchScope.BOTH), value: SessionSearchScope.BOTH },
    { label: scopeLabel(SessionSearchScope.METADATA), value: SessionSearchScope.METADATA },
    { label: scopeLabel(SessionSearchScope.CONTENT), value: SessionSearchScope.CONTENT },
];
function sessionTitle(session) {
    if (session.title && session.title.trim().length > 0)
        return session.title;
    if (session.firstUserMessage && session.firstUserMessage.trim().length > 0) {
        return session.firstUserMessage;
    }
    return t('chat.sessionHeader.untitled');
}
function isArchived(session) {
    return session.status === SessionStatus.ARCHIVED;
}
function previewTimestamp(value) {
    if (value === undefined || value === null)
        return '';
    const d = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(d.getTime()))
        return '';
    return d.toLocaleString();
}
// Focus the input on first render — keyboard-driven dialog UX.
const inputRef = ref(null);
onMounted(() => {
    setTimeout(() => inputRef.value?.focus(), 0);
});
onBeforeUnmount(() => {
    if (debounceTimer)
        clearTimeout(debounceTimer);
});
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
const __VLS_0 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.open),
    title: (__VLS_ctx.t('chat.search.title')),
}));
const __VLS_2 = __VLS_1({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.open),
    title: (__VLS_ctx.t('chat.search.title')),
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
let __VLS_4;
let __VLS_5;
let __VLS_6;
const __VLS_7 = {
    'onUpdate:modelValue': (__VLS_ctx.onModalChange)
};
__VLS_3.slots.default;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex items-center gap-2" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ onKeyup: (__VLS_ctx.runSearch) },
    ref: "inputRef",
    type: "search",
    ...{ class: "input input-bordered flex-1" },
    placeholder: (__VLS_ctx.t('chat.search.placeholder')),
});
(__VLS_ctx.query);
/** @type {typeof __VLS_ctx.inputRef} */ ;
const __VLS_8 = {}.VSelect;
/** @type {[typeof __VLS_components.VSelect, ]} */ ;
// @ts-ignore
const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
    modelValue: (__VLS_ctx.scope),
    options: (__VLS_ctx.SCOPE_OPTIONS),
}));
const __VLS_10 = __VLS_9({
    modelValue: (__VLS_ctx.scope),
    options: (__VLS_ctx.SCOPE_OPTIONS),
}, ...__VLS_functionalComponentArgsRest(__VLS_9));
const __VLS_12 = {}.VCheckbox;
/** @type {[typeof __VLS_components.VCheckbox, ]} */ ;
// @ts-ignore
const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
    modelValue: (__VLS_ctx.includeArchived),
    label: (__VLS_ctx.t('chat.search.includeArchived')),
}));
const __VLS_14 = __VLS_13({
    modelValue: (__VLS_ctx.includeArchived),
    label: (__VLS_ctx.t('chat.search.includeArchived')),
}, ...__VLS_functionalComponentArgsRest(__VLS_13));
if (__VLS_ctx.error) {
    const __VLS_16 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
        variant: "error",
    }));
    const __VLS_18 = __VLS_17({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_17));
    __VLS_19.slots.default;
    (__VLS_ctx.error);
    var __VLS_19;
}
if (__VLS_ctx.loading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.t('chat.picker.loading'));
}
else if (!__VLS_ctx.searched && !__VLS_ctx.loading) {
    const __VLS_20 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
        headline: (__VLS_ctx.t('chat.search.empty')),
        body: (__VLS_ctx.t('chat.search.emptyBody')),
    }));
    const __VLS_22 = __VLS_21({
        headline: (__VLS_ctx.t('chat.search.empty')),
        body: (__VLS_ctx.t('chat.search.emptyBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_21));
}
else if (__VLS_ctx.searched && __VLS_ctx.hits.length === 0) {
    const __VLS_24 = {}.VEmptyState;
    /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
    // @ts-ignore
    const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
        headline: (__VLS_ctx.t('chat.search.noResults')),
        body: (__VLS_ctx.t('chat.search.noResultsBody')),
    }));
    const __VLS_26 = __VLS_25({
        headline: (__VLS_ctx.t('chat.search.noResults')),
        body: (__VLS_ctx.t('chat.search.noResultsBody')),
    }, ...__VLS_functionalComponentArgsRest(__VLS_25));
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-4 max-h-96 overflow-y-auto pr-1" },
    });
    if (__VLS_ctx.metadataHits.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
            ...{ class: "flex flex-col gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold" },
        });
        (__VLS_ctx.t('chat.search.headlineMetadata'));
        for (const [hit] of __VLS_getVForSourceType((__VLS_ctx.metadataHits))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(__VLS_ctx.loading))
                            return;
                        if (!!(!__VLS_ctx.searched && !__VLS_ctx.loading))
                            return;
                        if (!!(__VLS_ctx.searched && __VLS_ctx.hits.length === 0))
                            return;
                        if (!(__VLS_ctx.metadataHits.length > 0))
                            return;
                        __VLS_ctx.pick(hit);
                    } },
                key: (`m-${hit.session.sessionId}`),
                type: "button",
                ...{ class: "text-left rounded border border-base-300 hover:border-primary p-3 flex items-start gap-3 bg-base-100" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xl shrink-0" },
            });
            if (hit.session.icon) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (hit.session.icon);
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-30" },
                });
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex-1 min-w-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-2 min-w-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-medium truncate" },
            });
            (__VLS_ctx.sessionTitle(hit.session));
            if (__VLS_ctx.isArchived(hit.session)) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 shrink-0" },
                });
                (__VLS_ctx.t('chat.sessionHeader.archived'));
            }
            if (hit.session.tags && hit.session.tags.length > 0) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex flex-wrap gap-1 mt-1" },
                });
                for (const [tag] of __VLS_getVForSourceType((hit.session.tags))) {
                    __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                        key: (tag),
                        ...{ class: "text-[10px] px-1.5 py-0.5 rounded bg-base-200" },
                    });
                    (tag);
                }
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 truncate mt-1" },
            });
            (hit.session.projectId);
        }
    }
    if (__VLS_ctx.contentHits.length > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.section, __VLS_intrinsicElements.section)({
            ...{ class: "flex flex-col gap-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.h4, __VLS_intrinsicElements.h4)({
            ...{ class: "text-xs uppercase tracking-wide opacity-60 font-semibold" },
        });
        (__VLS_ctx.t('chat.search.headlineContent'));
        for (const [hit] of __VLS_getVForSourceType((__VLS_ctx.contentHits))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                key: (`c-${hit.session.sessionId}-${hit.matchedMessageId ?? ''}`),
                ...{ class: "rounded border border-base-300 p-3 bg-base-100" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-start gap-3" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xl shrink-0" },
            });
            if (hit.session.icon) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (hit.session.icon);
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "opacity-30" },
                });
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex-1 min-w-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-2 min-w-0" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-medium truncate" },
            });
            (__VLS_ctx.sessionTitle(hit.session));
            if (__VLS_ctx.isArchived(hit.session)) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs uppercase tracking-wide px-1.5 py-0.5 rounded bg-warning/15 text-warning border border-warning/30 shrink-0" },
                });
                (__VLS_ctx.t('chat.sessionHeader.archived'));
            }
            if (hit.matchedRole) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-[10px] uppercase tracking-wide px-1 py-0.5 rounded bg-base-200 shrink-0" },
                });
                (hit.matchedRole);
            }
            if (hit.snippet) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                    ...{ class: "text-sm opacity-80 mt-1 line-clamp-3" },
                });
                (hit.snippet);
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 mt-1" },
            });
            (hit.session.projectId);
            if (hit.matchedAt) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.previewTimestamp(hit.matchedAt));
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "shrink-0 flex flex-col gap-1" },
            });
            const __VLS_28 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "ghost",
            }));
            const __VLS_30 = __VLS_29({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "ghost",
            }, ...__VLS_functionalComponentArgsRest(__VLS_29));
            let __VLS_32;
            let __VLS_33;
            let __VLS_34;
            const __VLS_35 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.loading))
                        return;
                    if (!!(!__VLS_ctx.searched && !__VLS_ctx.loading))
                        return;
                    if (!!(__VLS_ctx.searched && __VLS_ctx.hits.length === 0))
                        return;
                    if (!(__VLS_ctx.contentHits.length > 0))
                        return;
                    __VLS_ctx.showPreview(hit);
                }
            };
            __VLS_31.slots.default;
            (__VLS_ctx.t('chat.search.preview'));
            var __VLS_31;
            const __VLS_36 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "primary",
            }));
            const __VLS_38 = __VLS_37({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "primary",
            }, ...__VLS_functionalComponentArgsRest(__VLS_37));
            let __VLS_40;
            let __VLS_41;
            let __VLS_42;
            const __VLS_43 = {
                onClick: (...[$event]) => {
                    if (!!(__VLS_ctx.loading))
                        return;
                    if (!!(!__VLS_ctx.searched && !__VLS_ctx.loading))
                        return;
                    if (!!(__VLS_ctx.searched && __VLS_ctx.hits.length === 0))
                        return;
                    if (!(__VLS_ctx.contentHits.length > 0))
                        return;
                    __VLS_ctx.pick(hit);
                }
            };
            __VLS_39.slots.default;
            (__VLS_ctx.t('chat.search.open'));
            var __VLS_39;
        }
    }
}
{
    const { actions: __VLS_thisSlot } = __VLS_3.slots;
    const __VLS_44 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_46 = __VLS_45({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_45));
    let __VLS_48;
    let __VLS_49;
    let __VLS_50;
    const __VLS_51 = {
        onClick: (__VLS_ctx.close)
    };
    __VLS_47.slots.default;
    (__VLS_ctx.t('chat.search.previewClose'));
    var __VLS_47;
}
var __VLS_3;
const __VLS_52 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.previewSessionId !== null),
    title: (__VLS_ctx.previewSession ? __VLS_ctx.sessionTitle(__VLS_ctx.previewSession) : __VLS_ctx.t('chat.search.preview')),
}));
const __VLS_54 = __VLS_53({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.previewSessionId !== null),
    title: (__VLS_ctx.previewSession ? __VLS_ctx.sessionTitle(__VLS_ctx.previewSession) : __VLS_ctx.t('chat.search.preview')),
}, ...__VLS_functionalComponentArgsRest(__VLS_53));
let __VLS_56;
let __VLS_57;
let __VLS_58;
const __VLS_59 = {
    'onUpdate:modelValue': ((v) => { if (!v)
        __VLS_ctx.closePreview(); })
};
__VLS_55.slots.default;
if (__VLS_ctx.previewLoading) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-sm opacity-60" },
    });
    (__VLS_ctx.t('chat.search.previewLoading'));
}
else if (__VLS_ctx.previewError) {
    const __VLS_60 = {}.VAlert;
    /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
    // @ts-ignore
    const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
        variant: "error",
    }));
    const __VLS_62 = __VLS_61({
        variant: "error",
    }, ...__VLS_functionalComponentArgsRest(__VLS_61));
    __VLS_63.slots.default;
    (__VLS_ctx.previewError);
    var __VLS_63;
}
else {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-3 max-h-96 overflow-y-auto pr-1" },
    });
    for (const [msg] of __VLS_getVForSourceType((__VLS_ctx.previewMessages))) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            key: (msg.messageId ?? `${msg.createdAt}`),
            ...{ class: "rounded border border-base-200 p-2" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-[10px] uppercase tracking-wide opacity-60 mb-1" },
        });
        (msg.role);
        if (msg.createdAt) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
            (__VLS_ctx.previewTimestamp(msg.createdAt));
        }
        const __VLS_64 = {}.MarkdownView;
        /** @type {[typeof __VLS_components.MarkdownView, ]} */ ;
        // @ts-ignore
        const __VLS_65 = __VLS_asFunctionalComponent(__VLS_64, new __VLS_64({
            source: (msg.content ?? ''),
        }));
        const __VLS_66 = __VLS_65({
            source: (msg.content ?? ''),
        }, ...__VLS_functionalComponentArgsRest(__VLS_65));
    }
    if (__VLS_ctx.previewMessages.length === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-60" },
        });
        (__VLS_ctx.t('chat.picker.noSessionsBody'));
    }
}
{
    const { actions: __VLS_thisSlot } = __VLS_55.slots;
    if (__VLS_ctx.previewSession) {
        const __VLS_68 = {}.VButton;
        /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
        // @ts-ignore
        const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
            ...{ 'onClick': {} },
            variant: "primary",
        }));
        const __VLS_70 = __VLS_69({
            ...{ 'onClick': {} },
            variant: "primary",
        }, ...__VLS_functionalComponentArgsRest(__VLS_69));
        let __VLS_72;
        let __VLS_73;
        let __VLS_74;
        const __VLS_75 = {
            onClick: (...[$event]) => {
                if (!(__VLS_ctx.previewSession))
                    return;
                (__VLS_ctx.previewSession && __VLS_ctx.emit('pick', __VLS_ctx.previewSession.sessionId));
                __VLS_ctx.closePreview();
                ;
            }
        };
        __VLS_71.slots.default;
        (__VLS_ctx.t('chat.search.open'));
        var __VLS_71;
    }
    const __VLS_76 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_77 = __VLS_asFunctionalComponent(__VLS_76, new __VLS_76({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_78 = __VLS_77({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_77));
    let __VLS_80;
    let __VLS_81;
    let __VLS_82;
    const __VLS_83 = {
        onClick: (__VLS_ctx.closePreview)
    };
    __VLS_79.slots.default;
    (__VLS_ctx.t('chat.search.previewClose'));
    var __VLS_79;
}
var __VLS_55;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['input']} */ ;
/** @type {__VLS_StyleScopedClasses['input-bordered']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-4']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-96']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['pr-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:border-primary']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/30']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['font-semibold']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-100']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-start']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xl']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-30']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['min-w-0']} */ ;
/** @type {__VLS_StyleScopedClasses['font-medium']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-warning/15']} */ ;
/** @type {__VLS_StyleScopedClasses['text-warning']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-warning/30']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['line-clamp-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-1']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-96']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['pr-1']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-[10px]']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['tracking-wide']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            MarkdownView: MarkdownView,
            VAlert: VAlert,
            VButton: VButton,
            VCheckbox: VCheckbox,
            VEmptyState: VEmptyState,
            VModal: VModal,
            VSelect: VSelect,
            t: t,
            emit: emit,
            open: open,
            query: query,
            scope: scope,
            includeArchived: includeArchived,
            hits: hits,
            loading: loading,
            error: error,
            searched: searched,
            previewSessionId: previewSessionId,
            previewSession: previewSession,
            previewMessages: previewMessages,
            previewLoading: previewLoading,
            previewError: previewError,
            metadataHits: metadataHits,
            contentHits: contentHits,
            runSearch: runSearch,
            close: close,
            onModalChange: onModalChange,
            pick: pick,
            showPreview: showPreview,
            closePreview: closePreview,
            SCOPE_OPTIONS: SCOPE_OPTIONS,
            sessionTitle: sessionTitle,
            isArchived: isArchived,
            previewTimestamp: previewTimestamp,
            inputRef: inputRef,
        };
    },
    __typeEmits: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=SessionSearchModal.vue.js.map