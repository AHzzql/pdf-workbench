<script setup lang="ts">
import AppIcon from "./AppIcon.vue";

defineProps<{ disabled: boolean }>();
const emit = defineEmits<{ select: [prompt: string] }>();
const prompts = [
  { icon: "text", text: "帮我概括这份文档的主要内容" },
  { icon: "outline", text: "按章节梳理文档结构和重点" },
  { icon: "idea", text: "解释文档中的关键概念" },
];
</script>

<template>
  <div class="ai-starters" role="group" aria-label="推荐问题">
    <button
      v-for="prompt in prompts"
      :key="prompt.text"
      type="button"
      :disabled="disabled"
      @click="emit('select', prompt.text)"
    >
      <AppIcon :name="prompt.icon" class="ai-prompt-icon" />
      <span>{{ prompt.text }}</span>
      <AppIcon name="arrow" class="ai-prompt-arrow" />
    </button>
  </div>
</template>
