import type { Usage } from './streetviewApi'

/** Google から読んだ枚数を、増えた分だけ開発サーバー（bridge/usageLog.ts）へ送る */
export function startUsageLog(usage: Usage, context: () => object): void {
  let logged = { ...usage }
  const send = () => {
    const now = { ...usage }
    const added = Object.fromEntries(Object.entries(now).map(([name, value]) => [name, value - logged[name as keyof Usage]]))
    if (Object.values(added).every((value) => value === 0)) return
    logged = now
    navigator.sendBeacon('/usage-log', JSON.stringify({ ...context(), ...added }))
  }
  setInterval(send, 30_000)
  window.addEventListener('pagehide', send)
}
