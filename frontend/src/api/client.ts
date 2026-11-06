import { currentUser, userManager } from "../auth/auth";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

async function authHeaders(): Promise<Record<string, string>> {
  const user = await currentUser();
  if (!user) {
    await userManager.signinRedirect({ state: { returnTo: window.location.pathname } });
    throw new ApiError(401, "UNAUTHENTICATED", "Signing in");
  }
  return { Authorization: `Bearer ${user.access_token}` };
}

async function parse<T>(res: Response): Promise<T> {
  if (res.ok) {
    if (res.status === 204) return undefined as T;
    const type = res.headers.get("content-type") ?? "";
    return (type.includes("json") ? await res.json() : await res.text()) as T;
  }
  let body: Record<string, unknown> = {};
  try {
    body = await res.json();
  } catch {
    // no JSON body
  }
  throw new ApiError(
    res.status,
    (body.code as string) ?? "ERROR",
    (body.detail as string) ?? res.statusText,
    body,
  );
}

export const api = {
  async get<T>(path: string): Promise<T> {
    return parse<T>(await fetch(`/api${path}`, { headers: await authHeaders() }));
  },
  async post<T>(path: string, body?: unknown): Promise<T> {
    return parse<T>(
      await fetch(`/api${path}`, {
        method: "POST",
        headers: { ...(await authHeaders()), "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
  },
  async put<T>(path: string, body?: unknown): Promise<T> {
    return parse<T>(
      await fetch(`/api${path}`, {
        method: "PUT",
        headers: { ...(await authHeaders()), "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    );
  },
  async upload<T>(path: string, file: File, fields: Record<string, string> = {}): Promise<T> {
    const form = new FormData();
    form.append("file", file);
    Object.entries(fields).forEach(([k, v]) => form.append(k, v));
    return parse<T>(
      await fetch(`/api${path}`, { method: "POST", headers: await authHeaders(), body: form }),
    );
  },
  async download(path: string, fileName: string): Promise<void> {
    const res = await fetch(`/api${path}`, { headers: await authHeaders() });
    if (!res.ok) await parse(res);
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
  },
};
