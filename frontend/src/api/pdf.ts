export type Field = string | number | boolean | Blob | null | undefined;
export async function processPdf(
  endpoint: string,
  file: File,
  fields: Record<string, Field>,
  signal: AbortSignal,
) {
  const body = new FormData();
  body.append("file", file);
  for (const [name, value] of Object.entries(fields))
    if (value !== null && value !== undefined)
      body.append(name, value instanceof Blob ? value : String(value));
  const response = await fetch(`/api/v1/pdf/${endpoint}`, {
    method: "POST",
    body,
    signal,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message || `请求失败（${response.status}）`);
  }
  if (response.headers.get("content-type")?.includes("application/json"))
    return { data: (await response.json()) as unknown, download: null };
  const name =
    response.headers
      .get("content-disposition")
      ?.match(/filename="([^"]+)"/)?.[1] || "result.pdf";
  const total = Number(response.headers.get("X-Images-Total"));
  const exported = Number(response.headers.get("X-Images-Exported"));
  const note =
    response.headers.has("X-Images-Total") &&
    response.headers.has("X-Images-Exported") &&
    Number.isInteger(total) &&
    Number.isInteger(exported) &&
    total >= 0 &&
    exported >= 0 &&
    exported <= total
      ? `发现 ${total} 个图片资源，成功转换 ${exported} 个${exported < total ? "；部分图片仅保留原始流，详见 ZIP 清单" : ""}`
      : undefined;
  return { data: null, download: { blob: await response.blob(), name, note } };
}
