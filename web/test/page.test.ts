import { describe, expect, it } from "vitest";
import { escapeHtml, renderTripPage, routeArtSvg } from "../src/page";
import { parseTrip, type StoredTrip } from "../src/trip";
import { sampleMusic, sampleTrip } from "./fixtures";

const stored = (over: Record<string, unknown> = {}): StoredTrip => ({
  ...parseTrip({ ...sampleTrip, ...over }),
  deviceId: null,
  hasPhoto: true,
  photoVersion: 1,
  createdAtEpochMillis: 0,
});

describe("renderTripPage", () => {
  it("ステッカーの文言が入る", () => {
    const htmlText = renderTripPage(stored(), "/t/x/photo.jpg");
    expect(htmlText).toContain("10.17 sat.");
    expect(htmlText).toContain("@北海道 八雲町");
    expect(htmlText).toContain("MEMBERS");
    expect(htmlText).toContain("<li>Honoka</li>");
    expect(htmlText).toContain('<span class="num">358</span><span class="unit">km</span>');
    expect(htmlText).toContain('<span class="num">4</span><span class="unit">h</span><span class="num">30</span>');
    expect(htmlText).toContain("08:00");
    expect(htmlText).toContain("12:30");
    expect(htmlText).toContain("80</span> km/h");
    expect(htmlText).toContain("PHOTO");
    expect(htmlText).toContain('id="spot-star"');
    expect(htmlText).toContain("tiles.openfreemap.org/styles/liberty");
    expect(htmlText).toContain("<title>北海道 八雲町 2026.10.17</title>");
  });

  it("ユーザー入力はエスケープされ、script からも抜けられない", () => {
    const htmlText = renderTripPage(stored({ destinationTitle: "<img src=x onerror=alert(1)>", members: ["</script><b>"] }), null);
    expect(htmlText).not.toContain("<img src=x");
    expect(htmlText).toContain("&lt;img src=x");
    expect(htmlText).not.toContain("</script><b>");
  });

  it("写真が無いときはプレースホルダーになり、撮影地点の行は出ない", () => {
    const htmlText = renderTripPage(stored({ photoLocation: null }), null);
    expect(htmlText).toContain("art-placeholder");
    expect(htmlText).not.toContain("写真の撮影地点");
    expect(htmlText).not.toContain("PHOTO");
  });

  it("経路が無くても描ける。写真の位置だけあれば星は出る", () => {
    const svg = routeArtSvg(stored({ geometry: [] }));
    expect(svg).toContain("<svg");
    expect(svg).not.toContain('class="route-line"');
    expect(svg).toContain("PHOTO");
    const none = routeArtSvg(stored({ geometry: [], photoLocation: null }));
    expect(none).not.toContain("<path");
  });
});

describe("音楽のセクション", () => {
  it("曲と観測があればジャンル・順位・折れ線が出る", () => {
    const htmlText = renderTripPage(stored({ music: sampleMusic }), null);
    expect(htmlText).toContain("Genres");
    expect(htmlText).toContain("J-POP");
    expect(htmlText).toContain("Lively Tracks");
    expect(htmlText).toContain("峠を越えて");
    expect(htmlText).toContain('class="mood-chart"');
    expect(htmlText).toContain("表で見る");
  });

  it("音楽が無ければ音楽のセクションは出ない", () => {
    const htmlText = renderTripPage(stored(), null);
    expect(htmlText).not.toContain("Genres");
    expect(htmlText).not.toContain('class="mood-chart"');
  });
});

describe("escapeHtml", () => {
  it("5 文字を置き換える", () => {
    expect(escapeHtml(`<a href="x">&'</a>`)).toBe("&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;");
  });
});
