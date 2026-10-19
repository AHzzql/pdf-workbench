import type { AccountSession, AccountUser } from "../types/account";
export class AccountError extends Error {
  constructor(
    message: string,
    public status: number,
    public code: string,
  ) {
    super(message);
  }
}
let csrf: { headerName: string; token: string } | null = null;
let csrfEpoch = 0;
let csrfPending: Promise<{ headerName: string; token: string }> | null = null;
export function clearAccountToken() {
  csrfEpoch++;
  csrfPending = null;
  csrf = null;
}
async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch("/api/v1/account" + path, {
    method,
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(method === "GET" || !csrf ? {} : { [csrf.headerName]: csrf.token }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const value = await response.json().catch(() => ({}));
    if (response.status === 403) csrf = null;
    throw new AccountError(
      value.message || "账户服务暂不可用，请稍后重试",
      response.status,
      value.code || "ACCOUNT_ERROR",
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
export async function accountCsrf(): Promise<Record<string, string>> {
  if (!csrf) {
    const epoch = csrfEpoch;
    const pending = (csrfPending ??= request("/csrf"));
    try {
      const token = await pending;
      if (epoch !== csrfEpoch) return accountCsrf();
      csrf = token;
    } finally {
      if (csrfPending === pending) csrfPending = null;
    }
  }
  return { [csrf!.headerName]: csrf!.token };
}
export async function accountWrite<T>(
  path: string,
  method: string,
  body?: unknown,
) {
  await accountCsrf();
  try {
    return await request<T>(path, method, body);
  } finally {
    if (["/login", "/logout", "/password"].includes(path)) clearAccountToken();
  }
}
export const accountSession = () => request<AccountSession>("/session");
export const registerAccount = (
  email: string,
  displayName: string,
  password: string,
) =>
  accountWrite<AccountUser>("/register", "POST", {
    email,
    displayName,
    password,
  });
