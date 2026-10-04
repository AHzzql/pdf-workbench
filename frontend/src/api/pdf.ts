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
  return { data: null, download: { blob: await response.blob(), name } };
}
