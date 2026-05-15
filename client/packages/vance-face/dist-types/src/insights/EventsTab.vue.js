import { computed, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VButton, VCard, VEmptyState, VTextarea, } from '@/components';
import { useEvents } from '@/composables/useEvents';
const props = defineProps();
const { t } = useI18n();
const state = useEvents();
// Currently selected event name. Detail panel populates from
// state.current — loadOne is called when the user clicks an item.
const selectedName = ref(null);
/**
 * JSON payload editor content. Free text — we validate on submit so
 * users can edit live without flicker. Empty string = no payload at
 * all (the `params.payload` key is omitted server-side).
 */
const payloadText = ref('');
/** Result of the last trigger attempt — banner stays until next click. */
const triggerError = ref(null);
watch(() => props.projectId, (next) => {
    selectedName.value = null;
    state.clearCurrent();
    state.clearLastResult();
    triggerError.value = null;
    payloadText.value = '';
    if (next) {
        void state.loadProject(next);
    }
    else {
        state.events.value = [];
    }
}, { immediate: true });
async function selectEvent(name) {
    if (!props.projectId)
        return;
    selectedName.value = name;
    state.clearLastResult();
    triggerError.value = null;
    payloadText.value = '';
    await state.loadOne(props.projectId, name);
}
// Java enums arrive over the wire as their string name (Jackson default);
// the generated TS enum types them as numeric, but at runtime they are
// strings. Compare via the stringified value to stay correct in both
// the TS type system and the actual runtime.
function sourceLabel(source) {
    return String(source) === 'PROJECT' ? 'project' : '_vance';
}
function sourceClass(source) {
    return String(source) === 'PROJECT'
        ? 'badge-source badge-source--project'
        : 'badge-source badge-source--vance';
}
function methodsLabel(methods) {
    if (!methods || methods.length === 0)
        return 'GET, POST';
    return methods.join(', ');
}
const detail = computed(() => state.current.value);
const triggerDisabled = computed(() => {
    if (!props.projectId)
        return true;
    if (!detail.value)
        return true;
    if (!detail.value.enabled)
        return true;
    return state.busy.value;
});
const triggerDisabledHint = computed(() => {
    if (!detail.value)
        return null;
    if (!detail.value.enabled)
        return t('insights.events.disabledHint');
    return null;
});
async function onTrigger() {
    if (!props.projectId || !detail.value)
        return;
    triggerError.value = null;
    // Parse the payload text into an object/null. Empty → no payload.
    let payload = null;
    const raw = payloadText.value.trim();
    if (raw.length > 0) {
        try {
            payload = JSON.parse(raw);
        }
        catch (e) {
            triggerError.value = t('insights.events.payloadJsonError', {
                error: e instanceof Error ? e.message : String(e),
            });
            return;
        }
    }
    try {
        await state.trigger(props.projectId, detail.value.name, payload);
    }
    catch (e) {
        triggerError.value =
            e instanceof Error ? e.message : t('insights.events.triggerGenericError');
    }
}
function sortedEvents() {
    return [...state.events.value].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''));
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['event-row']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "flex flex-col gap-3 p-2" },
});
if (!__VLS_ctx.projectId) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "opacity-60 text-sm" },
    });
    (__VLS_ctx.$t('insights.events.pickProject'));
}
else {
    if (__VLS_ctx.state.error.value) {
        const __VLS_0 = {}.VAlert;
        /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
        // @ts-ignore
        const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
            variant: "error",
        }));
        const __VLS_2 = __VLS_1({
            variant: "error",
        }, ...__VLS_functionalComponentArgsRest(__VLS_1));
        __VLS_3.slots.default;
        __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
        (__VLS_ctx.state.error.value);
        var __VLS_3;
    }
    if (__VLS_ctx.state.loading.value) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "text-sm opacity-60" },
        });
        (__VLS_ctx.$t('insights.events.loading'));
    }
    else if (__VLS_ctx.state.events.value.length === 0) {
        const __VLS_4 = {}.VEmptyState;
        /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
        // @ts-ignore
        const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
            headline: (__VLS_ctx.$t('insights.events.emptyHeadline')),
            body: (__VLS_ctx.$t('insights.events.emptyBody')),
        }));
        const __VLS_6 = __VLS_5({
            headline: (__VLS_ctx.$t('insights.events.emptyHeadline')),
            body: (__VLS_ctx.$t('insights.events.emptyBody')),
        }, ...__VLS_functionalComponentArgsRest(__VLS_5));
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "grid grid-cols-12 gap-3" },
        });
        __VLS_asFunctionalElement(__VLS_intrinsicElements.nav, __VLS_intrinsicElements.nav)({
            ...{ class: "col-span-5 flex flex-col gap-1" },
        });
        for (const [ev] of __VLS_getVForSourceType((__VLS_ctx.sortedEvents()))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!!(!__VLS_ctx.projectId))
                            return;
                        if (!!(__VLS_ctx.state.loading.value))
                            return;
                        if (!!(__VLS_ctx.state.events.value.length === 0))
                            return;
                        __VLS_ctx.selectEvent(ev.name);
                    } },
                key: (ev.name),
                type: "button",
                ...{ class: "event-row" },
                ...{ class: ({
                        'event-row--active': __VLS_ctx.selectedName === ev.name,
                        'event-row--disabled': !ev.enabled,
                    }) },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center justify-between gap-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "font-mono text-sm truncate" },
            });
            (ev.name);
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: (__VLS_ctx.sourceClass(ev.source)) },
                ...{ class: "text-xs" },
            });
            (__VLS_ctx.sourceLabel(ev.source));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "text-xs opacity-60 truncate mt-0.5" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "opacity-80" },
            });
            (ev.workflow ?? '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "ml-2" },
            });
            (__VLS_ctx.methodsLabel(ev.methods));
            if (ev.authConfigured) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "ml-2" },
                });
            }
            if (!ev.enabled) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "ml-2 opacity-80" },
                });
            }
            if (ev.description) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "text-xs opacity-60 truncate mt-0.5" },
                    title: (ev.description),
                });
                (ev.description);
            }
        }
        __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
            ...{ class: "col-span-7 flex flex-col gap-3" },
        });
        if (!__VLS_ctx.detail) {
            const __VLS_8 = {}.VEmptyState;
            /** @type {[typeof __VLS_components.VEmptyState, ]} */ ;
            // @ts-ignore
            const __VLS_9 = __VLS_asFunctionalComponent(__VLS_8, new __VLS_8({
                headline: (__VLS_ctx.$t('insights.events.selectHeadline')),
                body: (__VLS_ctx.$t('insights.events.selectBody')),
            }));
            const __VLS_10 = __VLS_9({
                headline: (__VLS_ctx.$t('insights.events.selectHeadline')),
                body: (__VLS_ctx.$t('insights.events.selectBody')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_9));
        }
        else {
            const __VLS_12 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
                title: (__VLS_ctx.detail.name),
            }));
            const __VLS_14 = __VLS_13({
                title: (__VLS_ctx.detail.name),
            }, ...__VLS_functionalComponentArgsRest(__VLS_13));
            __VLS_15.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dl, __VLS_intrinsicElements.dl)({
                ...{ class: "grid grid-cols-3 gap-x-3 gap-y-1 text-sm" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60 col-span-1" },
            });
            (__VLS_ctx.$t('insights.events.detail.workflow'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "col-span-2 font-mono" },
            });
            (__VLS_ctx.detail.workflow ?? '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60 col-span-1" },
            });
            (__VLS_ctx.$t('insights.events.detail.enabled'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "col-span-2" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs px-1.5 py-0.5 rounded" },
                ...{ class: (__VLS_ctx.detail.enabled ? 'badge-open' : 'badge-closed') },
            });
            (__VLS_ctx.detail.enabled ? 'enabled' : 'disabled');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60 col-span-1" },
            });
            (__VLS_ctx.$t('insights.events.detail.methods'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "col-span-2" },
            });
            (__VLS_ctx.methodsLabel(__VLS_ctx.detail.methods));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60 col-span-1" },
            });
            (__VLS_ctx.$t('insights.events.detail.auth'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "col-span-2" },
            });
            if (__VLS_ctx.detail.authConfigured) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs" },
                });
                (__VLS_ctx.$t('insights.events.detail.bearerConfigured'));
            }
            else {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-70" },
                });
                (__VLS_ctx.$t('insights.events.detail.bearerNone'));
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60 col-span-1" },
            });
            (__VLS_ctx.$t('insights.events.detail.runAs'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "col-span-2" },
            });
            (__VLS_ctx.detail.runAs ?? '—');
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                ...{ class: "opacity-60 col-span-1" },
            });
            (__VLS_ctx.$t('insights.events.detail.source'));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                ...{ class: "col-span-2" },
            });
            (__VLS_ctx.sourceLabel(__VLS_ctx.detail.source));
            if (__VLS_ctx.detail.description) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.dt, __VLS_intrinsicElements.dt)({
                    ...{ class: "opacity-60 col-span-1" },
                });
                (__VLS_ctx.$t('insights.events.detail.description'));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.dd, __VLS_intrinsicElements.dd)({
                    ...{ class: "col-span-2" },
                });
                (__VLS_ctx.detail.description);
            }
            if (__VLS_ctx.detail.params) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({
                    ...{ class: "mt-3" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({
                    ...{ class: "text-xs opacity-70 cursor-pointer" },
                });
                (__VLS_ctx.$t('insights.events.detail.staticParams'));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                    ...{ class: "json-block" },
                });
                (JSON.stringify(__VLS_ctx.detail.params, null, 2));
            }
            if (__VLS_ctx.detail.yaml) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.details, __VLS_intrinsicElements.details)({
                    ...{ class: "mt-3" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.summary, __VLS_intrinsicElements.summary)({
                    ...{ class: "text-xs opacity-70 cursor-pointer" },
                });
                (__VLS_ctx.$t('insights.events.detail.rawYaml'));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
                    ...{ class: "json-block" },
                });
                (__VLS_ctx.detail.yaml);
            }
            var __VLS_15;
            const __VLS_16 = {}.VCard;
            /** @type {[typeof __VLS_components.VCard, typeof __VLS_components.VCard, ]} */ ;
            // @ts-ignore
            const __VLS_17 = __VLS_asFunctionalComponent(__VLS_16, new __VLS_16({
                title: (__VLS_ctx.$t('insights.events.trigger.title')),
            }));
            const __VLS_18 = __VLS_17({
                title: (__VLS_ctx.$t('insights.events.trigger.title')),
            }, ...__VLS_functionalComponentArgsRest(__VLS_17));
            __VLS_19.slots.default;
            __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
                ...{ class: "text-xs opacity-70 mb-2" },
            });
            (__VLS_ctx.$t('insights.events.trigger.help'));
            const __VLS_20 = {}.VTextarea;
            /** @type {[typeof __VLS_components.VTextarea, ]} */ ;
            // @ts-ignore
            const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
                modelValue: (__VLS_ctx.payloadText),
                label: (__VLS_ctx.$t('insights.events.trigger.payloadLabel')),
                placeholder: (__VLS_ctx.$t('insights.events.trigger.payloadPlaceholder')),
                rows: (6),
                ...{ class: "font-mono" },
            }));
            const __VLS_22 = __VLS_21({
                modelValue: (__VLS_ctx.payloadText),
                label: (__VLS_ctx.$t('insights.events.trigger.payloadLabel')),
                placeholder: (__VLS_ctx.$t('insights.events.trigger.payloadPlaceholder')),
                rows: (6),
                ...{ class: "font-mono" },
            }, ...__VLS_functionalComponentArgsRest(__VLS_21));
            if (__VLS_ctx.triggerError) {
                const __VLS_24 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_25 = __VLS_asFunctionalComponent(__VLS_24, new __VLS_24({
                    variant: "error",
                    ...{ class: "mt-2" },
                }));
                const __VLS_26 = __VLS_25({
                    variant: "error",
                    ...{ class: "mt-2" },
                }, ...__VLS_functionalComponentArgsRest(__VLS_25));
                __VLS_27.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.triggerError);
                var __VLS_27;
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex items-center gap-2 mt-3" },
            });
            const __VLS_28 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
                ...{ 'onClick': {} },
                disabled: (__VLS_ctx.triggerDisabled),
                loading: (__VLS_ctx.state.busy.value),
                variant: "primary",
            }));
            const __VLS_30 = __VLS_29({
                ...{ 'onClick': {} },
                disabled: (__VLS_ctx.triggerDisabled),
                loading: (__VLS_ctx.state.busy.value),
                variant: "primary",
            }, ...__VLS_functionalComponentArgsRest(__VLS_29));
            let __VLS_32;
            let __VLS_33;
            let __VLS_34;
            const __VLS_35 = {
                onClick: (__VLS_ctx.onTrigger)
            };
            __VLS_31.slots.default;
            (__VLS_ctx.$t('insights.events.trigger.button'));
            var __VLS_31;
            if (__VLS_ctx.triggerDisabledHint) {
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "text-xs opacity-70" },
                });
                (__VLS_ctx.triggerDisabledHint);
            }
            if (__VLS_ctx.state.lastResult.value) {
                const __VLS_36 = {}.VAlert;
                /** @type {[typeof __VLS_components.VAlert, typeof __VLS_components.VAlert, ]} */ ;
                // @ts-ignore
                const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
                    variant: "success",
                    ...{ class: "mt-3" },
                }));
                const __VLS_38 = __VLS_37({
                    variant: "success",
                    ...{ class: "mt-3" },
                }, ...__VLS_functionalComponentArgsRest(__VLS_37));
                __VLS_39.slots.default;
                __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                    ...{ class: "flex flex-col gap-1 text-sm" },
                });
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('insights.events.trigger.spawnedPrefix'));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "font-mono" },
                });
                (__VLS_ctx.state.lastResult.value.workflowName);
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
                (__VLS_ctx.$t('insights.events.trigger.runIdLabel'));
                __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                    ...{ class: "font-mono" },
                });
                (__VLS_ctx.state.lastResult.value.workflowRunId);
                var __VLS_39;
            }
            var __VLS_19;
        }
    }
}
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-12']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-5']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['event-row']} */ ;
/** @type {__VLS_StyleScopedClasses['event-row--active']} */ ;
/** @type {__VLS_StyleScopedClasses['event-row--disabled']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-80']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-7']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['grid']} */ ;
/** @type {__VLS_StyleScopedClasses['grid-cols-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-x-3']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-y-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['px-1.5']} */ ;
/** @type {__VLS_StyleScopedClasses['py-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-1']} */ ;
/** @type {__VLS_StyleScopedClasses['col-span-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['json-block']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['json-block']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mb-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VCard: VCard,
            VEmptyState: VEmptyState,
            VTextarea: VTextarea,
            state: state,
            selectedName: selectedName,
            payloadText: payloadText,
            triggerError: triggerError,
            selectEvent: selectEvent,
            sourceLabel: sourceLabel,
            sourceClass: sourceClass,
            methodsLabel: methodsLabel,
            detail: detail,
            triggerDisabled: triggerDisabled,
            triggerDisabledHint: triggerDisabledHint,
            onTrigger: onTrigger,
            sortedEvents: sortedEvents,
        };
    },
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=EventsTab.vue.js.map