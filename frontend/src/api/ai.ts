export const AI_BASE = "/api/v1/pdf/ai";
export async function aiRequest<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch(AI_BASE + path, {
    method,
    headers:
      body instanceof FormData || body === undefined
        ? {}
        : { "Content-Type": "application/json" },
    body:
      body === undefined
        ? undefined
        : body instanceof FormData
          ? body
          : JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || `请求失败（${response.status}）`);
  }
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}
