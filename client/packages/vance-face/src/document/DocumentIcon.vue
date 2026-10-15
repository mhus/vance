<script setup lang="ts">
import { computed } from 'vue';
import { VIcon, type IconName } from '@vance/components';

/**
 * Visual marker for a document — picks a glyph from {@code kind} (set
 * in the front-matter of yaml/json/md files), the {@code mimeType},
 * or the path's extension, in that priority. Falls back to a generic
 * page glyph when nothing matches.
 *
 * Rendered as a Lucide icon from the UI vocabulary (`kind-*` / `file-*`
 * in @vance/components icons.ts, corporate-design.md §5) — one colour,
 * one stroke, the same on every platform.
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

/** kind → icon. Lower-case match; unknown returns null. */
function iconForKind(kind: string | null | undefined): IconName | null {
  if (!kind) return null;
  switch (kind.toLowerCase()) {
    case 'list': return 'kind-list';
    case 'tree': return 'kind-tree';
    case 'text': return 'kind-text';
    case 'mindmap': return 'kind-mindmap';
    case 'graph': return 'kind-graph';
    case 'sheet': return 'kind-sheet';
    case 'slides': return 'kind-slides';
    case 'data': return 'kind-data';
    case 'records': return 'kind-records';
    case 'qrcode': return 'kind-qrcode';
    case 'questions': return 'kind-questions';
    case 'schema': return 'kind-schema';
    default: return null;
  }
}

/** mime type prefix/exact match → icon. */
function iconForMime(mime: string | null | undefined): IconName | null {
  if (!mime) return null;
  const m = mime.toLowerCase();
  if (m === 'application/pdf') return 'file-pdf';
  if (m === 'text/markdown') return 'kind-text';
  if (m === 'application/json') return 'file-structured';
  if (m === 'application/yaml' || m === 'application/x-yaml' || m === 'text/yaml') return 'file-structured';
  if (m === 'application/xml' || m === 'text/xml') return 'file-structured';
  if (m === 'text/html') return 'file-web';
  if (m === 'text/css') return 'file-style';
  if (m === 'application/sql') return 'file-database';
  if (m.startsWith('image/')) return 'file-image';
  if (m.startsWith('video/')) return 'file-video';
  if (m.startsWith('audio/')) return 'file-audio';
  if (m.startsWith('text/')) return 'document';
  if (m === 'application/zip' || m === 'application/x-tar' || m === 'application/gzip') return 'file-archive';
  if (m.startsWith('application/javascript')
      || m.startsWith('application/typescript')
      || m === 'text/x-python'
      || m === 'application/x-sh'
      || m === 'text/x-r'
      || m === 'text/x-java-source') return 'file-code';
  return null;
}

/** Map well-known extensions to an icon; complementary to mime. */
function iconForExtension(path: string | null | undefined): IconName | null {
  if (!path) return null;
  const dot = path.lastIndexOf('.');
  if (dot < 0 || dot === path.length - 1) return null;
  const ext = path.slice(dot + 1).toLowerCase();
  switch (ext) {
    case 'md':
    case 'markdown':
      return 'kind-text';
    case 'json':
    case 'yaml':
    case 'yml':
    case 'xml':
    case 'toml':
      return 'file-structured';
    case 'pdf': return 'file-pdf';
    case 'png':
    case 'jpg':
    case 'jpeg':
    case 'gif':
    case 'webp':
    case 'svg':
    case 'bmp':
      return 'file-image';
    case 'mp4':
    case 'webm':
    case 'mov':
    case 'mkv':
      return 'file-video';
    case 'mp3':
    case 'wav':
    case 'flac':
    case 'ogg':
      return 'file-audio';
    case 'zip':
    case 'tar':
    case 'gz':
    case '7z':
    case 'rar':
      return 'file-archive';
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
      return 'file-code';
    case 'html':
    case 'htm':
      return 'file-web';
    case 'css':
    case 'scss':
    case 'sass':
      return 'file-style';
    case 'sql':
      return 'file-database';
    case 'csv':
    case 'tsv':
      return 'kind-sheet';
    case 'txt':
    case 'log':
      return 'document';
    default:
      return null;
  }
}

const icon = computed<IconName>(() => {
  return iconForKind(props.kind)
    ?? iconForMime(props.mimeType)
    ?? iconForExtension(props.path)
    ?? 'document';
});

/**
 * Tooltip describing the resolution path — useful hover info for a
 * dense list, but never the only thing the user sees (the icon
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
  <span class="doc-icon" :title="tooltip || undefined" aria-hidden="true">
    <VIcon :name="icon" />
  </span>
</template>

<style scoped>
.doc-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  flex-shrink: 0;
  color: var(--v-label-secondary);
}
</style>
