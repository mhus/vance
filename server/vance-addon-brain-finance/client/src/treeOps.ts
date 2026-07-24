import type { FinanceNodeDto } from './generated/finance/FinanceNodeDto';
import type { FinanceTreeDto } from './generated/finance/FinanceTreeDto';

export interface Located {
  node: FinanceNodeDto;
  parent: FinanceNodeDto | null; // null = root
  siblings: FinanceNodeDto[] | null; // null = root
  index: number; // -1 for root
}

/** Locate a node by name anywhere in the tree. */
export function locate(tree: FinanceTreeDto, name: string): Located | null {
  const root = tree.root;
  if (!root) return null;
  if (root.name === name) return { node: root, parent: null, siblings: null, index: -1 };
  return locateIn(root, name);
}

function locateIn(parent: FinanceNodeDto, name: string): Located | null {
  const children = parent.children;
  for (let i = 0; i < children.length; i++) {
    if (children[i].name === name) {
      return { node: children[i], parent, siblings: children, index: i };
    }
    const hit = locateIn(children[i], name);
    if (hit) return hit;
  }
  return null;
}

export function collectNames(tree: FinanceTreeDto): Set<string> {
  const names = new Set<string>();
  if (tree.root) walk(tree.root, (n) => names.add(n.name));
  return names;
}

function walk(node: FinanceNodeDto, fn: (n: FinanceNodeDto) => void): void {
  fn(node);
  for (const c of node.children) walk(c, fn);
}

/** Mint a unique node name not colliding with the tree. */
export function nextName(tree: FinanceTreeDto, prefix = 'node'): string {
  const names = collectNames(tree);
  let i = 1;
  while (names.has(`${prefix}-${i}`)) i++;
  return `${prefix}-${i}`;
}

export function newNode(name: string, title: string): FinanceNodeDto {
  return { name, title, sign: 1, values: [], children: [] };
}

/** Add a child under parentName, or set the root when parentName is null. */
export function addChild(tree: FinanceTreeDto, parentName: string | null, node: FinanceNodeDto): void {
  if (parentName === null) {
    tree.root = node;
    return;
  }
  const loc = locate(tree, parentName);
  if (loc) loc.node.children.push(node);
}

export function removeNode(tree: FinanceTreeDto, name: string): void {
  const loc = locate(tree, name);
  if (!loc) return;
  if (loc.parent === null) {
    tree.root = undefined;
  } else if (loc.siblings) {
    loc.siblings.splice(loc.index, 1);
  }
}

export function move(tree: FinanceTreeDto, name: string, delta: number): void {
  const loc = locate(tree, name);
  if (!loc || !loc.siblings) return;
  const target = loc.index + delta;
  if (target < 0 || target >= loc.siblings.length) return;
  const [n] = loc.siblings.splice(loc.index, 1);
  loc.siblings.splice(target, 0, n);
}

/** Make the node a child of its previous sibling. */
export function indent(tree: FinanceTreeDto, name: string): void {
  const loc = locate(tree, name);
  if (!loc || !loc.siblings || loc.index === 0) return;
  const prev = loc.siblings[loc.index - 1];
  const [n] = loc.siblings.splice(loc.index, 1);
  prev.children.push(n);
}

/** Move the node up to be a sibling of its parent (right after it). */
export function outdent(tree: FinanceTreeDto, name: string): void {
  const loc = locate(tree, name);
  if (!loc || !loc.parent) return;
  const parentLoc = locate(tree, loc.parent.name);
  if (!parentLoc || !parentLoc.siblings) return; // parent is root — cannot outdent
  const [n] = loc.parent.children.splice(loc.index, 1);
  parentLoc.siblings.splice(parentLoc.index + 1, 0, n);
}
