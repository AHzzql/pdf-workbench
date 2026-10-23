<script setup lang="ts">
import { ref } from "vue";
defineOptions({ inheritAttrs: false });
defineProps<{ modelValue: string }>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const visible = ref(false);
</script>
<template>
  <span class="password-control">
    <input
      v-bind="$attrs"
      :value="modelValue"
      :type="visible ? 'text' : 'password'"
      @input="
        emit('update:modelValue', ($event.target as HTMLInputElement).value)
      "
    />
    <button
      type="button"
      :aria-pressed="visible"
      :aria-label="visible ? '隐藏密码' : '显示密码'"
      @click="visible = !visible"
    >
      {{ visible ? "隐藏" : "显示" }}
    </button>
  </span>
</template>
<style scoped>
.password-control {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.password-control input {
  flex: 1;
  min-width: 0;
}
.password-control button {
  flex: none;
  padding: 8px;
}
</style>
