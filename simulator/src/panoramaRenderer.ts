/**
 * ストリートビューの写真（正距円筒の 360 度写真）を、写真の位置から離れたカメラで描く。
 *
 * 写真には奥行きがないので、写真の周りを「地面（撮影したカメラの CAMERA_HEIGHT 下）」と
 * 「半径 PROXY_RADIUS の円筒」という仮の形とみなす。カメラから出した視線が仮の形に当たった点を、
 * 写真の位置から見た向きに直して色を取る。カメラを前へ動かすと、写真と写真の間も前へ進んで見える。
 *
 * 2枚の写真（今の写真 A と次の写真 B）を、進み具合に合わせて重ねる。
 * まだ読み込んでいない部分（透明）は、もう一方の写真で埋める。
 */

/** Google のストリートビューの車のカメラの高さ（地面から）。目の高さも同じにする */
export const CAMERA_HEIGHT = 2.5
/**
 * 仮の円筒の半径。2026-11-05 に函館・黄金道路・美瑛の実際の写真で、隣り合う2枚の写真の違い（継ぎ目の目立ちやすさ）を
 * 半径 30・60・100・200・400m で測り、60〜100m がいちばん小さかった（30m より 2〜4 割小さい）
 */
export const PROXY_RADIUS = 80
/** 写真のテクスチャの大きさ（新しい写真の zoom 3 の全体）。古い写真は左上の一部だけを使う */
export const TEXTURE_WIDTH = 4096
export const TEXTURE_HEIGHT = 2048
export const TILE_SIZE = 512

export interface PanoramaLayer {
  texture: WebGLTexture
  /** カメラの位置 − 写真の位置（東へ・北へ、メートル） */
  offsetEast: number
  offsetNorth: number
  /** 写真の真ん中が向いている方角（度） */
  heading: number
  /** テクスチャのうち写真が入っている割合（横・縦）。新しい写真は 1、古い写真は 1 より小さい */
  scale: [number, number]
}

export interface View {
  /** 見ている方角（度、北が 0 の時計回り） */
  heading: number
  /** 見上げる角度（度、下向きが負） */
  pitch: number
  /** 横の視野角（度） */
  fov: number
}

const VERTEX = `#version 300 es
in vec2 position;
out vec2 screen;
void main() {
  screen = position;
  gl_Position = vec4(position, 0.0, 1.0);
}`

const FRAGMENT = `#version 300 es
precision highp float;
const float PI = 3.141592653589793;
in vec2 screen;
out vec4 color;
uniform float yaw;
uniform float pitch;
uniform float tanHalfFov;
uniform float aspect;
uniform float cameraHeight;
uniform float radius;
uniform sampler2D textureA;
uniform sampler2D textureB;
uniform vec2 offsetA;
uniform vec2 offsetB;
uniform float headingA;
uniform float headingB;
uniform vec2 scaleA;
uniform vec2 scaleB;
uniform float mixB;

// 画面の点から出る視線（東・北・上）
vec3 ray() {
  vec3 d = normalize(vec3(screen.x * tanHalfFov, screen.y * tanHalfFov / aspect, 1.0));
  float cp = cos(pitch);
  float sp = sin(pitch);
  d = vec3(d.x, d.y * cp + d.z * sp, -d.y * sp + d.z * cp);
  float cy = cos(yaw);
  float sy = sin(yaw);
  return vec3(d.x * cy + d.z * sy, -d.x * sy + d.z * cy, d.y);
}

// 写真の位置から offset だけ離れたカメラの視線が、仮の形に当たる点（写真の位置から見た向き）で色を取る
vec4 shade(sampler2D image, vec2 offset, float heading, vec2 scale, vec3 dir) {
  vec3 origin = vec3(offset, 0.0);
  vec3 hit = vec3(0.0);
  bool found = false;
  if (dir.z < -1e-4) {
    vec3 ground = origin + dir * (-cameraHeight / dir.z);
    if (length(ground.xy) <= radius) {
      hit = ground;
      found = true;
    }
  }
  if (!found) {
    vec2 flat2 = dir.xy;
    float a = dot(flat2, flat2);
    float b = 2.0 * dot(origin.xy, flat2);
    float c = dot(origin.xy, origin.xy) - radius * radius;
    float disc = b * b - 4.0 * a * c;
    // 真上を見ている、またはカメラが円筒の外にいるときは、遠くの景色とみなす
    hit = (a < 1e-8 || disc < 0.0) ? dir * 1000.0 : origin + dir * ((-b + sqrt(disc)) / (2.0 * a));
  }
  float compass = atan(hit.x, hit.y);
  float up = atan(hit.z, length(hit.xy));
  vec2 uv = vec2(fract(0.5 + (compass - heading) / (2.0 * PI)), 0.5 - up / PI) * scale;
  return texture(image, uv);
}

void main() {
  vec3 dir = ray();
  vec4 a = shade(textureA, offsetA, headingA, scaleA, dir);
  vec4 b = shade(textureB, offsetB, headingB, scaleB, dir);
  float wa = (1.0 - mixB) * a.a;
  float wb = mixB * b.a;
  // 片方しか読み込めていない部分は、読み込めている方で埋める
  if (wa + wb < 1e-3) {
    wa = a.a;
    wb = b.a * (1.0 - a.a);
  }
  float total = wa + wb;
  color = total < 1e-3 ? vec4(0.62, 0.70, 0.74, 1.0) : vec4((a.rgb * wa + b.rgb * wb) / total, 1.0);
}`

export class PanoramaRenderer {
  /** 仮の円筒の半径（継ぎ目の測定で変えられるように持つ） */
  radius = PROXY_RADIUS
  private readonly gl: WebGL2RenderingContext
  private readonly program: WebGLProgram
  private readonly uniforms = new Map<string, WebGLUniformLocation | null>()
  private readonly empty: WebGLTexture

  constructor(private readonly canvas: HTMLCanvasElement) {
    // 継ぎ目の測定で、描いた直後の絵を読み出せるようにする
    const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true })
    if (!gl) throw new Error('この PC のブラウザでは WebGL2 が使えません')
    this.gl = gl
    this.program = link(gl, VERTEX, FRAGMENT)
    const buffer = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
    const position = gl.getAttribLocation(this.program, 'position')
    gl.enableVertexAttribArray(position)
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
    this.empty = this.createTexture()
  }

  /** 写真1枚分の、まだ何も入っていない（透明な）テクスチャ。 */
  createTexture(): WebGLTexture {
    const gl = this.gl
    const texture = gl.createTexture()!
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, TEXTURE_WIDTH, TEXTURE_HEIGHT, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    // 写真の左端と右端はつながっている（真後ろ）
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    return texture
  }

  /** テクスチャを使い回す前に透明へ戻す。 */
  clearTexture(texture: WebGLTexture): void {
    const gl = this.gl
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, TEXTURE_WIDTH, TEXTURE_HEIGHT, 0, gl.RGBA, gl.UNSIGNED_BYTE, null)
  }

  /** タイルを、写真のテクスチャの (x, y) 番目に書き込む。右端・下端のタイルは小さいので、位置は TILE_SIZE で決める。 */
  putTile(texture: WebGLTexture, x: number, y: number, image: ImageBitmap): void {
    const gl = this.gl
    gl.bindTexture(gl.TEXTURE_2D, texture)
    gl.texSubImage2D(gl.TEXTURE_2D, 0, x * TILE_SIZE, y * TILE_SIZE, gl.RGBA, gl.UNSIGNED_BYTE, image)
  }

  render(view: View, a: PanoramaLayer | undefined, b: PanoramaLayer | undefined, mixB: number): void {
    const { gl, canvas } = this
    const width = canvas.clientWidth
    const height = canvas.clientHeight
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width
      canvas.height = height
    }
    gl.viewport(0, 0, width, height)
    gl.useProgram(this.program)
    const radians = Math.PI / 180
    this.set('yaw', view.heading * radians)
    this.set('pitch', view.pitch * radians)
    this.set('tanHalfFov', Math.tan(view.fov * radians / 2))
    this.set('aspect', width / height)
    this.set('cameraHeight', CAMERA_HEIGHT)
    this.set('radius', this.radius)
    this.layer(0, 'A', a)
    this.layer(1, 'B', b)
    this.set('mixB', b ? mixB : 0)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }

  private layer(unit: number, name: string, layer: PanoramaLayer | undefined): void {
    const gl = this.gl
    gl.activeTexture(gl.TEXTURE0 + unit)
    gl.bindTexture(gl.TEXTURE_2D, layer?.texture ?? this.empty)
    gl.uniform1i(this.location(`texture${name}`), unit)
    gl.uniform2f(this.location(`offset${name}`), layer?.offsetEast ?? 0, layer?.offsetNorth ?? 0)
    gl.uniform2f(this.location(`scale${name}`), layer?.scale[0] ?? 1, layer?.scale[1] ?? 1)
    this.set(`heading${name}`, (layer?.heading ?? 0) * Math.PI / 180)
  }

  /**
   * 継ぎ目の目安。同じカメラ位置で、写真 A だけと写真 B だけを描き、両方が写っている画素の色の差の平均（0〜255）。
   * 小さいほど、入れ替えたときの継ぎ目が目立たない。
   */
  seam(view: View, a: PanoramaLayer, b: PanoramaLayer): number {
    const read = (mixB: number) => {
      this.render(view, a, b, mixB)
      const pixels = new Uint8Array(this.canvas.width * this.canvas.height * 4)
      this.gl.readPixels(0, 0, this.canvas.width, this.canvas.height, this.gl.RGBA, this.gl.UNSIGNED_BYTE, pixels)
      return pixels
    }
    const onlyA = read(0)
    const onlyB = read(1)
    let total = 0
    let count = 0
    // 空の部分（どの写真でも同じ色）は継ぎ目に効かないので、画面の下 2/3 だけを比べる
    const rows = Math.floor(this.canvas.height * 2 / 3)
    for (let i = 0; i < rows * this.canvas.width * 4; i += 16) {
      total += Math.abs(onlyA[i] - onlyB[i]) + Math.abs(onlyA[i + 1] - onlyB[i + 1]) + Math.abs(onlyA[i + 2] - onlyB[i + 2])
      count += 3
    }
    return total / count
  }

  private set(name: string, value: number): void {
    this.gl.uniform1f(this.location(name), value)
  }

  private location(name: string): WebGLUniformLocation | null {
    if (!this.uniforms.has(name)) this.uniforms.set(name, this.gl.getUniformLocation(this.program, name))
    return this.uniforms.get(name)!
  }
}

function link(gl: WebGL2RenderingContext, vertex: string, fragment: string): WebGLProgram {
  const program = gl.createProgram()!
  for (const [type, source] of [[gl.VERTEX_SHADER, vertex], [gl.FRAGMENT_SHADER, fragment]] as const) {
    const shader = gl.createShader(type)!
    gl.shaderSource(shader, source)
    gl.compileShader(shader)
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader) ?? 'shader')
    gl.attachShader(program, shader)
  }
  gl.linkProgram(program)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program) ?? 'program')
  return program
}
