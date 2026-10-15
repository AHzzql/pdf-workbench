<script setup lang="ts">
import type { Selection } from "../types/pdf";
import { useAiAssistant } from "../composables/useAiAssistant";
import AiAnswer from "./AiAnswer.vue";
import "../assets/ai.css";
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
  tab,
  mode,
  question,
  pages,
  chapterId,
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
  visibleHistory,
  currentName,
  open,
  close,
  showHistory,
  restore,
  submit,
  prepareOutline,
  stop,
  retry,
  locate,
  chooseChapter,
  newConversation,
  rename,
  remove,
} = useAiAssistant(props, emit);
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
            :disabled="busy"
            @click="newConversation"
          >
            ＋
          </button>
          <button
            aria-label="历史对话"
            title="历史对话"
            :class="{ selected: historyVisible }"
            @click="showHistory"
          >
            ◷
          </button>
          <button
            :aria-label="expanded ? '缩小窗口' : '展开窗口'"
            :title="expanded ? '缩小窗口' : '展开窗口'"
            @click="expanded = !expanded"
          >
            □
          </button>
          <button aria-label="最小化助手" title="最小化" @click="close">
            −
          </button>
          <button aria-label="关闭助手" title="关闭" @click="close">×</button>
        </div>
      </header>
      <div class="ai-context">
        <span class="ai-file-mark">PDF</span
        ><span :title="currentName">{{ currentName }}</span
        ><small v-if="document">{{ document.pages }} 页</small>
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
          <h3>历史对话</h3>
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
        <nav class="ai-tabs" aria-label="AI 阅读工具">
          <button
            v-for="item in [
              { id: 'ASK', title: '问答' },
              { id: 'SUMMARY', title: '摘要' },
              { id: 'CHAPTER', title: '章节' },
              { id: 'OUTLINE', title: '目录' },
            ]"
            :key="item.id"
            :class="{ active: tab === item.id }"
            @click="tab = item.id as typeof tab"
          >
            {{ item.title }}
          </button>
        </nav>
        <div v-if="tab === 'SUMMARY'" class="ai-options">
          <button :class="{ active: mode === 'BRIEF' }" @click="mode = 'BRIEF'">
            简要摘要</button
          ><button
            :class="{ active: mode === 'DETAILED' }"
            @click="mode = 'DETAILED'"
          >
            详细摘要</button
          ><button
            :class="{ active: mode === 'CHAPTER' }"
            @click="
              mode = 'CHAPTER';
              tab = 'CHAPTER';
            "
          >
            章节摘要
          </button>
        </div>
        <div v-if="tab === 'CHAPTER'" class="ai-scope">
          <select v-model="chapterId" aria-label="选择章节">
            <option value="">选择章节</option>
            <option v-for="c in chapters" :key="c.id" :value="c.id">
              {{ c.title }} · 第 {{ c.page }} 页
            </option></select
          ><input
            v-model="pages"
            aria-label="AI 页码范围"
            placeholder="或输入页码，如 3-8"
            maxlength="100"
          /><button
            v-if="!document"
            :disabled="!file || working"
            @click="prepareOutline"
          >
            读取目录
          </button>
        </div>
        <div v-if="document?.encrypted" class="ai-password">
          <label
            >PDF 打开密码<input
              type="password"
              autocomplete="off"
              placeholder="恢复原文预览时输入"
              @input="
                emit('password', ($event.target as HTMLInputElement).value)
              "
          /></label>
        </div>
        <div ref="body" class="ai-body">
          <template v-if="tab === 'OUTLINE'">
            <div class="ai-section-title">
              <h3>文档目录</h3>
              <small v-if="chapters.length">{{ chapters[0]?.source }}</small>
            </div>
            <div v-if="!document" class="ai-empty">
              <span class="ai-empty-icon">≡</span>
              <h3>按章节阅读</h3>
              <p>从当前 PDF 读取书签和标题结构。</p>
              <button
                class="ai-primary"
                :disabled="!file || working || configured === false"
                @click="prepareOutline"
              >
                读取文档目录
              </button>
            </div>
            <p v-else-if="!chapters.length" class="ai-muted">
              未发现可靠目录，可在「章节」中指定页码范围。
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
            <div v-if="!messages.length" class="ai-empty">
              <span class="ai-empty-icon">✦</span>
              <h3>
                {{
                  tab === "SUMMARY"
                    ? "读懂全文，把握重点"
                    : tab === "CHAPTER"
                      ? "聚焦一章，深入理解"
                      : "让每个答案，都有出处"
                }}
              </h3>
              <p>
                {{
                  file
                    ? "已关联工作台当前 PDF。提问后开始解析，回答可回到原文核对。"
                    : "请先在工作台选择一份 PDF，或打开历史对话继续阅读。"
                }}
              </p>
              <div class="ai-starters">
                <button
                  :disabled="!file || working"
                  @click="
                    question = '这份文档的核心观点是什么？';
                    submit('ASK');
                  "
                >
                  概括核心观点 ↗</button
                ><button :disabled="!file || working" @click="submit('BRIEF')">
                  生成简要摘要 ↗
                </button>
              </div>
              <small
                >首次 AI
                阅读会将文档和对话保存在本机；相关文本会发送给已配置的模型服务。</small
              >
            </div>
            <article
              v-for="m in messages"
              :key="m.id"
              class="ai-message"
              :class="m.role"
            >
              <div v-if="m.role === 'assistant'" class="ai-message-label">
                <span class="ai-symbol">✦</span>文档助手
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
              :placeholder="
                tab === 'SUMMARY'
                  ? '可直接生成摘要，或补充关注点…'
                  : tab === 'CHAPTER'
                    ? '输入章节问题，或留空生成章节摘要…'
                    : '向当前文档提问…'
              "
              maxlength="2000"
              rows="2"
              @keydown.enter.exact.prevent="submit()"
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
              :disabled="busy || !file || configured === false"
            >
              ↑
            </button>
          </form>
          <div class="ai-compose-meta">
            <span>{{
              conversation ? "会话已保存在本机" : "基于当前 PDF 回答"
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
