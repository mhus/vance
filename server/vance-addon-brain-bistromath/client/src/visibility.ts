import type { ViewNode } from './generated/bistromath/ViewNode';

/**
 * Whether a widget's `show:` gate lets it through.
 *
 * <p>Its own file because **two** places need the same answer, and they must
 * not disagree. A widget asks about itself; a `tabs` asks about each of its
 * children *before* rendering one, because the open tab is an index and a
 * hidden child would otherwise still occupy a slot — tick a `show:` key on the
 * second tab and every tab after it would shift under the reader's finger.
 *
 * <p>A key, never an expression: the program computes the boolean, the widget
 * reads it. **Unset counts as hidden** — briefly missing is a mistake the
 * reader can see and the author can explain; briefly showing what the document
 * says to hide is not.
 */
export function isVisible(node: ViewNode, lookup: (key: string) => unknown): boolean {
  const key = node.show;
  if (!key) return true;
  const v = lookup(key);
  // '0' and 'false' are strings a YAML document or a text input can produce,
  // and both read as "off" to everyone except JavaScript.
  return Boolean(v) && v !== 'false' && v !== '0';
}
