<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import AppIcon from "./AppIcon.vue";
import { useAccount } from "../composables/useAccount";
import { registerAccount } from "../api/account";
import type { AccountPanel } from "../types/account";
import "../assets/account.css";
const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: [] }>();
const account = useAccount();
const { user, pending } = account;
const dialog = ref<HTMLDialogElement | null>(null),
  panel = ref<AccountPanel>("login");
const email = ref(""),
  name = ref(""),
  password = ref(""),
  confirm = ref(""),
  currentPassword = ref(""),
  visible = ref(false),
  error = ref(""),
  notice = ref(""),
  registering = ref(false);
const busy = computed(() => pending.value || registering.value);
const title = computed(
  () =>
    ({
      login: "欢迎回来",
      register: "创建账户",
      profile: "个人资料",
      security: "账户安全",
    })[panel.value],
);
function clearPasswords() {
  password.value = "";
  confirm.value = "";
  currentPassword.value = "";
  visible.value = false;
}
function switchPanel(value: AccountPanel) {
  panel.value = value;
  clearPasswords();
  error.value = "";
  notice.value = "";
  name.value = user.value?.displayName || "";
}
function close() {
  clearPasswords();
  emit("close");
}
watch(
  () => props.open,
  async (value) => {
    if (value) {
      switchPanel(user.value ? "profile" : "login");
      await nextTick();
      dialog.value?.showModal();
      void account.refresh();
    } else {
      dialog.value?.close();
      clearPasswords();
    }
  },
);
watch(user, (value, old) => {
  if (value && !old && props.open) switchPanel("profile");
  if (value && old && name.value === old.displayName)
    name.value = value.displayName;
  if (!value && old && ["profile", "security"].includes(panel.value))
    switchPanel("login");
});
async function submit() {
  if (busy.value) return;
  error.value = "";
  notice.value = "";
  if (["register", "security"].includes(panel.value)) {
    if (
      Array.from(password.value).length < 12 ||
      Array.from(password.value).length > 128
    ) {
      error.value = "密码须为 12–128 个字符";
      return;
    }
    if (password.value !== confirm.value) {
      error.value = "两次输入的密码不一致";
      return;
    }
  }
  if (
    ["register", "profile"].includes(panel.value) &&
    (!name.value.trim() || Array.from(name.value.trim()).length > 24)
  ) {
    error.value = "昵称须为 1–24 个字符";
    return;
  }
  try {
    if (panel.value === "register") {
      registering.value = true;
      await registerAccount(email.value, name.value, password.value);
      switchPanel("login");
      notice.value = "账户已创建，请登录";
    } else if (panel.value === "login") {
      await account.login(email.value, password.value);
      close();
    } else if (panel.value === "profile") {
      await account.rename(name.value);
      notice.value = "昵称已保存";
    } else {
      await account.password(currentPassword.value, password.value);
      switchPanel("login");
      notice.value = "密码已更新，已退出旧登录态。可重新登录或继续游客使用。";
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : "账户操作失败，请重试";
  } finally {
    clearPasswords();
    registering.value = false;
  }
}
async function logout() {
  error.value = "";
  try {
    await account.logout();
    close();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "退出失败，请重试";
  }
}
</script>
<template>
  <dialog
    ref="dialog"
    class="account-dialog"
    aria-labelledby="account-title"
    @cancel.prevent="close"
    @close="close"
  >
    <header class="account-dialog-header">
      <div class="account-dialog-brand">
        <span class="account-brand-icon"><AppIcon name="text" /></span
        ><span>PDF 工作台<small>ACCOUNT & CONVERSATIONS</small></span>
      </div>
      <button
        type="button"
        class="account-icon-button"
        aria-label="关闭账户面板"
        @click="close"
      >
        <AppIcon name="close" />
      </button>
    </header>
    <section class="account-dialog-content">
      <p class="account-eyebrow">
        {{ user ? "PERSONAL WORKSPACE" : "YOUR DOCUMENT SPACE" }}
      </p>
      <h2 id="account-title">{{ title }}</h2>
      <p class="account-description">
        {{
          user
            ? "管理账户资料，聊天记录随账户独立保存。"
            : "登录后保存独立聊天记录，也可以继续以游客使用。"
        }}
      </p>
      <div v-if="user" class="account-tabs" aria-label="账户设置">
        <button
          type="button"
          :aria-pressed="panel === 'profile'"
          @click="switchPanel('profile')"
        >
          个人资料</button
        ><button
          type="button"
          :aria-pressed="panel === 'security'"
          @click="switchPanel('security')"
        >
          账户安全
        </button>
      </div>
      <p v-if="account.unavailable.value" class="account-message">
        账户连接暂不可用，不影响游客文档处理。<button
          type="button"
          @click="account.refresh"
        >
          重试连接
        </button>
      </p>
      <p v-if="error" class="account-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="account-notice" role="status">{{ notice }}</p>
      <form :aria-busy="busy" @submit.prevent="submit">
        <label
          v-if="panel === 'register' || panel === 'profile'"
          class="account-label"
          >昵称<input
            v-model="name"
            name="displayName"
            autocomplete="nickname"
            placeholder="怎么称呼你"
            required
            :disabled="busy"
        /></label>
        <label
          v-if="panel === 'login' || panel === 'register'"
          class="account-label"
          >邮箱<input
            v-model="email"
            name="email"
            type="email"
            autocomplete="username"
            placeholder="name@example.com"
            maxlength="254"
            required
            :disabled="busy"
        /></label>
        <div v-if="panel === 'profile'" class="account-label">
          登录邮箱
          <p class="account-readonly">{{ user?.email }}</p>
        </div>
        <label v-if="panel === 'security'" class="account-label"
          >当前密码<input
            v-model="currentPassword"
            name="currentPassword"
            type="password"
            autocomplete="current-password"
            required
            :disabled="busy"
        /></label>
        <label v-if="panel !== 'profile'" class="account-label"
          >{{ panel === "security" ? "新密码" : "密码"
          }}<span class="account-password"
            ><input
              v-model="password"
              name="password"
              :type="visible ? 'text' : 'password'"
              :autocomplete="
                panel === 'login' ? 'current-password' : 'new-password'
              "
              :placeholder="panel === 'login' ? '请输入密码' : '12–128 个字符'"
              required
              :disabled="busy"
            /><button
              type="button"
              :aria-label="visible ? '隐藏密码' : '显示密码'"
              :aria-pressed="visible"
              @click="visible = !visible"
            >
              {{ visible ? "隐藏" : "显示" }}
            </button></span
          ></label
        >
        <label
          v-if="panel === 'register' || panel === 'security'"
          class="account-label"
          >确认密码<input
            v-model="confirm"
            name="confirmPassword"
            type="password"
            autocomplete="new-password"
            placeholder="再次输入密码"
            required
            :disabled="busy"
        /></label>
        <button class="account-primary" type="submit" :disabled="busy">
          {{
            busy
              ? "正在提交…"
              : {
                  login: "登录",
                  register: "创建账户",
                  profile: "保存修改",
                  security: "更新密码",
                }[panel]
          }}<AppIcon name="arrow" />
        </button>
      </form>
      <p v-if="!user" class="account-alternative">
        {{ panel === "register" ? "已有账户？" : "还没有账户？"
        }}<button
          type="button"
          :disabled="busy"
          @click="switchPanel(panel === 'register' ? 'login' : 'register')"
        >
          {{ panel === "register" ? "返回登录" : "创建账户" }}
        </button>
      </p>
      <div class="account-footnote">
        <AppIcon name="history" />
        <p>账户之间的对话互不可见。<br />游客记录不会自动迁移到账户。</p>
      </div>
    </section>
    <footer class="account-dialog-footer">
      <button v-if="!user" type="button" @click="close">
        继续以游客使用 <span aria-hidden="true">↗</span></button
      ><button v-else type="button" :disabled="busy" @click="logout">
        退出登录</button
      ><span>专注每一页</span>
    </footer>
  </dialog>
</template>
