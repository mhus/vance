<script setup lang="ts">
</script>

<template>
  <div class="flex h-full flex-col">
    <router-view v-slot="{ Component }">
      <component :is="Component" />
    </router-view>
  </div>
</template>
