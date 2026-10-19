import { chatSpace } from "./useAccount";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { AI_BASE, aiRequest } from "../api/ai";
import type {
  AiDocument,
  AiMessage,
  Citation,
  Chapter,
  Conversation,
  ReadingMode,
} from "../types/ai";
import type { Selection } from "../types/pdf";
type AssistantEvents = {
  (event: "locate", selection: Selection): void;
  (event: "restore", file: File): void;
  (event: "password", value: string): void;
};
export function useAiAssistant(
  props: Readonly<{ file: File | null; password: string }>,
  emit: AssistantEvents,
) {
  const opened = ref(false),
    expanded = ref(false),
    historyVisible = ref(false),
    outlineVisible = ref(false);
  const question = ref("");
  const document = ref<AiDocument | null>(null),
    conversation = ref<Conversation | null>(null);
  const messages = ref<AiMessage[]>([]),
    chapters = ref<Chapter[]>([]),
    history = ref<Conversation[]>([]);
  const busy = ref(false),
    stage = ref(""),
    error = ref(""),
    configured = ref<boolean | null>(null);
  const historyQuery = ref(""),
    renameId = ref(""),
    renameTitle = ref(""),
    deleteId = ref(""),
    deleteDocument = ref(true);
  const activeCitation = ref<Citation | null>(null),
    body = ref<HTMLElement | null>(null),
    input = ref<HTMLTextAreaElement | null>(null),
    launcher = ref<HTMLButtonElement | null>(null);
  let source: EventSource | null = null,
    revision = 0,
    restoring = false,
    initialized = false,
    autoWelcome = true;
  const running = computed(() =>
    messages.value.find((m) => m.status === "RUNNING"),
  );
  const working = computed(() => busy.value || !!running.value);
  const suggestionMessageId = computed(() =>
    messages.value.some((message) => message.role === "user")
      ? null
      : messages.value.filter((message) => message.mode === "WELCOME").at(-1)
          ?.id,
  );
  const visibleHistory = computed(() =>
    history.value.filter((c) =>
      c.title.toLowerCase().includes(historyQuery.value.toLowerCase()),
    ),
  );
  const currentName = computed(
    () => props.file?.name || document.value?.name || "尚未选择 PDF",
  );

  function lastKey() {
    return chatSpace.value === "guest"
      ? "pdf-ai-conversation"
      : `pdf-ai-conversation:${chatSpace.value}`;
  }
  function saveLast(id: string | null) {
    try {
      if (id) localStorage.setItem(lastKey(), id);
      else localStorage.removeItem(lastKey());
    } catch {
      /* 浏览器禁用存储时仍使用服务端历史。 */
    }
  }
  function lastId() {
    try {
      return localStorage.getItem(lastKey());
    } catch {
      return null;
    }
  }
  async function open() {
    const current = revision;
    opened.value = true;
    if (!initialized) {
      initialized = true;
      try {
        const status = await aiRequest<{ configured: boolean }>("/status");
        if (current !== revision) return;
        configured.value = status.configured;
        await loadHistory();
        if (current !== revision) return;
        const last = history.value.find((c) => c.id === lastId());
        if (!props.file && last) await restore(last);
      } catch (e) {
        if (current === revision) {
          fail(e);
          initialized = false;
        }
      }
    }
    if (current !== revision) return;
    if (
      autoWelcome &&
      props.file &&
      configured.value === true &&
      !messages.value.length &&
      !working.value
    )
      await startWelcome();
    await nextTick();
    if (opened.value) input.value?.focus();
  }
  function close() {
    opened.value = false;
    void nextTick(() => launcher.value?.focus());
  }
  function fail(e: unknown) {
    error.value = e instanceof Error ? e.message : "请求失败，请重试";
  }
  function disconnect() {
    source?.close();
    source = null;
  }
  function reset(preservePointer = false) {
    revision++;
    disconnect();
    conversation.value = null;
    document.value = null;
    messages.value = [];
    chapters.value = [];
    outlineVisible.value = false;
    question.value = "";
    activeCitation.value = null;
    error.value = "";
    busy.value = false;
    stage.value = "";
    if (!preservePointer) saveLast(null);
  }
  watch(
    chatSpace,
    () => {
      autoWelcome = false;
      reset(true);
      history.value = [];
      historyQuery.value = "";
      renameId.value = "";
      deleteId.value = "";
      initialized = false;
      configured.value = null;
      if (opened.value) void open();
    },
    { flush: "sync" },
  );
  watch(
    () => props.file,
    () => {
      if (!restoring) {
        autoWelcome = true;
        reset();
        if (opened.value && configured.value === true) void startWelcome();
      }
    },
  );
  async function loadHistory() {
    const current = revision;
    const result = await aiRequest<Conversation[]>(
      "/conversations/search",
      "POST",
      { query: "" },
    );
    if (current === revision) history.value = result;
  }
  async function showHistory() {
    historyVisible.value = !historyVisible.value;
    outlineVisible.value = false;
    error.value = "";
    if (historyVisible.value)
      try {
        await loadHistory();
      } catch (e) {
        fail(e);
      }
  }
  async function restore(c: Conversation) {
    const current = ++revision;
    disconnect();
    busy.value = true;
    error.value = "";
    try {
      const [doc, list, outline] = await Promise.all([
        aiRequest<AiDocument>(`/documents/${c.documentId}`),
        aiRequest<AiMessage[]>(`/conversations/${c.id}/messages`),
        aiRequest<Chapter[]>(`/documents/${c.documentId}/chapters`),
      ]);
      const response = await fetch(`${AI_BASE}/documents/${doc.id}/file`, {
        cache: "no-store",
        headers: { "X-Chat-Space": chatSpace.value },
      });
      if (!response.ok) throw new Error("关联 PDF 无法读取");
      const blob = await response.blob();
      if (current !== revision) return;
      restoring = true;
      emit("restore", new File([blob], doc.name, { type: "application/pdf" }));
      await nextTick();
      restoring = false;
      document.value = doc;
      conversation.value = c;
      messages.value = list;
      chapters.value = outline;
      activeCitation.value = null;
      outlineVisible.value = false;
      historyVisible.value = false;
      saveLast(c.id);
      if (running.value) subscribe(running.value.id, current);
      await nextTick();
      if (list.some((message) => message.role === "user")) scroll(true);
    } catch (e) {
      if (current === revision) fail(e);
    } finally {
      restoring = false;
      if (current === revision) busy.value = false;
    }
  }
  function scroll(force = false) {
    const el = body.value;
    if (el && (force || el.scrollHeight - el.scrollTop - el.clientHeight < 170))
      el.scrollTop = el.scrollHeight;
  }
  function subscribe(id: string, current: number) {
    disconnect();
    const events = new EventSource(`${AI_BASE}/runs/${id}/events`);
    source = events;
    events.addEventListener("snapshot", (event) => {
      if (current !== revision) {
        events.close();
        return;
      }
      try {
        const snapshot = JSON.parse((event as MessageEvent).data) as {
          message: AiMessage;
          stage: string;
        };
        const index = messages.value.findIndex(
          (m) => m.id === snapshot.message.id,
        );
        if (
          index >= 0 &&
          messages.value[index]!.sequence > snapshot.message.sequence
        )
          return;
        if (index >= 0) messages.value[index] = snapshot.message;
        else messages.value.push(snapshot.message);
        stage.value = snapshot.stage;
        if (snapshot.message.mode !== "WELCOME") void nextTick(() => scroll());
        if (snapshot.message.status !== "RUNNING") {
          events.close();
          if (source === events) source = null;
          void loadHistory().catch(fail);
        }
      } catch {
        error.value = "生成消息无法解析，请从历史对话重新打开";
        events.close();
      }
    });
    events.onerror = () => {
      if (current === revision && running.value) {
        stage.value = "连接中断，正在恢复…";
        void aiRequest(`/runs/${id}`).catch(() => {
          if (current === revision) {
            disconnect();
            error.value = "账户或连接已变化，请重新打开历史对话";
          }
        });
      }
    };
  }
  async function ensureConversation(current: number) {
    const file = props.file;
    if (!file) throw new Error("请先在工作台选择 PDF 文件");
    if (!document.value) {
      stage.value = "正在解析文档…";
      const form = new FormData();
      form.append("file", file);
      form.append("password", props.password);
      const doc = await aiRequest<AiDocument>("/documents", "POST", form);
      if (current !== revision) return false;
      const outline = await aiRequest<Chapter[]>(
        `/documents/${doc.id}/chapters`,
      );
      if (current !== revision) return false;
      document.value = doc;
      chapters.value = outline;
    }
    if (current !== revision) return false;
    if (!conversation.value) {
      const c = await aiRequest<Conversation>("/conversations", "POST", {
        documentId: document.value!.id,
      });
      if (current !== revision) return false;
      conversation.value = c;
      saveLast(c.id);
    }
    return true;
  }
  async function startWelcome() {
    if (!props.file || working.value || messages.value.length) return;
    await send("WELCOME", "");
  }
  async function submit(prompt?: string) {
    const text = (prompt ?? question.value).trim();
    if (!text || working.value) return;
    outlineVisible.value = false;
    await send("AUTO", text);
  }
  async function send(operation: ReadingMode, text: string) {
    const current = revision;
    busy.value = true;
    error.value = "";
    try {
      if (!(await ensureConversation(current))) return;
      const message = await aiRequest<AiMessage>(
        `/conversations/${conversation.value!.id}/messages`,
        "POST",
        {
          requestId: crypto.randomUUID(),
          mode: operation,
          question: text,
          pages: null,
          chapterId: null,
        },
      );
      if (current !== revision) return;
      const list = await aiRequest<AiMessage[]>(
        `/conversations/${conversation.value!.id}/messages`,
      );
      if (current !== revision) return;
      messages.value = list;
      if (
        operation !== "WELCOME" &&
        (question.value.trim() === text || !question.value.trim())
      )
        question.value = "";
      subscribe(message.id, current);
      await nextTick();
      if (operation !== "WELCOME") scroll(true);
    } catch (e) {
      if (current === revision) fail(e);
    } finally {
      if (current === revision) busy.value = false;
    }
  }
  async function prepareOutline() {
    outlineVisible.value = !outlineVisible.value;
    historyVisible.value = false;
    if (!outlineVisible.value || document.value || busy.value || !props.file)
      return;
    const current = revision;
    busy.value = true;
    error.value = "";
    try {
      await ensureConversation(current);
    } catch (e) {
      if (current === revision) fail(e);
    } finally {
      if (current === revision) busy.value = false;
    }
  }
  async function stop() {
    if (!running.value) return;
    const current = revision;
    try {
      const m = await aiRequest<AiMessage>(
        `/runs/${running.value.id}/cancel`,
        "POST",
      );
      if (current !== revision) return;
      const i = messages.value.findIndex((v) => v.id === m.id);
      if (i >= 0) messages.value[i] = m;
      disconnect();
      stage.value = "已停止";
    } catch (e) {
      fail(e);
    }
  }
  async function retry(message: AiMessage) {
    if (working.value) return;
    const current = revision;
    busy.value = true;
    error.value = "";
    try {
      const m = await aiRequest<AiMessage>(
        `/messages/${message.id}/retry`,
        "POST",
        { requestId: crypto.randomUUID() },
      );
      if (current !== revision) return;
      messages.value.push(m);
      subscribe(m.id, current);
    } catch (e) {
      if (current === revision) fail(e);
    } finally {
      if (current === revision) busy.value = false;
    }
  }
  function locate(citation: Citation) {
    if (citation.documentId !== document.value?.id) return;
    activeCitation.value = citation;
    emit("locate", { page: citation.page, bounds: citation.bounds });
  }
  function chooseChapter(chapter: Chapter) {
    outlineVisible.value = false;
    question.value = `分析「${chapter.title}」这一章的重点与约束（起始于 PDF 第 ${chapter.page} 页）。`;
    void nextTick(() => input.value?.focus());
    emit("locate", { page: chapter.page, bounds: [] });
  }
  function newConversation() {
    if (working.value) return;
    revision++;
    disconnect();
    conversation.value = null;
    messages.value = [];
    question.value = "";
    activeCitation.value = null;
    historyVisible.value = false;
    error.value = "";
    saveLast(null);
    outlineVisible.value = false;
    if (configured.value === true) void startWelcome();
  }
  async function rename(c: Conversation) {
    const current = revision;
    try {
      const renamed = await aiRequest<Conversation>(
        `/conversations/${c.id}`,
        "PATCH",
        { title: renameTitle.value },
      );
      if (current !== revision) return;
      if (conversation.value?.id === c.id) conversation.value = renamed;
      renameId.value = "";
      await loadHistory();
    } catch (e) {
      fail(e);
    }
  }
  async function remove(c: Conversation) {
    const current = revision;
    try {
      await aiRequest(`/conversations/${c.id}`, "DELETE", {
        deleteDocument: deleteDocument.value,
      });
      if (current !== revision) return;
      if (conversation.value?.id === c.id) reset();
      deleteId.value = "";
      await loadHistory();
    } catch (e) {
      fail(e);
    }
  }
  onBeforeUnmount(disconnect);

  return {
    opened,
    expanded,
    historyVisible,
    outlineVisible,
    question,
    document,
    conversation,
    messages,
    chapters,
    history,
    busy,
    stage,
    error,
    configured,
    historyQuery,
    renameId,
    renameTitle,
    deleteId,
    deleteDocument,
    activeCitation,
    body,
    input,
    launcher,
    running,
    working,
    suggestionMessageId,
    visibleHistory,
    currentName,
    open,
    close,
    showHistory,
    restore,
    submit,
    startWelcome,
    prepareOutline,
    stop,
    retry,
    locate,
    chooseChapter,
    newConversation,
    rename,
    remove,
  };
}
