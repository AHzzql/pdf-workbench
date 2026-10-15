<script setup lang="ts">
defineProps<{ name: string }>();
const paths: Record<string, string> = {
  text: "M6 3h9l4 4v14H6z M14 3v5h5 M9 12h7 M9 16h5",
  tables: "M3 4h18v16H3z M3 9h18 M9 4v16 M3 14h18",
  images: "M3 4h18v16H3z M3 16l5-5 4 4 3-3 6 6 M16 8h.01",
  encrypt: "M5 10h14v11H5z M8 10V6a4 4 0 0 1 8 0v4 M12 14v3",
  signatures: "M14 4a5 5 0 1 1-2 9l-7 7H2v-3l7-7a5 5 0 0 1 5-6z M16 7h.01",
  watermark: "M12 2S5 10 5 15a7 7 0 0 0 14 0c0-5-7-13-7-13z",
  info: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 11v6 M12 7h.01",
  upload: "M12 16V3 M7 8l5-5 5 5 M4 15v6h16v-6",
  arrow: "M5 12h14 M14 7l5 5-5 5",
  check: "M4 12l5 5L20 6",
  outline: "M4 5h2 M10 5h10 M4 12h2 M10 12h10 M4 19h2 M10 19h10",
  idea: "M9 18h6 M10 21h4 M8 14a6 6 0 1 1 8 0c-1 1-1 2-1 2H9s0-1-1-2",
  plus: "M12 5v14 M5 12h14",
  history: "M4 5v5h5 M4 10a8 8 0 1 1 0 5 M12 7v5l3 2",
  expand: "M4 9V4h5 M15 4h5v5 M20 15v5h-5 M9 20H4v-5",
  collapse: "M4 9h5V4 M15 4v5h5 M20 15h-5v5 M9 20v-5H4",
  minimize: "M5 12h14",
  send: "M12 20V4 M6 10l6-6 6 6",
  close: "M6 6l12 12 M6 18L18 6",
  download: "M12 3v13 M7 11l5 5 5-5 M4 17v4h16v-4",
};
</script>
<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.text" />
  </svg>
</template>
