<script setup lang="ts">
import { computed } from 'vue';

/**
 * Visual marker for a document — picks a glyph from {@code kind} (set
 * in the front-matter of yaml/json/md files), the {@code mimeType},
 * or the path's extension, in that priority. Falls back to a generic
 * page glyph when nothing matches.
 *
 * Rendered as a Unicode glyph rather than an SVG icon set so we
 * don't pull in another runtime dependency. The glyphs render with
 * native emoji on every modern browser.
 */
interface Props {
  path?: string | null;
  mimeType?: string | null;
  /**
   * Document kind from the front matter (e.g. {@code list},
   * {@code mindmap}). Lower-case; unknown values fall through to the
   * mime/extension lookup.
   */
  kind?: string | null;
}
const props = defineProps<Props>();

/** kind → glyph. Lower-case match; unknown returns null. */
function iconForKind(kind: string | null | undefined): string | null {
  if (!kind) return null;
  switch (kind.toLowerCase()) {
    case 'list': return '📋';
    case 'text': return '📝';
    case 'mindmap': return '🧠';
    case 'graph': return '🕸️';
    case 'sheet': return '📊';
    case 'data': return '🗃️';
    case 'records': return '📇';
    case 'schema': return '🧬';
    default: return null;
  }
}

/** mime type prefix/exact match → glyph. */
function iconForMime(mime: string | null | undefined): string | null {
  if (!mime) return null;
  const m = mime.toLowerCase();
  if (m === 'application/pdf') return '📕';
  if (m === 'text/markdown') return '📝';
  if (m === 'application/json') return '🧾';
  if (m === 'application/yaml' || m === 'application/x-yaml' || m === 'text/yaml') return '🧾';
  if (m === 'application/xml' || m === 'text/xml') return '🧾';
  if (m === 'text/html') return '🌐';
  if (m === 'text/css') return '🎨';
  if (m === 'application/sql') return '🗄️';
  if (m.startsWith('image/')) return '🖼️';
  if (m.startsWith('video/')) return '🎬';
  if (m.startsWith('audio/')) return '🎵';
  if (m.startsWith('text/')) return '📄';
  if (m === 'application/zip' || m === 'application/x-tar' || m === 'application/gzip') return '📦';
  if (m.startsWith('application/javascript')
      || m.startsWith('application/typescript')
      || m === 'text/x-python'
      || m === 'application/x-sh'
      || m === 'text/x-r'
      || m === 'text/x-java-source') return '⌨️';
  return null;
}

/** Map well-known extensions to a glyph; complementary to mime. */
function iconForExtension(path: string | null | undefined): string | null {
  if (!path) return null;
  const dot = path.lastIndexOf('.');
  if (dot < 0 || dot === path.length - 1) return null;
  const ext = path.slice(dot + 1).toLowerCase();
  switch (ext) {
    case 'md':
    case 'markdown':
      return '📝';
    case 'json':
    case 'yaml':
    case 'yml':
    case 'xml':
    case 'toml':
      return '🧾';
    case 'pdf': return '📕';
    case 'png':
    case 'jpg':
    case 'jpeg':
    case 'gif':
    case 'webp':
    case 'svg':
    case 'bmp':
      return '🖼️';
    case 'mp4':
    case 'webm':
    case 'mov':
    case 'mkv':
      return '🎬';
    case 'mp3':
    case 'wav':
    case 'flac':
    case 'ogg':
      return '🎵';
    case 'zip':
    case 'tar':
    case 'gz':
    case '7z':
    case 'rar':
      return '📦';
    case 'js':
    case 'mjs':
    case 'ts':
    case 'tsx':
    case 'jsx':
    case 'py':
    case 'sh':
    case 'java':
    case 'kt':
    case 'go':
    case 'rs':
    case 'c':
    case 'cpp':
    case 'h':
    case 'r':
      return '⌨️';
    case 'html':
    case 'htm':
      return '🌐';
    case 'css':
    case 'scss':
    case 'sass':
      return '🎨';
    case 'sql':
      return '🗄️';
    case 'csv':
    case 'tsv':
      return '📊';
    case 'txt':
    case 'log':
      return '📄';
    default:
      return null;
  }
}

const glyph = computed<string>(() => {
  return iconForKind(props.kind)
    ?? iconForMime(props.mimeType)
    ?? iconForExtension(props.path)
    ?? '📄';
});

/**
 * Tooltip describing the resolution path — useful hover info for a
 * dense list, but never the only thing the user sees (the glyph
 * itself carries the meaning).
 */
const tooltip = computed<string>(() => {
  const parts: string[] = [];
  if (props.kind) parts.push(`kind: ${props.kind}`);
  if (props.mimeType) parts.push(props.mimeType);
  return parts.join(' · ');
});
</script>

<template>
  <span
    class="doc-icon"
    :title="tooltip || undefined"
    aria-hidden="true"
  >{{ glyph }}</span>
</template>

<style scoped>
.doc-icon {
  display: inline-block;
  font-size: 1.05rem;
  line-height: 1;
  width: 1.4em;
  text-align: center;
  flex-shrink: 0;
}
</style>
