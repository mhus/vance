import { ref, watch } from 'vue';
const VIEW_EDIT_KEY = 'editor:viewEditMode';
function load() {
    try {
        return sessionStorage.getItem(VIEW_EDIT_KEY) === 'edit' ? 'edit' : 'view';
    }
    catch {
        return 'view';
    }
}
const state = ref(load());
watch(state, (v) => {
    try {
        sessionStorage.setItem(VIEW_EDIT_KEY, v);
    }
    catch {
        /* sessionStorage unavailable — quota / private mode */
    }
});
export function useViewEditMode() {
    return state;
}
//# sourceMappingURL=useViewEditMode.js.map