import {
	env,
	createExecutionContext,
	waitOnExecutionContext,
	SELF,
} from "cloudflare:test";
import { describe, it, expect } from "vitest";
import worker from "../src/index";

const IncomingRequest = Request<unknown, IncomingRequestCfProperties>;

describe("bgm-api worker", () => {
	it("ヘルスチェック（unit style）", async () => {
		const request = new IncomingRequest("http://example.com");
		const ctx = createExecutionContext();
		const response = await worker.fetch(request, env, ctx);
		await waitOnExecutionContext(ctx);
		expect(await response.text()).toBe("bgm-api ok");
	});

	it("ヘルスチェック（integration style）", async () => {
		const response = await SELF.fetch("https://example.com");
		expect(await response.text()).toBe("bgm-api ok");
	});

	it("モードの一覧を返す", async () => {
		const res = await SELF.fetch("https://example.com/modes");
		const body: any = await res.json();
		expect(body.default).toBe("blend");
		expect(body.modes.map((m: any) => m.id)).toEqual(["discover", "blend", "playlist"]);
	});

	it("不正な mode は 400", async () => {
		const res = await SELF.fetch("https://example.com/recommend", {
			method: "POST",
			body: JSON.stringify({ mode: "all", scene: { features: {} }, members: [{ id: "a", name: "A" }] }),
		});
		expect(res.status).toBe(400);
	});

	it.each([{ history: {} }, { pool: [null] }, { members: [null] }, { scene: { features: { forest: 2 } } }])("不正な推薦入力は400: %j", async (patch) => {
		const res = await SELF.fetch("https://example.com/recommend", {
			method: "POST", headers: { "content-type": "application/json" },
			body: JSON.stringify({ scene: { features: {} }, members: [{ id: "a", name: "A" }], ...patch }),
		});
		expect(res.status).toBe(400);
		expect(await res.json()).toHaveProperty("error");
	});

	it.each([
		["/annotate", { tracks: [{ title: 42, artist: "A" }] }],
		["/annotate", { tracks: [null] }],
		["/link/spotify", { track_ids: {} }],
		["/link/spotify", { track_ids: [null] }],
	])("不正な曲入力は400: %s", async (path, body) => {
		const res = await SELF.fetch(`https://example.com${path}`, { method: "POST", body: JSON.stringify(body) });
		expect(res.status).toBe(400);
	});

	it("APP_TOKEN があるときは、トークンが無いと 401。/modes は誰でも呼べる", async () => {
		const withToken = { ...env, APP_TOKEN: "secret-token" };
		const call = async (path: string, headers: Record<string, string> = {}) => {
			const ctx = createExecutionContext();
			const res = await worker.fetch(new IncomingRequest(`http://example.com${path}`, { method: "POST", headers, body: "{}" }), withToken, ctx);
			await waitOnExecutionContext(ctx);
			return res.status;
		};
		expect(await call("/annotate")).toBe(401);
		expect(await call("/annotate", { authorization: "Bearer wrong" })).toBe(401);
		// 正しいトークンなら認証は通る（本文が空なので 400）
		expect(await call("/annotate", { authorization: "Bearer secret-token" })).toBe(400);
		const ctx = createExecutionContext();
		const res = await worker.fetch(new IncomingRequest("http://example.com/modes"), withToken, ctx);
		await waitOnExecutionContext(ctx);
		expect(res.status).toBe(200);
	});
});
