<script setup lang="ts">
/**
 * Recursive read-only node for {@link TreeView}'s inline / embedded
 * render path. One `<li>` per TreeItem, recurses through children
 * as nested `<ul>`. No edit affordances, no drag handles — just the
 * structure.
 */
import type { TreeItem } from './treeItemsCodec';

defineOptions({ name: 'TreeViewReadNode' });

defineProps<{
  item: TreeItem;
}>();
</script>

<template>
  <li class="tree-read__node">
    <span v-if="item.text" class="tree-read__text">{{ item.text }}</span>
    <span v-else class="tree-read__empty-text">—</span>
    <ul v-if="item.children && item.children.length > 0" class="tree-read__children">
      <TreeViewReadNode
        v-for="(child, i) in item.children"
        :key="i"
        :item="child"
      />
    </ul>
  </li>
</template>

<style scoped>
.tree-read__node {
  margin: 0.1em 0;
}
.tree-read__text {
  word-break: break-word;
}
.tree-read__empty-text {
  opacity: 0.5;
}
.tree-read__children {
  list-style: disc;
  margin: 0.1em 0 0.1em 1.2em;
  padding: 0;
}
.tree-read__children > .tree-read__node {
  list-style: circle;
}
</style>
