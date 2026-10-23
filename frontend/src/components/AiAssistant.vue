<script setup lang="ts">
import DocumentPassword from "./DocumentPassword.vue";
import { useAccount } from "../composables/useAccount";
import type { Selection } from "../types/pdf";
import { useAiAssistant } from "../composables/useAiAssistant";
import AiAnswer from "./AiAnswer.vue";
import AiSuggestions from "./AiSuggestions.vue";
import AppIcon from "./AppIcon.vue";
import "../assets/ai.css";
const account = useAccount();
const props = defineProps<{ file: File | null; password: string }>();
const emit = defineEmits<{
  locate: [selection: Selection];
  restore: [file: File];
  password: [value: string];
}>();
const {
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
} = useAiAssistant(props, emit);
function onEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  void submit();
}
</script>

<template>
  <Teleport to="body">
    <section
      v-show="opened"
      class="ai-window"
      :class="{ expanded }"
      role="dialog"
      aria-label="PDF 智能助手"
      @keydown.esc.stop="close"
    >
      <header class="ai-header">
        <div class="ai-brand">
          <span class="ai-symbol" aria-hidden="true">✦</span
          ><strong>PDF 智能助手</strong>
        </div>
        <div class="ai-window-actions">
          <button
            aria-label="新对话"
            title="新对话"
            :disabled="working"
            @click="newConversation"
          >
            <AppIcon name="plus" />
          </button>
          <button
            aria-label="历史对话"
            title="历史对话"
            :class="{ selected: historyVisible }"
            @click="showHistory"
          >
            <AppIcon name="history" />
          </button>
          <button
            :aria-label="expanded ? '缩小窗口' : '展开窗口'"
            :title="expanded ? '缩小窗口' : '展开窗口'"
            @click="expanded = !expanded"
          >
            <AppIcon :name="expanded ? 'collapse' : 'expand'" />
          </button>
          <button aria-label="最小化助手" title="最小化" @click="close">
            <AppIcon name="minimize" />
          </button>
          <button aria-label="关闭助手" title="关闭" @click="close">
            <AppIcon name="close" />
          </button>
        </div>
      </header>
      <div class="ai-context">
        <span class="ai-file-mark">PDF</span
        ><span :title="currentName">{{ currentName }}</span
        ><small v-if="document">{{ document.pages }} 页</small>
        <button
          class="ai-outline-toggle"
          :aria-pressed="outlineVisible"
          :disabled="!file"
          @click="prepareOutline"
        >
          <AppIcon name="outline" />目录
        </button>
      </div>
      <div v-if="configured === false" class="ai-notice">
        AI 模型尚未配置，普通文档工具不受影响。
      </div>
      <div v-if="error" class="ai-error" role="alert">
        {{ error
        }}<button aria-label="关闭错误提示" @click="error = ''">×</button>
      </div>
      <template v-if="historyVisible">
        <div class="ai-history-heading">
          <h3>{{ account.user.value ? "账户历史" : "游客历史" }}</h3>
          <span>{{ history.length }} 条</span>
        </div>
        <div class="ai-search">
          <input
            v-model="historyQuery"
            aria-label="搜索历史对话"
            placeholder="搜索对话名称…"
            maxlength="100"
          />
        </div>
        <div class="ai-history-list">
          <p v-if="!visibleHistory.length" class="ai-muted">
            暂无匹配的历史对话
          </p>
          <article
            v-for="c in visibleHistory"
            :key="c.id"
            class="ai-history-item"
          >
            <form
              v-if="renameId === c.id"
              class="ai-rename"
              @submit.prevent="rename(c)"
            >
              <input
                v-model="renameTitle"
                aria-label="会话名称"
                maxlength="80"
                required
              /><button type="submit">保存</button
              ><button type="button" @click="renameId = ''">取消</button>
            </form>
            <template v-else
              ><button
                class="ai-history-open"
                :disabled="busy"
                @click="restore(c)"
              >
                <strong>{{ c.title }}</strong
                ><small>{{
                  new Date(c.updatedAt).toLocaleString("zh-CN")
                }}</small>
              </button>
              <div class="ai-history-controls">
                <button
                  @click="
                    renameId = c.id;
                    renameTitle = c.title;
                  "
                >
                  重命名</button
                ><button @click="deleteId = c.id">删除</button>
              </div></template
            >
            <div v-if="deleteId === c.id" class="ai-delete-confirm">
              <p>删除此会话及全部消息？</p>
              <label
                ><input
                  v-model="deleteDocument"
                  type="checkbox"
                />同时清理未被其他会话引用的 PDF 和索引</label
              >
              <div>
                <button @click="deleteId = ''">取消</button
                ><button class="ai-danger" @click="remove(c)">确认删除</button>
              </div>
            </div>
          </article>
        </div>
      </template>
      <template v-else>
        <div v-if="document?.encrypted" class="ai-password">
          <DocumentPassword
            :key="document?.id"
            :model-value="password"
            :document="file"
            @update:model-value="emit('password', $event)"
          />
        </div>
        <div ref="body" class="ai-body">
          <template v-if="outlineVisible">
            <div class="ai-section-title">
              <h3>文档目录</h3>
              <button @click="outlineVisible = false">返回对话 →</button>
            </div>
            <div v-if="!document" class="ai-empty">
              <span class="ai-empty-icon">≡</span>
              <h3>按章节阅读</h3>
              <p>从当前 PDF 读取书签和标题结构。</p>
              <button
                class="ai-primary"
                :disabled="!file || working || configured === false"
                @click="
                  outlineVisible = false;
                  startWelcome();
                "
              >
                读取文档目录
              </button>
            </div>
            <p v-else-if="!chapters.length" class="ai-muted">
              未发现可靠目录。可以直接提问“分析第 3–8 页”，按页码阅读。
            </p>
            <button
              v-for="c in chapters"
              :key="c.id"
              class="ai-chapter"
              @click="chooseChapter(c)"
            >
              <span>{{ c.title }}</span
              ><small>第 {{ c.page }} 页 ↗</small>
            </button>
          </template>
          <template v-else>
            <section v-if="!messages.length" class="ai-welcome">
              <div class="ai-welcome-heading">
                <span class="ai-symbol">✦</span>
                <h3>你好，一起读懂这份文档</h3>
              </div>
              <p>
                {{
                  file
                    ? working
                      ? "正在阅读文档，整理内容概览…"
                      : "可以直接提问，或选择一个问题开始。"
                    : "在工作台选择一份 PDF，即可开始阅读；也可以打开历史对话继续。"
                }}
              </p>
              <AiSuggestions
                :disabled="!file || working || configured === false"
                @select="submit"
              />
            </section>
            <article
              v-for="m in messages"
              :key="m.id"
              class="ai-message"
              :class="m.role"
            >
              <div
                v-if="m.role === 'assistant'"
                class="ai-message-label"
                :class="{ 'ai-welcome-label': m.mode === 'WELCOME' }"
              >
                <span class="ai-symbol">✦</span
                >{{
                  m.mode === "WELCOME" ? "你好，一起读懂这份文档" : "文档助手"
                }}
              </div>
              <p v-if="m.role === 'user'" class="ai-question">{{ m.text }}</p>
              <AiAnswer v-else :message="m" @locate="locate" />
              <p
                v-if="m.status === 'RUNNING'"
                class="ai-progress"
                role="status"
              >
                <span class="ai-pulse"></span>{{ stage || "正在处理…" }}
              </p>
              <p
                v-if="m.status === 'FAILED' || m.status === 'STOPPED'"
                class="ai-message-status"
              >
                {{
                  m.status === "STOPPED" ? "已停止生成" : m.error || "生成失败"
                }}
              </p>
              <div
                v-if="m.role === 'assistant' && m.status !== 'RUNNING'"
                class="ai-message-actions"
              >
                <button :disabled="working" @click="retry(m)">
                  {{ m.status === "COMPLETED" ? "重新生成" : "重试" }}</button
                ><span v-if="m.citations.length"
                  >{{ m.citations.length }} 处原文引用</span
                >
              </div>
              <div
                v-if="m.id === suggestionMessageId"
                class="ai-welcome-suggestions"
              >
                <p>你可以直接提问，也可以从这里开始：</p>
                <AiSuggestions
                  :disabled="!file || working || configured === false"
                  @select="submit"
                />
              </div>
            </article>
            <aside v-if="activeCitation" class="ai-evidence">
              <header>
                <strong>第 {{ activeCitation.page }} 页 · 原文证据</strong
                ><button aria-label="收起证据" @click="activeCitation = null">
                  ×
                </button>
              </header>
              <p>{{ activeCitation.quote }}</p>
              <button @click="locate(activeCitation)">定位原文 ↗</button>
            </aside>
          </template>
        </div>
        <footer class="ai-compose">
          <div v-if="busy" class="ai-progress" role="status">
            <span class="ai-pulse"></span>{{ stage || "正在读取…" }}
          </div>
          <form @submit.prevent="submit()">
            <textarea
              ref="input"
              v-model="question"
              aria-label="向当前文档提问"
              placeholder="直接提问，例如：总结全文，或分析第 3–8 页"
              maxlength="2000"
              rows="2"
              @keydown.enter.exact="onEnter"
            ></textarea
            ><button
              v-if="running"
              type="button"
              class="ai-send ai-stop"
              aria-label="停止生成"
              @click="stop"
            >
              ■</button
            ><button
              v-else
              type="submit"
              class="ai-send"
              aria-label="发送 AI 请求"
              :disabled="
                busy || !file || configured === false || !question.trim()
              "
            >
              <AppIcon name="send" />
            </button>
          </form>
          <div class="ai-compose-meta">
            <span>{{
              conversation
                ? account.user.value
                  ? "会话已独立保存至当前账户"
                  : "游客会话已保存在本机"
                : "基于当前 PDF 回答"
            }}</span
            ><span>Enter 发送 · Shift+Enter 换行</span>
          </div>
        </footer>
      </template>
    </section>
    <button
      ref="launcher"
      class="ai-launcher"
      :class="{ running: working }"
      aria-label="打开 PDF 智能助手"
      :aria-expanded="opened"
      @click="opened ? close() : open()"
    >
      <span aria-hidden="true">✦</span><i v-if="working"></i>
    </button>
  </Teleport>
</template>
