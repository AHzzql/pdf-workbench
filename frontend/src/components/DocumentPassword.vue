<script setup lang="ts">
import { ref, watch, useId } from "vue";
const props = defineProps<{ modelValue: string; document: File | null }>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const draft = ref(props.modelValue);
const inputId = useId();
watch(
  () => [props.document, props.modelValue],
  () => {
    draft.value = props.modelValue;
  },
);
function confirm() {
  emit("update:modelValue", draft.value);
}
</script>
<template>
  <div class="field">
    <label :for="inputId"
      >文档打开密码 <span class="optional">可选</span></label
    >
    <input
      :id="inputId"
      v-model="draft"
      type="password"
      autocomplete="off"
      placeholder="输入完成后确认"
      @keydown.enter.prevent.stop="confirm"
    />
    <button type="button" :disabled="draft === modelValue" @click="confirm">
      确认密码
    </button>
  </div>
</template>
