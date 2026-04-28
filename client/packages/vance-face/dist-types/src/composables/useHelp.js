import { ref } from 'vue';
import { brainFetchText } from '@vance/shared';
/**
 * Loads bundled help / documentation files from the brain. The URL
 * pattern is {@code help/{lang}/{path}}; the brain falls back to the
 * {@code en} variant if the language-specific file is missing, and
 * returns 404 only when neither exists.
 *
 * <p>Language is taken from {@link Navigator#language} truncated to
 * the first two characters. Subclasses (e.g. {@code en-US}) collapse
 * to {@code en}, which the brain accepts as the canonical form.
 */
export function useHelp() {
    const content = ref(null);
    const loading = ref(false);
    const error = ref(null);
    function detectLang() {
        const raw = (typeof navigator !== 'undefined' ? navigator.language : 'en') ?? 'en';
        const two = raw.slice(0, 2).toLowerCase();
        return /^[a-z]{2}$/.test(two) ? two : 'en';
    }
    async function load(resourcePath) {
        loading.value = true;
        error.value = null;
        try {
            const lang = detectLang();
            content.value = await brainFetchText(`help/${encodeURIComponent(lang)}/${resourcePath}`);
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load help.';
            content.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    return { content, loading, error, load };
}
//# sourceMappingURL=useHelp.js.map