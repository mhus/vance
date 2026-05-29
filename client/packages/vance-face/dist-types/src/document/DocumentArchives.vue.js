import { computed, ref, watch } from 'vue';
import { VAlert, VButton, VModal } from '@/components';
import { useDocumentArchives } from '@/composables/useDocumentArchives';
const props = defineProps();
const emit = defineEmits();
const archives = useDocumentArchives();
const expanded = ref(false);
// Two-step destructive actions — same posture as the document-delete
// confirmation modal in DocumentApp.vue.
const confirmDelete = ref(null);
const confirmRestore = ref(null);
const acting = ref(false);
watch(() => props.document?.id, async (newId) => {
    if (!newId) {
        archives.items.value = [];
        archives.clearPreview();
        expanded.value = false;
        return;
    }
    await archives.load(newId);
}, { immediate: true });
const count = computed(() => archives.items.value.length);
// Surface the count to the parent so it can render a badge or
// counter without owning the archive state. Initial mount fires once
// with the current length (typically 0 before {@link load} resolves);
// subsequent emits cover load results, delete and restore.
watch(count, (n) => emit('update:count', n), { immediate: true });
function formatDate(ms) {
    return new Date(ms).toLocaleString();
}
async function openPreview(archiveId) {
    if (!props.document)
        return;
    await archives.open(props.document.id, archiveId);
}
async function deleteArchive() {
    if (!props.document || !confirmDelete.value)
        return;
    acting.value = true;
    try {
        const ok = await archives.remove(props.document.id, confirmDelete.value);
        if (ok)
            confirmDelete.value = null;
    }
    finally {
        acting.value = false;
    }
}
async function restoreArchive() {
    if (!props.document || !confirmRestore.value)
        return;
    acting.value = true;
    try {
        const restored = await archives.restore(props.document.id, confirmRestore.value);
        if (restored) {
            confirmRestore.value = null;
            archives.clearPreview();
            emit('restored', restored);
        }
    }
    finally {
        acting.value = false;
    }
}
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mt-3 border border-base-300 rounded-md overflow-hidden" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (...[$event]) => {
            __VLS_ctx.expanded = !__VLS_ctx.expanded;
        } },
    type: "button",
    ...{ class: "w-full flex items-center justify-between px-3 py-2 bg-base-200 text-xs uppercase opacity-70 hover:opacity-100 transition cursor-pointer" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({});
(__VLS_ctx.$t('documents.archives.heading'));
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "ml-2 font-mono normal-case opacity-100" },
});
(__VLS_ctx.count);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    'aria-hidden': "true",
});
(__VLS_ctx.expanded ? '▾' : '▸');
if (__VLS_ctx.expanded) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "p-3 flex flex-col gap-3" },
    });
    if (__VLS_ctx.archives.error.value) {
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
        (__VLS_ctx.archives.error.value);
        var __VLS_3;
    }
    if (!__VLS_ctx.archives.loading.value && __VLS_ctx.count === 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm italic opacity-60" },
        });
        (__VLS_ctx.$t('documents.archives.empty'));
    }
    if (__VLS_ctx.count > 0) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.ul, __VLS_intrinsicElements.ul)({
            ...{ class: "flex flex-col divide-y divide-base-300 max-h-80 overflow-y-auto" },
        });
        for (const [archive] of __VLS_getVForSourceType((__VLS_ctx.archives.items.value))) {
            __VLS_asFunctionalElement(__VLS_intrinsicElements.li, __VLS_intrinsicElements.li)({
                key: (archive.id),
                ...{ class: "py-2 flex items-center justify-between gap-3" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
                ...{ onClick: (...[$event]) => {
                        if (!(__VLS_ctx.expanded))
                            return;
                        if (!(__VLS_ctx.count > 0))
                            return;
                        __VLS_ctx.openPreview(archive.id);
                    } },
                type: "button",
                ...{ class: "flex-1 text-left flex flex-col gap-0.5 hover:underline cursor-pointer" },
            });
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-sm font-mono" },
            });
            (__VLS_ctx.formatDate(archive.archivedAtMs));
            __VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
                ...{ class: "text-xs opacity-70 truncate" },
            });
            (archive.path);
            if (archive.size) {
                (archive.size);
            }
            if (!archive.inline) {
                (__VLS_ctx.$t('documents.archives.storageBacked'));
            }
            __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
                ...{ class: "flex gap-1 shrink-0" },
            });
            const __VLS_4 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "ghost",
                disabled: (__VLS_ctx.acting),
            }));
            const __VLS_6 = __VLS_5({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "ghost",
                disabled: (__VLS_ctx.acting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_5));
            let __VLS_8;
            let __VLS_9;
            let __VLS_10;
            const __VLS_11 = {
                onClick: (...[$event]) => {
                    if (!(__VLS_ctx.expanded))
                        return;
                    if (!(__VLS_ctx.count > 0))
                        return;
                    __VLS_ctx.confirmRestore = archive.id;
                }
            };
            __VLS_7.slots.default;
            (__VLS_ctx.$t('documents.archives.restore'));
            var __VLS_7;
            const __VLS_12 = {}.VButton;
            /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
            // @ts-ignore
            const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "ghost",
                disabled: (__VLS_ctx.acting),
            }));
            const __VLS_14 = __VLS_13({
                ...{ 'onClick': {} },
                size: "sm",
                variant: "ghost",
                disabled: (__VLS_ctx.acting),
            }, ...__VLS_functionalComponentArgsRest(__VLS_13));
            let __VLS_16;
            let __VLS_17;
            let __VLS_18;
            const __VLS_19 = {
                onClick: (...[$event]) => {
                    if (!(__VLS_ctx.expanded))
                        return;
                    if (!(__VLS_ctx.count > 0))
                        return;
                    __VLS_ctx.confirmDelete = archive.id;
                }
            };
            __VLS_15.slots.default;
            (__VLS_ctx.$t('documents.archives.delete'));
            var __VLS_15;
        }
    }
}
const __VLS_20 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.archives.preview.value !== null),
}));
const __VLS_22 = __VLS_21({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.archives.preview.value !== null),
}, ...__VLS_functionalComponentArgsRest(__VLS_21));
let __VLS_24;
let __VLS_25;
let __VLS_26;
const __VLS_27 = {
    'onUpdate:modelValue': ((v) => v || __VLS_ctx.archives.clearPreview())
};
__VLS_23.slots.default;
{
    const { title: __VLS_thisSlot } = __VLS_23.slots;
    (__VLS_ctx.$t('documents.archives.previewTitle', {
        when: __VLS_ctx.archives.preview.value ? __VLS_ctx.formatDate(__VLS_ctx.archives.preview.value.archivedAtMs) : '',
    }));
}
if (__VLS_ctx.archives.preview.value) {
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "flex flex-col gap-2 max-h-[60vh] overflow-y-auto" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "text-xs opacity-70 font-mono" },
    });
    (__VLS_ctx.archives.preview.value.path);
    if (__VLS_ctx.archives.preview.value.mimeType) {
        (__VLS_ctx.archives.preview.value.mimeType);
    }
    if (__VLS_ctx.archives.preview.value.inline && __VLS_ctx.archives.preview.value.inlineText !== undefined) {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.pre, __VLS_intrinsicElements.pre)({
            ...{ class: "text-xs whitespace-pre-wrap font-mono bg-base-200 p-2 rounded-md" },
        });
        (__VLS_ctx.archives.preview.value.inlineText);
    }
    else {
        __VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
            ...{ class: "text-sm italic opacity-60" },
        });
        (__VLS_ctx.$t('documents.archives.previewBinary'));
    }
}
{
    const { footer: __VLS_thisSlot } = __VLS_23.slots;
    const __VLS_28 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
        ...{ 'onClick': {} },
        variant: "ghost",
    }));
    const __VLS_30 = __VLS_29({
        ...{ 'onClick': {} },
        variant: "ghost",
    }, ...__VLS_functionalComponentArgsRest(__VLS_29));
    let __VLS_32;
    let __VLS_33;
    let __VLS_34;
    const __VLS_35 = {
        onClick: (...[$event]) => {
            __VLS_ctx.archives.clearPreview();
        }
    };
    __VLS_31.slots.default;
    (__VLS_ctx.$t('documents.archives.close'));
    var __VLS_31;
}
var __VLS_23;
const __VLS_36 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_37 = __VLS_asFunctionalComponent(__VLS_36, new __VLS_36({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.confirmRestore !== null),
}));
const __VLS_38 = __VLS_37({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.confirmRestore !== null),
}, ...__VLS_functionalComponentArgsRest(__VLS_37));
let __VLS_40;
let __VLS_41;
let __VLS_42;
const __VLS_43 = {
    'onUpdate:modelValue': ((v) => v || (__VLS_ctx.confirmRestore = null))
};
__VLS_39.slots.default;
{
    const { title: __VLS_thisSlot } = __VLS_39.slots;
    (__VLS_ctx.$t('documents.archives.confirmRestoreTitle'));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm" },
});
(__VLS_ctx.$t('documents.archives.confirmRestoreBody'));
{
    const { footer: __VLS_thisSlot } = __VLS_39.slots;
    const __VLS_44 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_45 = __VLS_asFunctionalComponent(__VLS_44, new __VLS_44({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.acting),
    }));
    const __VLS_46 = __VLS_45({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.acting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_45));
    let __VLS_48;
    let __VLS_49;
    let __VLS_50;
    const __VLS_51 = {
        onClick: (...[$event]) => {
            __VLS_ctx.confirmRestore = null;
        }
    };
    __VLS_47.slots.default;
    (__VLS_ctx.$t('documents.archives.cancel'));
    var __VLS_47;
    const __VLS_52 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_53 = __VLS_asFunctionalComponent(__VLS_52, new __VLS_52({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.acting),
    }));
    const __VLS_54 = __VLS_53({
        ...{ 'onClick': {} },
        variant: "primary",
        loading: (__VLS_ctx.acting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_53));
    let __VLS_56;
    let __VLS_57;
    let __VLS_58;
    const __VLS_59 = {
        onClick: (__VLS_ctx.restoreArchive)
    };
    __VLS_55.slots.default;
    (__VLS_ctx.$t('documents.archives.restore'));
    var __VLS_55;
}
var __VLS_39;
const __VLS_60 = {}.VModal;
/** @type {[typeof __VLS_components.VModal, typeof __VLS_components.VModal, ]} */ ;
// @ts-ignore
const __VLS_61 = __VLS_asFunctionalComponent(__VLS_60, new __VLS_60({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.confirmDelete !== null),
}));
const __VLS_62 = __VLS_61({
    ...{ 'onUpdate:modelValue': {} },
    modelValue: (__VLS_ctx.confirmDelete !== null),
}, ...__VLS_functionalComponentArgsRest(__VLS_61));
let __VLS_64;
let __VLS_65;
let __VLS_66;
const __VLS_67 = {
    'onUpdate:modelValue': ((v) => v || (__VLS_ctx.confirmDelete = null))
};
__VLS_63.slots.default;
{
    const { title: __VLS_thisSlot } = __VLS_63.slots;
    (__VLS_ctx.$t('documents.archives.confirmDeleteTitle'));
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.p, __VLS_intrinsicElements.p)({
    ...{ class: "text-sm" },
});
(__VLS_ctx.$t('documents.archives.confirmDeleteBody'));
{
    const { footer: __VLS_thisSlot } = __VLS_63.slots;
    const __VLS_68 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_69 = __VLS_asFunctionalComponent(__VLS_68, new __VLS_68({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.acting),
    }));
    const __VLS_70 = __VLS_69({
        ...{ 'onClick': {} },
        variant: "ghost",
        disabled: (__VLS_ctx.acting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_69));
    let __VLS_72;
    let __VLS_73;
    let __VLS_74;
    const __VLS_75 = {
        onClick: (...[$event]) => {
            __VLS_ctx.confirmDelete = null;
        }
    };
    __VLS_71.slots.default;
    (__VLS_ctx.$t('documents.archives.cancel'));
    var __VLS_71;
    const __VLS_76 = {}.VButton;
    /** @type {[typeof __VLS_components.VButton, typeof __VLS_components.VButton, ]} */ ;
    // @ts-ignore
    const __VLS_77 = __VLS_asFunctionalComponent(__VLS_76, new __VLS_76({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.acting),
    }));
    const __VLS_78 = __VLS_77({
        ...{ 'onClick': {} },
        variant: "danger",
        loading: (__VLS_ctx.acting),
    }, ...__VLS_functionalComponentArgsRest(__VLS_77));
    let __VLS_80;
    let __VLS_81;
    let __VLS_82;
    const __VLS_83 = {
        onClick: (__VLS_ctx.deleteArchive)
    };
    __VLS_79.slots.default;
    (__VLS_ctx.$t('documents.archives.delete'));
    var __VLS_79;
}
var __VLS_63;
/** @type {__VLS_StyleScopedClasses['mt-3']} */ ;
/** @type {__VLS_StyleScopedClasses['border']} */ ;
/** @type {__VLS_StyleScopedClasses['border-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-hidden']} */ ;
/** @type {__VLS_StyleScopedClasses['w-full']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['px-3']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['uppercase']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['transition']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['ml-2']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['normal-case']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-100']} */ ;
/** @type {__VLS_StyleScopedClasses['p-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-y']} */ ;
/** @type {__VLS_StyleScopedClasses['divide-base-300']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-80']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['py-2']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['items-center']} */ ;
/** @type {__VLS_StyleScopedClasses['justify-between']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-3']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-1']} */ ;
/** @type {__VLS_StyleScopedClasses['text-left']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-0.5']} */ ;
/** @type {__VLS_StyleScopedClasses['hover:underline']} */ ;
/** @type {__VLS_StyleScopedClasses['cursor-pointer']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['truncate']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-1']} */ ;
/** @type {__VLS_StyleScopedClasses['shrink-0']} */ ;
/** @type {__VLS_StyleScopedClasses['flex']} */ ;
/** @type {__VLS_StyleScopedClasses['flex-col']} */ ;
/** @type {__VLS_StyleScopedClasses['gap-2']} */ ;
/** @type {__VLS_StyleScopedClasses['max-h-[60vh]']} */ ;
/** @type {__VLS_StyleScopedClasses['overflow-y-auto']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-70']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['text-xs']} */ ;
/** @type {__VLS_StyleScopedClasses['whitespace-pre-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['font-mono']} */ ;
/** @type {__VLS_StyleScopedClasses['bg-base-200']} */ ;
/** @type {__VLS_StyleScopedClasses['p-2']} */ ;
/** @type {__VLS_StyleScopedClasses['rounded-md']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['italic']} */ ;
/** @type {__VLS_StyleScopedClasses['opacity-60']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
/** @type {__VLS_StyleScopedClasses['text-sm']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            VAlert: VAlert,
            VButton: VButton,
            VModal: VModal,
            archives: archives,
            expanded: expanded,
            confirmDelete: confirmDelete,
            confirmRestore: confirmRestore,
            acting: acting,
            count: count,
            formatDate: formatDate,
            openPreview: openPreview,
            deleteArchive: deleteArchive,
            restoreArchive: restoreArchive,
        };
    },
    __typeEmits: {},
    __typeProps: {},
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
    __typeEmits: {},
    __typeProps: {},
});
; /* PartiallyEnd: #4569/main.vue */
//# sourceMappingURL=DocumentArchives.vue.js.map