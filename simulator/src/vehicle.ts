/** ハンコンやキーボードから読んだ操作。 */
export interface Controls {
  /** -1 が左いっぱい、1 が右いっぱい。交差点で曲がる方を選ぶ */
  steer: number
  /** 0〜1 */
  throttle: number
  /** 0〜1 */
  brake: number
}

/** 普通の乗用車くらいの値。ブレーキは体験会で止まりやすいよう、実車より強くしている（社長の指示で 3 倍） */
export const CAR = {
  maxAcceleration: 3.0,
  maxBraking: 24.0,
  /** アクセルを離したときに自然に落ちる速さ */
  coastDeceleration: 0.4,
  /** 制限速度（社長の指示で 60km/h） */
  topSpeed: 60 / 3.6,
} as const

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

/** dt 秒後の速さ（メートル毎秒、0 以上）。道の上を前へ進むだけなので、向きは道が決める。 */
export function nextSpeed(speed: number, controls: Controls, dt: number): number {
  const throttle = clamp(controls.throttle, 0, 1)
  const brake = clamp(controls.brake, 0, 1)
  const accel = throttle * CAR.maxAcceleration * (1 - clamp(speed / CAR.topSpeed, 0, 1))
  const resist = brake * CAR.maxBraking + (throttle === 0 ? CAR.coastDeceleration : 0)
  return Math.max(0, speed + (accel - resist) * dt)
}
