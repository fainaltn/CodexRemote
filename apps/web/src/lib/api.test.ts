import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearToken, getToken, login, logout } from "./api";

function createLocalStorageMock() {
  const store = new Map<string, string>();
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, value);
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
  };
}

describe("web api token flow", () => {
  beforeEach(() => {
    vi.stubGlobal("window", globalThis);
    vi.stubGlobal("localStorage", createLocalStorageMock());
    vi.stubGlobal("fetch", vi.fn());
  });

  it("stores and returns the login token", async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      json: async () => ({
        token: "token-123",
        expiresAt: "2026-04-07T00:00:00.000Z",
      }),
    } as Response);

    await login("secret");

    expect(getToken()).toBe("token-123");
  });

  it("clears the token on logout even if the request fails", async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      json: async () => ({
        token: "token-456",
        expiresAt: "2026-04-07T00:00:00.000Z",
      }),
    } as Response);

    await login("secret");

    vi.mocked(fetch).mockResolvedValue({
      ok: false,
      status: 500,
      statusText: "boom",
      json: async () => ({ error: "boom" }),
    } as Response);

    await expect(logout()).rejects.toThrow("boom");
    expect(getToken()).toBeNull();
  });

  it("supports explicit clearing", () => {
    localStorage.setItem("codexremote_token", "token-789");
    clearToken();
    expect(getToken()).toBeNull();
  });
});
