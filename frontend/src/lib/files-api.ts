import { getAccessToken, parseErrorResponse, refreshSession, toApiError } from "./api";
import type { FileRef } from "./types";

/*
 * Files (docs/adr/0004-file-storage.md): uploads go to the owning module's endpoint as
 * multipart/form-data; downloads come from GET /api/files/{id} with the bearer token.
 */

/** The largest file the API accepts, in bytes (5 MB). */
export const MAX_FILE_BYTES = 5 * 1024 * 1024;

/** File types the API accepts (it checks the contents too). */
export const ALLOWED_EXTENSIONS = ["pdf", "jpg", "jpeg", "png", "webp", "docx", "xlsx", "pptx", "txt"] as const;

/** For `<input type="file" accept>`. */
export const FILE_ACCEPT = ALLOWED_EXTENSIONS.map((e) => `.${e}`).join(",");

export type FileProblem = "type" | "size" | "empty";

/** Why the API would refuse this file, judged from its name and size; null when it looks fine. Pure. */
export function fileProblem(file: { name: string; size: number }, maxBytes = MAX_FILE_BYTES): FileProblem | null {
  const dot = file.name.lastIndexOf(".");
  const extension = dot >= 0 ? file.name.slice(dot + 1).toLowerCase() : "";
  if (!(ALLOWED_EXTENSIONS as readonly string[]).includes(extension)) return "type";
  if (file.size === 0) return "empty";
  if (file.size > maxBytes) return "size";
  return null;
}

/** 1536 → "1.5 KB"; 5242880 → "5 MB". */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb < 10 ? kb.toFixed(1).replace(/\.0$/, "") : Math.round(kb)} KB`;
  const mb = kb / 1024;
  return `${mb < 10 ? mb.toFixed(1).replace(/\.0$/, "") : Math.round(mb)} MB`;
}

async function send(path: string, init: (token: string | null) => RequestInit): Promise<Response> {
  let res: Response;
  try {
    const sent = getAccessToken();
    res = await fetch(path, init(sent));
    if (res.status === 401) {
      const session = await refreshSession();
      if (session) res = await fetch(path, init(session.accessToken));
    }
  } catch (error) {
    throw toApiError(error);
  }
  if (!res.ok) throw await parseErrorResponse(res);
  return res;
}

/**
 * POSTs a multipart form with the bearer token (retrying once through the refresh cookie on
 * 401) and returns the JSON answer. The browser sets the multipart boundary itself.
 */
export async function sendForm<T>(path: string, form: FormData): Promise<T> {
  const res = await send(path, (token) => ({
    method: "POST",
    headers: { Accept: "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: form,
    credentials: "include",
  }));
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Downloads a stored file and offers it to the browser under its name. */
export async function downloadFile(file: Pick<FileRef, "id" | "name">): Promise<void> {
  const res = await send(`/api/files/${encodeURIComponent(file.id)}`, (token) => {
    const headers: Record<string, string> = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    return { headers, credentials: "include" };
  });
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
