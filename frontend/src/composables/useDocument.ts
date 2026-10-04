import { onBeforeUnmount, ref, shallowRef } from "vue";
import { processPdf, type Field } from "../api/pdf";
export function useDocument() {
  const file = shallowRef<File | null>(null),
    password = ref(""),
    busy = ref(false),
    error = ref(""),
    result = shallowRef<unknown>(null);
  const completed = ref(false),
    download = shallowRef<{ url: string; name: string } | null>(null);
  let controller: AbortController | null = null,
    revision = 0;
  function clear() {
    revision++;
    controller?.abort();
    controller = null;
    busy.value = false;
    error.value = "";
    result.value = null;
    completed.value = false;
    if (download.value) URL.revokeObjectURL(download.value.url);
    download.value = null;
  }
  function select(value: File) {
    clear();
    password.value = "";
    file.value = null;
    if (value.size > 50 * 1024 * 1024) {
      error.value = "PDF 文件不能超过 50 MiB";
      return;
    }
    if (!value.name.toLowerCase().endsWith(".pdf")) {
      error.value = "请选择 PDF 文件";
      return;
    }
    file.value = value;
  }
  async function execute(endpoint: string, fields: Record<string, Field> = {}) {
    if (!file.value || busy.value) return;
    const retained = endpoint === "tables/csv" ? result.value : null;
    clear();
    const current = revision;
    controller = new AbortController();
    busy.value = true;
    try {
      const response = await processPdf(
        endpoint,
        file.value,
        { ...fields, password: password.value },
        controller.signal,
      );
      if (current !== revision) return;
      result.value = response.data ?? retained;
      if (response.download)
        download.value = {
          url: URL.createObjectURL(response.download.blob),
          name: response.download.name,
        };
      completed.value = true;
    } catch (reason) {
      if (
        current === revision &&
        !(reason instanceof DOMException && reason.name === "AbortError")
      )
        error.value = reason instanceof Error ? reason.message : "请求失败";
    } finally {
      if (current === revision) busy.value = false;
    }
  }
  onBeforeUnmount(clear);
  return {
    file,
    password,
    busy,
    error,
    result,
    completed,
    download,
    select,
    clear,
    execute,
  };
}
