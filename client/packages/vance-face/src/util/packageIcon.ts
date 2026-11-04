import type { IconName } from '@vance/components';

/**
 * The icon of a package folder, from the application type its manifest
 * declares. Types without a dedicated glyph — and a manifest that names none —
 * get the generic application icon: a package is recognisable as one even
 * when the tree does not know what is inside.
 */
export function packageIcon(app: string | null | undefined): IconName {
  switch (app?.toLowerCase()) {
    case 'kanban': return 'kanban';
    case 'calendar': return 'calendar';
    case 'links': return 'link';
    default: return 'app';
  }
}
