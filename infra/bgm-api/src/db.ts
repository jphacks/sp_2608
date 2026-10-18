// 推薦・注釈が使う DB の操作だけを型にしたもの。
// Cloudflare の D1 はこの形を満たすのでそのまま渡せる。PC では sqlite.ts が Node.js 内蔵の SQLite で同じ形を作る。

export interface SqlStatement {
  bind(...values: unknown[]): SqlStatement;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
}

export interface SqlDatabase {
  prepare(sql: string): SqlStatement;
  /** すべて成功するか、すべて取り消すか。 */
  batch(statements: SqlStatement[]): Promise<unknown[]>;
}
