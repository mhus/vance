import { ref } from 'vue';
import { brainBaseUrl, getTenantId } from '@vance/shared';
const TEXT_EXTS = new Set([
    'txt', 'log', 'md', 'markdown', 'json', 'yaml', 'yml', 'xml', 'html', 'htm',
    'css', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'py', 'sh', 'bash', 'zsh',
    'java', 'kt', 'rs', 'go', 'rb', 'sql', 'conf', 'cfg', 'ini', 'properties',
    'env', 'toml', 'csv', 'tsv', 'gitignore', 'editorconfig',
]);
const IMAGE_EXTS = new Set(['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp', 'avif']);
const MIME_BY_EXT = {
    md: 'text/markdown', markdown: 'text/markdown',
    json: 'application/json',
    yaml: 'application/yaml', yml: 'application/yaml',
    xml: 'application/xml',
    html: 'text/html', htm: 'text/html',
    css: 'text/css',
    js: 'application/javascript', mjs: 'application/javascript', cjs: 'application/javascript',
    ts: 'application/typescript', tsx: 'application/typescript', jsx: 'application/javascript',
    py: 'text/x-python',
    sh: 'application/x-sh', bash: 'application/x-sh', zsh: 'application/x-sh',
    java: 'text/x-java-source',
    sql: 'application/sql',
    toml: 'application/toml',
    txt: 'text/plain', log: 'text/plain', conf: 'text/plain', cfg: 'text/plain',
    ini: 'text/plain', properties: 'text/plain', env: 'text/plain',
    csv: 'text/csv', tsv: 'text/tab-separated-values',
};
function ext(name) {
    const i = name.lastIndexOf('.');
    return i < 0 ? '' : name.slice(i + 1).toLowerCase();
}
function pickMode(name) {
    const e = ext(name);
    if (e === 'md' || e === 'markdown')
        return 'markdown';
    if (TEXT_EXTS.has(e))
        return 'text';
    if (IMAGE_EXTS.has(e))
        return 'image';
    return 'binary';
}
function pickMime(name) {
    return MIME_BY_EXT[ext(name)] ?? 'text/plain';
}
export function workspaceFileUrl(projectId, path) {
    const tenant = getTenantId();
    if (!tenant)
        return '';
    const params = new URLSearchParams({ path });
    return `${brainBaseUrl()}/brain/${encodeURIComponent(tenant)}/projects/${encodeURIComponent(projectId)}/workspace/file?${params}`;
}
export function useWorkspaceFile() {
    const result = ref(null);
    const loading = ref(false);
    const error = ref(null);
    async function load(projectId, path, name) {
        loading.value = true;
        error.value = null;
        const mode = pickMode(name);
        const mimeType = pickMime(name);
        const url = workspaceFileUrl(projectId, path);
        try {
            let text = null;
            if (mode === 'text' || mode === 'markdown') {
                const r = await fetch(url, { credentials: 'include' });
                if (!r.ok)
                    throw new Error(`HTTP ${r.status}`);
                text = await r.text();
            }
            result.value = { mode, text, url, mimeType };
        }
        catch (e) {
            error.value = e instanceof Error ? e.message : 'Failed to load file.';
            result.value = null;
        }
        finally {
            loading.value = false;
        }
    }
    function clear() {
        result.value = null;
        error.value = null;
    }
    return { result, loading, error, load, clear };
}
//# sourceMappingURL=useWorkspaceFile.js.map