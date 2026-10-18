import { describe, it, expect } from 'vitest';
import apiDoc from '../docs/API.md?raw';
import indexSource from '../src/index.ts?raw';
import { MODE_IDS } from '../src/modes';

// API を変えたときに docs/API.md の更新を忘れないためのテスト
describe('docs/API.md', () => {
  const routes = [...indexSource.matchAll(/app\.(get|post|put|patch|delete)\(\s*'([^']+)'/g)].map(
    ([, method, path]) => `${method.toUpperCase()} ${path}`,
  );

  it('コードにあるエンドポイントがすべて載っている', () => {
    expect(routes.length).toBeGreaterThan(0);
    const missing = routes.filter((r) => !apiDoc.includes(`\`${r}\``));
    expect(missing, `docs/API.md に載っていないエンドポイント: ${missing.join(', ')}`).toEqual([]);
  });

  it('選曲モードの識別子がすべて載っている', () => {
    const missing = MODE_IDS.filter((id) => !apiDoc.includes(`\`${id}\``));
    expect(missing, `docs/API.md に載っていないモード: ${missing.join(', ')}`).toEqual([]);
  });
});
