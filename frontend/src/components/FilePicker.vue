<script setup lang="ts">
import { ref, watch } from "vue";
const props = defineProps<{
  modelValue: File | null;
  accept: string;
  label: string;
  required?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [value: File | null] }>();
const input = ref<HTMLInputElement | null>(null);
watch(
  () => props.modelValue,
  (value) => {
    if (!value && input.value) input.value.value = "";
  },
);
function change(event: Event) {
  emit(
    "update:modelValue",
    (event.target as HTMLInputElement).files?.[0] || null,
  );
}
</script>
<template>
  <span class="file-picker"
    ><span>选择文件</span
    ><span class="picked-name">{{ modelValue?.name || "未选择文件" }}</span
    ><input
      ref="input"
      type="file"
      :aria-label="label"
      :accept="accept"
      :required="required"
      @change="change"
  /></span>
</template>
<style scoped>
.file-picker {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid #dfe5ee;
  border-radius: 5px;
  padding: 8px;
  margin-top: 7px;
  min-height: 39px;
  font-size: 11px;
}
.file-picker > span:first-child {
  background: #edf3fd;
  border-radius: 3px;
  color: #3263a9;
  padding: 4px 7px;
  white-space: nowrap;
}
.picked-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #929caf;
  font-weight: 400;
}
.file-picker input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
  margin: 0 !important;
  padding: 0 !important;
}
.file-picker:focus-within {
  outline: 3px solid #8bb6ff;
  outline-offset: 2px;
}
</style>
