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
export const chatSpace = computed(() => user.value?.id || "guest");
let generation = 0;
let restoring: Promise<void> | null = null;
export function useAccount() {
  async function refresh() {
    if (restoring) return restoring;
    const current = generation;
    restoring = (async () => {
      try {
        const result = await accountSession();
        if (current === generation) {
          if (user.value?.id !== result.user?.id || !result.authenticated)
            clearAccountToken();
          user.value = result.user;
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
        user.value = null;
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
      user.value = null;
      clearAccountToken();
    });
  }
  async function password(currentPassword: string, newPassword: string) {
    return perform(async () => {
      await accountWrite("/password", "POST", { currentPassword, newPassword });
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
