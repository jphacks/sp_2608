"""画像生成で作った黒一色のイラストを、レコードのラベル用に、白を透明にした PNG にする。

    python3 simulator/tools/record_art.py 生成したイラスト.png simulator/public/records/art/<道の id>.png
"""
import sys
import numpy as np
from PIL import Image, ImageFilter

src, out = sys.argv[1], sys.argv[2]
im = Image.open(src).convert('L').resize((600, 600), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.6))
a = np.asarray(im).astype(float)
# 黒の濃さを不透明度にする（白は透明）。輪郭は少しなめらかに残す
alpha = np.clip((150 - a) / 90, 0, 1)
rgba = np.zeros((600, 600, 4), dtype=np.uint8)
rgba[..., 3] = (alpha * 255).astype(np.uint8)
rgba[..., :3] = (0x1e, 0x2a, 0x24)
Image.fromarray(rgba).save(out, optimize=True)
