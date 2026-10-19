import { chatSpace, useAccount } from "../composables/useAccount";
import { accountCsrf } from "./account";
export const AI_BASE = "/api/v1/pdf/ai";
export async function aiRequest<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const space = chatSpace.value;
  const csrf = method !== "GET" && space !== "guest" ? await accountCsrf() : {};
  if (space !== chatSpace.value) throw new Error("聊天空间已切换");
  const response = await fetch(AI_BASE + path, {
    method,
    headers: {
      "X-Chat-Space": space,
      ...csrf,
      ...(body instanceof FormData || body === undefined
        ? {}
        : { "Content-Type": "application/json" }),
    },
    body:
      body === undefined
        ? undefined
        : body instanceof FormData
          ? body
          : JSON.stringify(body),
    cache: "no-store",
  });
  if (space !== chatSpace.value) throw new Error("聊天空间已切换");
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    if (response.status === 401 || error.code === "CHAT_SPACE_CHANGED")
      await useAccount().refresh();
    throw new Error(error.message || `请求失败（${response.status}）`);
  }
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}
