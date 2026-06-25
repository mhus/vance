function store() {
    let s = globalThis.__VANCE_RUNNER_REGISTRY__;
    if (!s) {
        s = new Map();
        globalThis.__VANCE_RUNNER_REGISTRY__ = s;
    }
    return s;
}
/**
 * Register a Run adapter. Idempotent — registering the same id again
 * replaces the previous entry (handy for HMR + addon re-load).
 */
export function registerRunner(adapter) {
    store().set(adapter.id, adapter);
}
/**
 * Resolve a Run adapter by its stable id.
 */
export function resolveRunner(id) {
    return store().get(id);
}
/**
 * First adapter whose matcher accepts the given document.
 * Iteration order is insertion order — host built-ins register before
 * addons, so a built-in wins ties.
 */
export function resolveRunAdapter(doc) {
    for (const adapter of store().values()) {
        if (adapter.matches(doc))
            return adapter;
    }
    return undefined;
}
/**
 * Snapshot of all currently-registered runners in insertion order.
 */
export function listRunners() {
    return [...store().values()];
}
//# sourceMappingURL=index.js.map