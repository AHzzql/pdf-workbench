import { computed, ref, shallowRef } from "vue";
import {
  accountSession,
  accountWrite,
  clearAccountToken,
  AccountError,
} from "../api/account";
import type { AccountUser, AccountSession } from "../types/account";
const user = shallowRef<AccountUser | null>(null);
const pending = ref(false);
const unavailable = ref(false);
const guestWorkspace = ref("");
export const chatSpace = computed(() => user.value?.id || guestWorkspace.value);
export async function ensureChatSpace() {
  if (!chatSpace.value) await useAccount().refresh();
  if (!chatSpace.value) throw new Error("聊天会话暂不可用，请稍后重试");
  return chatSpace.value;
}
let generation = 0;
let restoring: Promise<void> | null = null;
export function useAccount() {
  async function refresh() {
    if (restoring) return restoring;
    const current = generation;
    restoring = (async () => {
      try {
        const result = await accountSession();
        if (!result.workspaceId) throw new Error("聊天会话缺少空间标识");
        if (current === generation) {
          if (user.value?.id !== result.user?.id || !result.authenticated)
            clearAccountToken();
          if (result.authenticated) {
            user.value = result.user;
            guestWorkspace.value = "";
          } else {
            guestWorkspace.value = result.workspaceId;
            user.value = null;
          }
          unavailable.value = false;
        }
      } catch {
        if (current === generation) unavailable.value = true;
      } finally {
        restoring = null;
      }
    })();
    return restoring;
  }
  async function perform<T>(operation: () => Promise<T>): Promise<T> {
    if (pending.value) throw new Error("账户操作正在进行");
    pending.value = true;
    const current = ++generation;
    try {
      const result = await operation();
      unavailable.value = false;
      return result;
    } catch (e) {
      if (
        e instanceof AccountError &&
        e.status === 401 &&
        current === generation
      ) {
        clearAccountToken();
        if (user.value) {
          guestWorkspace.value = "";
          user.value = null;
        }
      }
      if (e instanceof AccountError && e.status === 403) await refresh();
      throw e;
    } finally {
      pending.value = false;
    }
  }
  async function login(email: string, password: string) {
    return perform(async () => {
      const result = await accountWrite<AccountSession>("/login", "POST", {
        email,
        password,
      });
      user.value = result.user;
      guestWorkspace.value = "";
      unavailable.value = false;
    });
  }
  async function rename(displayName: string) {
    return perform(async () => {
      user.value = await accountWrite<AccountUser>("/profile", "PATCH", {
        displayName,
      });
    });
  }
  async function logout() {
    return perform(async () => {
      await accountWrite("/logout", "POST");
      guestWorkspace.value = "";
      user.value = null;
      clearAccountToken();
    });
  }
  async function password(currentPassword: string, newPassword: string) {
    return perform(async () => {
      await accountWrite("/password", "POST", { currentPassword, newPassword });
      guestWorkspace.value = "";
      user.value = null;
      clearAccountToken();
    });
  }
  return {
    user,
    pending,
    unavailable,
    refresh,
    login,
    rename,
    logout,
    password,
  };
}
