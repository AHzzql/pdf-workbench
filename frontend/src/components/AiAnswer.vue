<script setup lang="ts">
import { computed } from "vue";
import type { AiMessage, Citation } from "../types/ai";
const props = defineProps<{ message: AiMessage }>();
const emit = defineEmits<{ locate: [citation: Citation] }>();
const lines = computed(() =>
  props.message.text.split("\n").map((line) => ({
    heading: /^#{1,4}\s/.test(line),
    parts: line
      .replace(/^#{1,4}\s+/, "")
      .split(/(\*\*[^*]+\*\*|\[p\d+-\d+\])/g)
      .filter(Boolean)
      .map((text) => ({
        text,
        bold: text.startsWith("**"),
        citation: props.message.citations.find((c) => `[${c.id}]` === text),
      })),
  })),
);
</script>
<template>
  <div class="ai-answer">
    <p
      v-for="(line, i) in lines"
      :key="i"
      :class="{ 'ai-answer-heading': line.heading }"
    >
      <template v-for="(part, j) in line.parts" :key="j">
        <button
          v-if="part.citation"
          class="ai-cite"
          :aria-label="`查看引用第 ${part.citation.page} 页`"
          @click="emit('locate', part.citation)"
        >
          第 {{ part.citation.page }} 页 ↗
        </button>
        <strong v-else-if="part.bold">{{ part.text.slice(2, -2) }}</strong>
        <span v-else>{{ part.text }}</span>
      </template>
    </p>
  </div>
</template>
