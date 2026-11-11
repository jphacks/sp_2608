import net from 'node:net'
import { afterEach, describe, expect, it } from 'vitest'
import { PhoneLink, formatDestination, formatFix, parseDestination, parseFix } from '../bridge/link'

const fix = { lat: 41.773436, lon: 140.726639, speed: 13.9, bearing: 116 }

describe('parseFix', () => {
  it('数がそろっていれば受け取り、向きを 0〜360 に直す', () => {
    expect(parseFix({ ...fix, bearing: -90 })).toEqual({ ...fix, bearing: 270 })
  })

  it('欠けている・範囲の外・数でないものは受け取らない', () => {
    expect(parseFix({ lat: 41, lon: 140, speed: 1 })).toBeUndefined()
    expect(parseFix({ ...fix, lat: 91 })).toBeUndefined()
    expect(parseFix({ ...fix, speed: '13' })).toBeUndefined()
    expect(parseFix(null)).toBeUndefined()
  })
})

describe('formatFix', () => {
  it('スマホが空白で区切って読める1行にする', () => {
    expect(formatFix(fix)).toBe('FIX 41.7734360 140.7266390 13.90 116.0\n')
  })
})

describe('目的地', () => {
  it('名前の改行は空白にして、1行で送る', () => {
    expect(formatDestination({ lat: 41.9808, lon: 140.6701, name: '大沼\n公園' })).toBe('DEST 41.9808000 140.6701000 大沼 公園\n')
  })

  it('位置が範囲の外、名前が空のものは受け取らない', () => {
    expect(parseDestination({ lat: 41.9808, lon: 140.6701, name: '大沼公園' })).toEqual({ lat: 41.9808, lon: 140.6701, name: '大沼公園' })
    expect(parseDestination({ lat: 91, lon: 140, name: '大沼公園' })).toBeUndefined()
    expect(parseDestination({ lat: 41, lon: 140, name: ' ' })).toBeUndefined()
  })
})

describe('PhoneLink', () => {
  let link: PhoneLink | undefined
  afterEach(async () => { await link?.close() })

  function connect(port: number): Promise<{ socket: net.Socket; lines: () => Promise<string> }> {
    return new Promise((resolve) => {
      const socket = net.connect(port, '127.0.0.1', () => {
        let buffer = ''
        const waiting: ((line: string) => void)[] = []
        socket.setEncoding('utf8')
        socket.on('data', (chunk: string) => {
          buffer += chunk
          let newline
          while ((newline = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, newline)
            buffer = buffer.slice(newline + 1)
            waiting.shift()?.(line)
          }
        })
        resolve({ socket, lines: () => new Promise((r) => waiting.push(r)) })
      })
    })
  }

  async function until(condition: () => boolean): Promise<void> {
    for (let i = 0; i < 100 && !condition(); i++) await new Promise((r) => setTimeout(r, 10))
  }

  it('つながっているスマホへ位置と終わりを送り、途中からつないだスマホにも今の位置を渡す', async () => {
    link = new PhoneLink()
    const port = await link.listen(0)
    const first = await connect(port)
    await until(() => link!.phoneCount === 1)

    const firstLine = first.lines()
    link.sendFix(fix)
    expect(await firstLine).toBe('FIX 41.7734360 140.7266390 13.90 116.0')

    const second = await connect(port)
    expect(await second.lines()).toBe('FIX 41.7734360 140.7266390 13.90 116.0')
    await until(() => link!.phoneCount === 2)

    const stop = first.lines()
    link.sendStop()
    expect(await stop).toBe('STOP')

    second.socket.destroy()
    await until(() => link!.phoneCount === 1)
    expect(link.phoneCount).toBe(1)
    first.socket.destroy()
  })

  it('途中からつないだスマホにも、位置のあとに目的地を渡す', async () => {
    link = new PhoneLink()
    const port = await link.listen(0)
    link.sendFix(fix)
    link.sendDestination({ lat: 41.9808, lon: 140.6701, name: '大沼公園' })
    const phone = await connect(port)
    // 2行は1回でまとめて届くので、先に2行とも待っておく
    const lines = [phone.lines(), phone.lines()]
    expect(await Promise.all(lines)).toEqual(['FIX 41.7734360 140.7266390 13.90 116.0', 'DEST 41.9808000 140.6701000 大沼公園'])
    phone.socket.destroy()
  })
})
