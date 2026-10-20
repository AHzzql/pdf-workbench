<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type {
  TextResult,
  TableData,
  SignatureResult,
  DocumentInfo,
  SearchMatch,
  Selection,
} from "../types/pdf";
import BookmarkTree from "./BookmarkTree.vue";
const props = defineProps<{
  tool: string;
  mode: string;
  result: unknown;
  completed: boolean;
  busy: boolean;
}>();
const emit = defineEmits<{
  locate: [selection: Selection];
  csv: [index: number];
}>();
const tab = ref("text");
watch(
  () => props.result,
  () => (tab.value = "text"),
);
const text = computed(() => props.result as TextResult | null),
  tables = computed(() => props.result as TableData[] | null);
const matches = computed(() => props.result as SearchMatch[] | null),
  signatures = computed(() => props.result as SignatureResult[] | null);
const info = computed(() => props.result as DocumentInfo | null);
const states: Record<string, string> = {
  VALID: "有效",
  INVALID: "无效",
  UNKNOWN: "未知",
  EXPIRED: "已过期",
  NOT_YET_VALID: "尚未生效",
  TRUSTED: "可信",
  UNTRUSTED: "不可信",
  NOT_EVALUATED: "未评估",
};
</script>
<template>
  <div class="result-heading">
    <h2>处理结果</h2>
    <span v-if="completed" class="status-dot">处理完成</span>
  </div>
  <div v-if="busy" class="result-placeholder">
    <span class="spinner"></span>正在处理文档…
  </div>
  <div v-else-if="!completed" class="result-placeholder">
    结果将在处理完成后显示
  </div>
  <template v-else-if="result !== null">
    <template v-if="tool === 'text' && mode !== 'search' && text">
      <div class="tabs">
        <button :class="{ active: tab === 'text' }" @click="tab = 'text'">
          文本</button
        ><button
          :class="{ active: tab === 'properties' }"
          @click="tab = 'properties'"
        >
          文本块属性
        </button>
      </div>
      <p v-if="!text.pages.length" class="empty-note">所选范围没有页面</p>
      <section v-for="page in text.pages" :key="page.page" class="page-result">
        <h3>
          第 {{ page.page }} 页 <small>{{ page.blocks.length }} 个文本块</small>
        </h3>
        <template v-if="tab === 'text'"
          ><p v-if="!page.text" class="empty-note">本页无可提取文本</p>
          <pre v-else class="extracted-text">{{ page.text }}</pre>
        </template>
        <div v-else class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>文本</th>
                <th>字体 / 字号</th>
                <th>颜色</th>
                <th>位置 X / Y</th>
                <th>尺寸 宽 / 高</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(block, i) in page.blocks" :key="i">
                <td>
                  <button
                    class="text-button"
                    @click="
                      emit('locate', {
                        page: page.page,
                        bounds: [block.bounds],
                      })
                    "
                  >
                    {{ block.text }}
                  </button>
                </td>
                <td>
                  {{ block.font
                  }}<small>{{ block.fontSize.toFixed(1) }} pt</small>
                </td>
                <td>
                  {{
                    block.fillColor?.rgb ||
                    (block.fillColor
                      ? `${block.fillColor.colorSpace} (${block.fillColor.components.join(", ")})`
                      : "未知")
                  }}
                </td>
                <td>
                  {{ block.bounds.x.toFixed(1) }},
                  {{ block.bounds.y.toFixed(1) }}
                </td>
                <td>
                  {{ block.bounds.width.toFixed(1) }} ×
                  {{ block.bounds.height.toFixed(1) }} pt
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p
          v-for="message in page.diagnostics"
          :key="message"
          class="empty-note"
        >
          {{ message }}
        </p>
      </section>
    </template>
    <template v-else-if="tool === 'text' && mode === 'search' && matches"
      ><p class="result-count">{{ matches.length }} 处匹配</p>
      <button
        v-for="(match, i) in matches"
        :key="i"
        class="match-card"
        @click="emit('locate', match)"
      >
        <small>第 {{ match.page }} 页 · {{ match.start }}–{{ match.end }}</small
        >{{ match.text }}
      </button></template
    >
    <template v-else-if="tool === 'tables' && tables"
      ><p v-if="!tables.length" class="empty-note">未检测到规则网格表格</p>
      <section
        v-for="(table, index) in tables"
        :key="index"
        class="page-result"
      >
        <div class="result-heading">
          <h3>表格 {{ index + 1 }} · 第 {{ table.page }} 页</h3>
          <button class="text-button" @click="emit('csv', index)">
            导出 CSV
          </button>
        </div>
        <button
          class="text-button"
          @click="emit('locate', { page: table.page, bounds: [table.bounds] })"
        >
          定位表格
        </button>
        <div class="table-scroll">
          <table>
            <tbody>
              <tr v-for="(row, i) in table.rows" :key="i">
                <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section></template
    >
    <template v-else-if="tool === 'signatures' && signatures"
      ><p v-if="!signatures.length" class="empty-note">文档没有数字签名</p>
      <section
        v-for="(signature, index) in signatures"
        :key="index"
        class="signature-card"
      >
        <h3>签名 {{ index + 1 }}</h3>
        <p class="signer">
          {{ signature.signer || signature.name || "未知签署者" }}
        </p>
        <dl class="signature-facts">
          <dt>签名完整性</dt>
          <dd
            :class="{
              positive: signature.integrity === 'VALID',
              negative: signature.integrity === 'INVALID',
            }"
          >
            {{
              signature.integrity === "UNKNOWN"
                ? "无法验证"
                : states[signature.integrity]
            }}
          </dd>
          <dt>签后修改</dt>
          <dd :class="{ negative: signature.hasLaterRevision === true }">
            {{
              signature.hasLaterRevision === null
                ? "无法判定后续修订"
                : signature.hasLaterRevision
                  ? "存在后续修订"
                  : "未发现后续修订"
            }}
          </dd>
          <dt>证书有效期</dt>
          <dd>{{ states[signature.certificateValidity] }}</dd>
          <dt>证书信任</dt>
          <dd>{{ states[signature.trustStatus] }}</dd>
          <dt>算法</dt>
          <dd>{{ signature.algorithm || "未知" }}</dd>
          <dt>吊销检查</dt>
          <dd>{{ signature.revocationChecked ? "已检查" : "未检查" }}</dd>
        </dl>
        <details v-if="signature.certificate">
          <summary>证书详情</summary>
          <dl class="metadata">
            <dt>主体</dt>
            <dd>{{ signature.certificate.subject }}</dd>
            <dt>签发者</dt>
            <dd>{{ signature.certificate.issuer }}</dd>
            <dt>序列号</dt>
            <dd>{{ signature.certificate.serialNumber }}</dd>
            <dt>生效时间</dt>
            <dd>{{ signature.certificate.notBefore }}</dd>
            <dt>到期时间</dt>
            <dd>{{ signature.certificate.notAfter }}</dd>
          </dl>
        </details>
        <p v-if="signature.reason" class="error">{{ signature.reason }}</p>
      </section></template
    >
    <template v-else-if="tool === 'info' && info"
      ><p class="result-count">总页数 {{ info.pageCount }}</p>
      <h3>标准元数据</h3>
      <dl class="metadata">
        <template v-for="(value, key) in info.standardMetadata" :key="key"
          ><dt>{{ key }}</dt>
          <dd>{{ value || "—" }}</dd></template
        >
      </dl>
      <h3>自定义元数据</h3>
      <p v-if="!Object.keys(info.customMetadata).length" class="empty-note">
        无自定义元数据
      </p>
      <dl class="metadata">
        <template v-for="(value, key) in info.customMetadata" :key="key"
          ><dt>{{ key }}</dt>
          <dd>{{ value }}</dd></template
        >
      </dl>
      <h3>书签</h3>
      <BookmarkTree
        :items="info.bookmarks"
        @locate="(page) => emit('locate', { page, bounds: [] })"
      />
      <p v-if="!info.bookmarks.length" class="empty-note">无书签</p>
      <h3>批注</h3>
      <p v-if="!info.annotations.length" class="empty-note">无批注</p>
      <button
        v-for="(annotation, i) in info.annotations"
        :key="i"
        class="match-card"
        :disabled="!annotation.bounds"
        @click="
          annotation.bounds &&
          emit('locate', { page: annotation.page, bounds: [annotation.bounds] })
        "
      >
        <small
          >第 {{ annotation.page }} 页 · {{ annotation.type }} ·
          {{ annotation.author }}</small
        >{{ annotation.contents || "无文本内容" }}
      </button>
      <details v-if="info.xmp">
        <summary>XMP 元数据</summary>
        <pre class="extracted-text">{{ info.xmp }}</pre>
      </details>
      <p v-for="message in info.diagnostics" :key="message" class="empty-note">
        {{ message }}
      </p></template
    >
  </template>
</template>
