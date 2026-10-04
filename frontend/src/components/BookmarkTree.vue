<script setup lang="ts">
import type { Bookmark } from "../types/pdf";
defineProps<{ items: Bookmark[] }>();
const emit = defineEmits<{ locate: [page: number] }>();
</script>
<template>
  <ul class="bookmark-list">
    <li v-for="(item, i) in items" :key="i">
      <button
        v-if="item.page"
        class="text-button"
        @click="emit('locate', item.page)"
      >
        {{ item.title }} · {{ item.page }}</button
      ><span v-else>{{ item.title }}</span
      ><span v-if="item.externalUri" class="external-uri">{{
        item.externalUri
      }}</span
      ><BookmarkTree
        v-if="item.children.length"
        :items="item.children"
        @locate="(page) => emit('locate', page)"
      />
    </li>
  </ul>
</template>
