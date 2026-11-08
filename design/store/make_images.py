"""ロゴの原本と朝焼けの写真から Google Play のアイコンを作る。使い方: python3 design/store/make_images.py

字の輪郭はロゴの画像から取り出す。レコードは拡大しても溝が潰れないよう、同じ寸法で描き直す。
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

HERE = Path(__file__).parent
LOGO = HERE.parent / 'brand' / 'tabireco-logo.png'
PHOTO = HERE / 'source' / 'photo.png'
MASK_SCALE, SS = 6, 4
X0, Y0, X1, Y1 = 405, 283, 1521, 684      # ロゴの範囲
CX, CY = 474.5, 332.5                     # レコードの中心
R, R1, R2, LABEL, LINE = 41.4, 33.9, 29.1, 15.75, .75
RED, WHITE = (255, 49, 49), (255, 255, 255)


def letter_mask():
    image = np.array(Image.open(LOGO).convert('L')).astype(float) / 255
    yy, xx = np.mgrid[0:image.shape[0], 0:image.shape[1]]
    image[((xx - CX) ** 2 + (yy - CY) ** 2 < (R + 2.2) ** 2) & (yy < 375.3)] = 1  # 下の横棒は残す
    part = Image.fromarray((image * 255).astype(np.uint8)).crop((X0, Y0, X1, Y1))
    part = part.resize((part.width * MASK_SCALE, part.height * MASK_SCALE), Image.BICUBIC)
    smooth = ndimage.gaussian_filter(np.array(part).astype(float) / 255, MASK_SCALE * .42)
    return Image.fromarray(((smooth < .5) * 255).astype(np.uint8))


def record(canvas, x, y, s):
    """白いレコードを描く。溝は写真が透けて見えるように抜く。"""
    shape = Image.new('L', canvas.size, 0)
    draw = ImageDraw.Draw(shape)

    def circle(target, r, fill):
        target.ellipse((x - r * s, y - r * s, x + r * s, y + r * s), fill=fill)
    circle(draw, R, 255)
    for r in (R1, R2):
        circle(draw, r + LINE / 2, 0)
        circle(draw, r - LINE / 2, 255)
    canvas.paste(WHITE, (0, 0), shape)
    circle(ImageDraw.Draw(canvas), LABEL, RED)


def put_logo(canvas, mask, box, scale, x, y):
    part = mask.crop(tuple((v - o) * MASK_SCALE for v, o in zip(box, (X0, Y0, X0, Y0))))
    size = round((box[2] - box[0]) * scale * SS), round((box[3] - box[1]) * scale * SS)
    canvas.paste(WHITE, (round(x * SS), round(y * SS)), part.resize(size, Image.LANCZOS))
    record(canvas, (x + (CX - box[0]) * scale) * SS, (y + (CY - box[1]) * scale) * SS, scale * SS)


def background(crop, size):
    return Image.open(PHOTO).convert('RGB').crop(crop).resize((size[0] * SS, size[1] * SS), Image.LANCZOS)


mask = letter_mask()

# アイコン：朝日の前に「旅」の1字
icon = background((152, 228, 752, 828), (512, 512))
put_logo(icon, mask, (415, 291, 758, 636), 384 / 345, 66, 64)
icon.resize((512, 512), Image.LANCZOS).convert('RGBA').save(HERE / 'icon-512.png')
