import { type Ref } from 'vue';
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
export declare function useHelp(): {
    content: Ref<string | null>;
    loading: Ref<boolean>;
    error: Ref<string | null>;
    load: (resourcePath: string) => Promise<void>;
};
//# sourceMappingURL=useHelp.d.ts.map