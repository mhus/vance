import { ref } from 'vue';
import { brainFetch, BrainWebSocket, getTenantId } from '@vance/shared';
const JS_MIMES = new Set([
    'text/javascript',
    'application/javascript',
    'application/x-javascript',
    'application/x-mjs',
    'text/x-javascript',
]);
const JS_EXTS = ['.js', '.mjs', '.mjsh'];
function isJsDocument(doc) {
    const m = (doc.mimeType ?? '').toLowerCase().trim();
    if (m && JS_MIMES.has(m))
        return true;
    const p = doc.path.toLowerCase();
    return JS_EXTS.some((ext) => p.endsWith(ext));
}
/**
 * JS-execution adapter: drives the brain's {@code scripts/execute}
 * endpoint, subscribes to {@code script-execution-*} push events,
 * and falls back to short-interval polling when WS subscription
 * fails (e.g. the worker terminated before subscribe landed).
 *
 * <p>Drives the run inline below the editor (no modal). Same backend
 * endpoints the deleted Script-Cortex modal used — only the UI
 * shape changed.
 */
export const jsRunner = {
    id: 'js',
    label: 'Run JS',
    matches: isJsDocument,
    async execute({ doc, projectId, args }) {
        const state = ref('starting');
        const logLines = ref([]);
        const result = ref(null);
        const error = ref(null);
        const durationMs = ref(null);
        let executionId = null;
        let ws = null;
        let wsSubscribed = false;
        let pollTimer = null;
        let detached = false;
        function stopPolling() {
            if (pollTimer != null) {
                window.clearTimeout(pollTimer);
                pollTimer = null;
            }
        }
        function bindWs(w, expectedId) {
            w.on('script-execution-started', (d) => {
                if (d.executionId !== expectedId)
                    return;
                state.value = 'running';
            });
            w.on('script-execution-log', (d) => {
                if (d.executionId !== expectedId)
                    return;
                logLines.value.push(`[${d.stream}] ${d.logLine ?? ''}`);
                // Trim aggressively so a runaway script doesn't OOM the tab —
                // 5k lines is the same cap ScriptCortex uses.
                if (logLines.value.length > 5000) {
                    logLines.value = logLines.value.slice(-4000);
                }
            });
            w.on('script-execution-finished', (d) => {
                if (d.executionId !== expectedId)
                    return;
                state.value = 'finished';
                result.value = d.resultValue ?? null;
                durationMs.value = d.durationMs ?? null;
                stopPolling();
            });
            w.on('script-execution-failed', (d) => {
                if (d.executionId !== expectedId)
                    return;
                state.value = 'failed';
                error.value = d.errorMessage ?? 'Execution failed';
                durationMs.value = d.durationMs ?? null;
                stopPolling();
            });
            w.on('script-execution-cancelled', (d) => {
                if (d.executionId !== expectedId)
                    return;
                state.value = 'cancelled';
                durationMs.value = d.durationMs ?? null;
                stopPolling();
            });
        }
        function startPolling() {
            stopPolling();
            const tick = async () => {
                if (detached || !executionId)
                    return;
                try {
                    const snap = await brainFetch('GET', `scripts/executions/${executionId}`);
                    const snapState = snap.state;
                    if (snapState !== 'running') {
                        state.value = snapState;
                        result.value = snap.resultValue ?? null;
                        error.value = snap.errorMessage ?? null;
                        durationMs.value = snap.durationMs ?? null;
                    }
                    if (!wsSubscribed && snap.logBuffer && snap.logBuffer.length > 0) {
                        // No WS stream — use the snapshot's buffered log as the
                        // authoritative source so we don't lose lines that
                        // arrived before subscribe landed.
                        logLines.value = snap.logBuffer.map((l) => `[buffered] ${l}`);
                    }
                    if (snapState !== 'running') {
                        stopPolling();
                        return;
                    }
                }
                catch (e) {
                    // 404 = retention-evicted (5 min); stop polling, leave the
                    // current state — the user can still see the last log.
                    console.warn('[cortex/js-runner] poll error:', e);
                    stopPolling();
                    return;
                }
                pollTimer = window.setTimeout(tick, 1500);
            };
            pollTimer = window.setTimeout(tick, 800);
        }
        // Kick off the execution. Errors here mean "couldn't even start" —
        // surface as terminal failure state so the shell renders a banner.
        let resp;
        try {
            resp = await brainFetch('POST', `scripts/execute?projectId=${encodeURIComponent(projectId)}`, {
                body: {
                    scriptId: doc.id,
                    args,
                    sourceName: doc.path,
                },
            });
        }
        catch (e) {
            state.value = 'failed';
            error.value = e instanceof Error ? e.message : 'Execute failed';
            return makeHandle();
        }
        executionId = resp.executionId;
        state.value = 'running';
        // Best-effort WS subscribe. The polling backstop below covers the
        // failure case + the race where the worker terminated before our
        // subscribe landed.
        try {
            ws = await BrainWebSocket.connect({
                tenant: getTenantId() ?? '',
                profile: 'web',
                clientVersion: '0.1.0',
            });
            bindWs(ws, resp.executionId);
            await ws.send('script-execution-subscribe', { executionId: resp.executionId });
            wsSubscribed = true;
        }
        catch (e) {
            console.warn('[cortex/js-runner] WS subscribe failed, polling only:', e);
            wsSubscribed = false;
        }
        startPolling();
        function makeHandle() {
            return {
                get id() { return executionId ?? ''; },
                state,
                logLines,
                result,
                error,
                durationMs,
                async cancel() {
                    if (!executionId)
                        return;
                    try {
                        await brainFetch('POST', `scripts/executions/${executionId}/cancel`);
                    }
                    catch (e) {
                        error.value = e instanceof Error ? e.message : 'Cancel failed';
                    }
                },
                detach() {
                    if (detached)
                        return;
                    detached = true;
                    stopPolling();
                    if (ws) {
                        ws.close();
                        ws = null;
                    }
                },
            };
        }
        return makeHandle();
    },
};
//# sourceMappingURL=jsRunner.js.map