"""Build the architecture plates as editable SVG, PNG and a six-page PDF."""

import argparse
import html
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
WIDTH, HEIGHT = 1600, 1000
PAPER, INK, MUTED = '#ffffff', '#222222', '#666666'
TEAL, BLUE, GOLD, RED = '#444444', '#444444', '#444444', '#444444'
FONT = '/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc'
BOLD = '/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc'


class Plate:
    def __init__(self, number, slug):
        self.number, self.slug = number, slug
        self.image = Image.new('RGB', (WIDTH, HEIGHT), PAPER)
        self.draw = ImageDraw.Draw(self.image)
        self.svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}">',
                    f'<rect width="{WIDTH}" height="{HEIGHT}" fill="{PAPER}"/>']
        titles = ('Android Auto対応ナビの構成', '経路探索', '景観評価', 'データ構築', 'アプリの構成', '入力と推論')
        self.text(60, 50, titles[number-1], 34, INK, True)
        self.text(60, 1080, '矢印：情報・操作の流れ　　破線：実機・実モデルは未接続', 16, MUTED)
        self.text(1540, 1080, str(number), 16, MUTED, align='right')

    def rect(self, x, y, w, h, fill='white', stroke=None, radius=18, dashed=False):
        y -= 120 if y >= 260 else 0
        fill = 'white'
        stroke = '#bdbdbd' if stroke else None
        radius = 2
        self.draw.rounded_rectangle((x, y, x+w, y+h), radius, fill=fill, outline=stroke, width=2)
        dash = ' stroke-dasharray="9 7"' if dashed else ''
        self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" fill="{fill}" stroke="{stroke or "none"}" stroke-width="2"{dash}/>')
        if dashed and stroke:
            # Match the dashed future boundary in the raster output as well.
            self.draw.rounded_rectangle((x, y, x+w, y+h), radius, outline=fill, width=3)
            for a in range(int(x+radius), int(x+w-radius), 16):
                self.draw.line((a, y, min(a+9, x+w-radius), y), fill=stroke, width=2)
                self.draw.line((a, y+h, min(a+9, x+w-radius), y+h), fill=stroke, width=2)
            for a in range(int(y+radius), int(y+h-radius), 16):
                self.draw.line((x, a, x, min(a+9, y+h-radius)), fill=stroke, width=2)
                self.draw.line((x+w, a, x+w, min(a+9, y+h-radius)), fill=stroke, width=2)

    def text(self, x, y, value, size=24, color=INK, bold=False, align='left'):
        y -= 120 if y >= 260 else 0
        font = ImageFont.truetype(BOLD if bold else FONT, size)
        width = self.draw.textlength(value, font=font)
        left = x-width if align == 'right' else x-width/2 if align == 'center' else x
        if left < 0 or left+width > WIDTH-12 or y+size > HEIGHT:
            raise ValueError(f'Text outside page: {value}')
        self.draw.text((left, y), value, font=font, fill=color, anchor='lt')
        family = 'Hiragino Kaku Gothic ProN, Hiragino Sans, Noto Sans CJK JP, sans-serif'
        self.svg.append(f'<text x="{left}" y="{y}" dominant-baseline="text-before-edge" font-family="{family}" font-size="{size}" font-weight="{600 if bold else 400}" fill="{color}">{html.escape(value)}</text>')
        return width

    def card(self, x, y, w, h, title, lines=(), tag=None, accent=TEAL, future=False, fill='white', size=23):
        self.rect(x, y, w, h, fill, '#bdcfc9', dashed=future)
        title_size = 28
        font = ImageFont.truetype(BOLD, title_size)
        if self.draw.textlength(title, font=font) > w-62:
            raise ValueError(f'Card heading too wide: {title}')
        self.text(x+36, y+24, title, title_size, INK, True)
        for i, line in enumerate(lines):
            line_y = y+78+i*32
            bottom_limit = y+h-39 if tag else y+h-10
            if line_y+size > bottom_limit:
                raise ValueError(f'Card text overlaps its footer: {line}')
            width = self.text(x+26, line_y, line, size, MUTED)
            if width > w-48:
                raise ValueError(f'Card line too wide: {line}')
        if tag:
            self.text(x+26, y+h-35, tag, 16, accent)

    def arrow(self, points, color=MUTED, dashed=False):
        points = [(x, y-120 if y >= 260 else y) for x, y in points]
        data = ' '.join(f'{x},{y}' for x, y in points)
        dash = ' stroke-dasharray="9 7"' if dashed else ''
        self.svg.append(f'<polyline points="{data}" fill="none" stroke="{color}" stroke-width="3" stroke-linejoin="round"{dash}/>')
        if dashed:
            for a, b in zip(points, points[1:]):
                length = math.dist(a, b)
                for d in range(0, int(length), 16):
                    p = tuple(a[i]+(b[i]-a[i])*d/max(length, 1) for i in (0, 1))
                    q = tuple(a[i]+(b[i]-a[i])*min(d+9, length)/max(length, 1) for i in (0, 1))
                    self.draw.line((p, q), fill=color, width=3)
        else:
            self.draw.line(points, fill=color, width=3, joint='curve')
        a, b = points[-2:]
        angle = math.atan2(b[1]-a[1], b[0]-a[0])
        tip = [b] + [(b[0]-14*math.cos(angle+v), b[1]-14*math.sin(angle+v)) for v in (-.45, .45)]
        self.draw.polygon(tip, fill=color)
        self.svg.append(f'<polygon points="{" ".join(f"{x},{y}" for x,y in tip)}" fill="{color}"/>')

    def note(self, y, title, lines, color=TEAL):
        self.text(82, y+18, title, 22, color, True)
        for i, line in enumerate(lines):
            self.text(82, y+54+i*32, line, 21, INK)

    def save(self, output):
        self.image.save(output / f'{self.slug}.png')
        (output / f'{self.slug}.svg').write_text('\n'.join(self.svg+['</svg>']), encoding='utf-8')


def system():
    p = Plate(1, '01-system')
    p.card(60, 290, 410, 245, 'Android Auto 車載画面', ['車載地図・次の曲がり方', '残り時間・距離・案内の終了', '同乗者リクエストのQR表示'], 'platform/android-auto / Car App Library')
    p.card(585, 290, 400, 245, 'ナビの共有セッション', ['選択ルート・現在地・進捗', 'スマホと車載で同じ案内状態', 'GPS・逸脱判定・音声案内'], 'NavigationSession / KMP ＋ Android', BLUE)
    p.card(1100, 290, 440, 245, '景観推薦サービス', ['どの道を組み合わせる？', '景観・時間・負荷を比較', '異なる候補を最大6案'], 'scenic :8004 / Python', TEAL)
    p.arrow([(470, 400), (585, 400)], BLUE)
    p.text(480, 364, '操作', 18, BLUE)
    p.arrow([(585, 465), (470, 465)], BLUE)
    p.text(480, 482, '案内状態', 17, BLUE)
    p.arrow([(985, 400), (1100, 400)], BLUE)
    p.arrow([(1100, 490), (985, 490)], TEAL)
    p.text(999, 445, '候補', 17, TEAL)
    p.card(60, 650, 410, 230, 'スマートフォン', ['停車中に目的地・好みを設定', '候補・景観・名道を比較', 'Spotify・BLE窓機器と連携'], '車載と同じNavigationSessionを参照', TEAL)
    p.card(585, 650, 400, 230, '経路計算エンジン', ['Valhalla ＋ OSM道路タイル', '道路条件に合う経路・時間', '通常ルート要求は直接こちらへ'], 'valhalla :8002 / C++', BLUE)
    p.card(1100, 650, 440, 230, '探索の材料', ['静的：道路・地形・名道のDB', '動的：Open-Meteoの予報', '日時・座標から太陽位置を計算'], '同じ検索では予報・データ版を固定', GOLD)
    p.arrow([(470, 720), (535, 720), (535, 510), (585, 510)], TEAL)
    p.arrow([(1210, 535), (1210, 620), (785, 620), (785, 650)], BLUE)
    p.arrow([(785, 650), (785, 620), (1210, 620), (1210, 535)], BLUE)
    p.text(802, 625, '計算依頼・結果', 17, BLUE)
    p.arrow([(1320, 650), (1320, 535)], GOLD)
    p.note(930, 'スマホで選び、車載画面で案内する', ['選択した経路と案内状態を共有する。Android Autoは車載表示、経路探索は自前PCが担当。',
                                             '通信はCloudflare Tunnelと認証付きNginxを経由。通常経路はValhalla、景観推薦はscenicへ。'])
    return p


def search():
    p = Plate(2, '02-route-search')
    cards = [
        (60, 295, '1  基準を決める', ['同じ道路条件の通常経路', '基準時間 T0 を取得', '走行上限 ＝ T0 ＋ 寄り道'], 'models.py / routing.py'),
        (580, 295, '2  広く候補を集める', ['海・森・水辺・名道の区間', '地域を分散して候補抽出', '広域回廊で日本海側等も試す'], 'store.py / candidate_area.py / corridors.py'),
        (1100, 295, '3  つないで実経路にする', ['候補区間と通過点を並べる', 'Valhallaで道路をつなぐ', '時間・方向・対象区間を検査'], 'search.py / routing.py'),
        (1100, 580, '4  複数案を残して改善', ['ビーム：有望な案を複数保持', 'ALNS：区間を外して再挿入', '評価・制約検査を繰り返す'], 'search.py / evaluator.py'),
        (580, 580, '5  違う体験の案を選ぶ', ['時間と景観の劣る案を整理', '同じ道ばかりの候補を抑制', '特徴の異なる最大6案へ'], 'portfolio.py'),
        (60, 580, '6  比較できる形で返す', ['推薦理由・基準経路との差', '景観・名道・天気の時系列', '採用と案内に同じ経路形状'], 'evidence.py / route_environment.py'),
    ]
    for x, y, title, lines, tag in cards:
        p.card(x, y, 440, 230, title, lines, tag, accent=BLUE if title.startswith('3') else TEAL, size=22)
    for a, b in [((500, 410), (580, 410)), ((1020, 410), (1100, 410)),
                 ((1320, 525), (1320, 580)), ((1100, 695), (1020, 695)), ((580, 695), (500, 695))]:
        p.arrow([a, b])
    p.rect(60, 850, 1480, 75, '#e4eef2')
    p.text(85, 871, 'Valhallaは 1・3・4 で利用。同じ条件の再計算や道路照合はキャッシュで削減。', 25, BLUE, True)
    p.note(950, '先に出して、裏で続ける', ['有効な候補を途中公開 → アプリがrevisionを取得 → 改善後の候補を更新。',
                                             '計算時間・回数には上限がある近似探索。limitedでも有効候補を返せる。厳密最適解の保証ではない。'])
    return p


def evaluation():
    p = Plate(3, '03-factor-evaluation')
    p.card(60, 285, 440, 245, '場所が持つ特徴', ['海・森・湖・川・谷', '自然・田園・市街地の密度', '水域の向き・地形の遮蔽', '登録名道・トンネル等'], 'store.py / terrain.py', TEAL)
    p.card(580, 285, 440, 245, 'その場所を通る状況', ['経路から推定した通過時刻', '日時と座標 → 太陽高度・方位', '固定予報 → 視程・降水・雲', '進行方向・助手席側の景色'], 'environment.py / evaluator.py', BLUE)
    p.card(1100, 285, 440, 245, 'ユーザーの好み', ['海・森林など8種類の比重', '変化・名道・助手席の眺め', '寄り道上限・道路条件', '夜間補正のON / OFF'], 'models.py / feature:route', GOLD)
    for x in (280, 800, 1320):
        p.arrow([(x, 530), (x, 566), (800, 566), (800, 600)], color=BLUE)
    p.card(60, 600, 1480, 150, '区間の品質を補正する',
           ['海など：地理特徴 → 見え方 → 天気・明るさ → 条件が合えば水辺の朝夕加点',
            'トンネルは景観0。森林・市街地には、水辺と同じ天気・昼夜補正を掛けない。'],
           accent=BLUE, size=23)
    p.arrow([(800, 750), (800, 790)], color=TEAL)
    p.card(60, 790, 960, 225, 'ルート全体の体験として評価',
           ['＋ 景観の質・連続性・変化・名道・立ち寄り',
            '− 追加時間・運転負荷・同じ道の重複・未確認情報',
            '満足の頭打ちと重複除外で、走るほど得にはしない'],
           'evaluator.py / experience.py', TEAL)
    p.card(1100, 790, 440, 225, '点数と表示は別の役割',
           ['採点：各区間の品質を積分', '表示：主要景観を帯へ要約', '表示用の海の強調 ≠ 採点加点'],
           '気温・風等は取得するが直接採点しない', GOLD, size=22)
    p.arrow([(1020, 900), (1100, 900)], GOLD)
    p.text(60, 1040, '欠損は快晴・眺望保証に置き換えない。名道の登録と地理特徴による探索候補も区別する。', 21, MUTED)
    return p


def data():
    p = Plate(4, '04-data-pipeline')
    for x, title in ((60, '公開データ・紹介資料'), (580, '収集・検査・変換'), (1100, '検索で読む成果物')):
        p.text(x+8, 278, title, 25, MUTED, True)
    rows = [
        (330, 'OpenStreetMap', ['北海道 region.osm.pbf', '道路・海岸・森林・水域・地名'],
         '道路網と特徴を作る', ['道路タイルと小区間を生成', '水域・道路属性・地名を抽出'],
         '経路 ＋ 地理 ＋ 場所', ['Valhallaタイル ／ scenic DB', 'landscapes ／ context ／ places'], TEAL),
        (505, '国土地理院 DEM', ['地表の標高', '周囲の地形・地平線の推定'],
         '地形を道路へ対応付け', ['標高・谷・8方向の遮蔽を計算', '見通しは地形による近似'],
         'terrain DB', ['地表標高・開放性・谷の指標', '建物・植生・防潮堤は保証外'], BLUE),
        (680, 'ESA WorldCover', ['公開の土地被覆分類', '観測年と取得年を区別'],
         '道路周辺の被覆を集計', ['森林等の不足を補完', '分類の出典・版・欠損を保持'],
         'landcover DB', ['森林・農地等の被覆特徴', '現況や完全な分類精度ではない'], TEAL),
        (855, '公式・自治体・観光団体', ['道路としての紹介が必須', '名称・紹介区間・根拠を調査'],
         '審査 ＋ OSM道路照合', ['連続した紹介道路として登録', '形状不足・根拠不足は保留'],
         '名道の登録DB', ['秀逸な道 ／ 絶景ロード', 'おすすめロード ／ 景観ロード'], GOLD),
    ]
    for y, t1, l1, t2, l2, t3, l3, color in rows:
        for x, title, lines in ((60, t1, l1), (580, t2, l2), (1100, t3, l3)):
            p.card(x, y, 440, 150, title, lines, accent=color, size=21)
        p.arrow([(500, y+75), (580, y+75)], color)
        p.arrow([(1020, y+75), (1100, y+75)], color)
    p.text(60, 1030, '公開前：元データの署名・地図版・スキーマを検査。不一致のDBは混ぜない。地形だけの道は本登録にしない。', 21, MUTED)
    return p


def app():
    p = Plate(5, '05-app-components')
    p.card(60, 290, 440, 180, 'ナビの画面・操作', ['Androidの地図 ／ Android Auto', '同じNavigationSessionを参照'], 'platform/android-navigation・android-auto', BLUE, size=22)
    p.card(580, 290, 440, 180, '音楽の画面・操作', ['選曲の確認・Spotify再生操作', 'ナビから景観・進捗を受け取る'], 'platform/android-music', BLUE, size=22)
    p.card(1100, 290, 440, 180, '窓の画面・操作', ['専用機器へ手動で開閉・停止', '窓の提案と直接操作は別経路'], 'platform/android-window', BLUE, size=22)
    for x in (280, 800, 1320):
        p.arrow([(x, 470), (x, 515)])
    p.card(60, 515, 440, 210, 'ルートと案内の契約・状態', ['feature/route：検索・候補モデル', 'feature/navigation：案内状態', 'GPSから進捗・逸脱を判断'], 'KMP / core/location', TEAL, size=21)
    p.card(580, 515, 440, 210, '音楽の契約・判断・状態', ['feature/music：選曲・再生状態', '曲長と景観から再生を計画', 'feature/driveがナビ情報を変換'], 'KMP / core/environment', TEAL, size=21)
    p.card(1100, 515, 440, 210, '車内の契約・判断', ['feature/cabin：窓提案・機器契約', '接続方式を判断部へ持ち込まない', '提案からの自動窓操作は未接続'], 'KMP / WindowGateway', TEAL, size=21)
    for x in (280, 800, 1320):
        p.arrow([(x, 725), (x, 770)])
    p.card(60, 770, 440, 180, 'ナビの接続実装', ['data/valhalla → 経路・検索API', 'android-location → GPS', 'MapLibre・TTS・前景サービス'], accent=BLUE, size=21)
    p.card(580, 770, 440, 180, '音楽の接続実装', ['data/spotify → 認証・再生操作', 'data/weather → 現在地の予報', 'data/bgm → 外部の1曲推薦API'], accent=BLUE, size=21)
    p.card(1100, 770, 440, 180, '車内機器の接続実装', ['BleWindowGateway → ESP32', '測定・機器イベントを受信', '通知はTTS・スマホ・車載へ'], accent=BLUE, size=21)
    p.text(60, 986, '組み立て：TheaCarApplicationが実ナビ・音楽・窓のRuntimeを所有し、共通GPSと連携を注入。', 23, INK, True)
    p.text(60, 1030, 'ホームのAppGraph.demoは別系統。実入力・実案内と混同しない。iOSの実ナビアダプターは未完成。', 22, MUTED)
    return p


def perception():
    p = Plate(6, '06-input-inference')
    p.card(60, 295, 410, 210, '1  共有入力の機器取得', ['マイク ／ カメラ', '権限・頻度・バッファ解放', '共有アダプターは未接続'], '予定：platform/*-input', BLUE, future=True)
    p.card(595, 295, 410, 210, '2  同じフレームを共有', ['不変のAudioFrame / CameraFrame', '複数の利用先でも取得は一回', '最後の購読終了で機器を解放'], 'core/sensing / SharedSensorFeed', TEAL, size=20)
    p.card(1130, 295, 410, 210, '3  型付きの認識結果へ', ['会話量・景色・特殊音イベント', '信頼度・鮮度・処理期限を管理', '実AIモデルは未接続'], 'core/inference ＋ feature/perception', GOLD, future=True, size=21)
    p.arrow([(470, 400), (595, 400)], BLUE, dashed=True)
    p.arrow([(1005, 400), (1130, 400)], TEAL)
    p.card(60, 605, 410, 215, '6  明示した操作を実行', ['PlaybackGateway等の契約', '再生・音量・案内などへ反映', '窓提案からの自動開閉は未接続'], '実機の成功応答と表示状態を区別', BLUE, size=21)
    p.card(595, 605, 410, 215, '5  各機能が判断', ['選曲 ／ 目標音量 ／ 窓の提案', 'feature/driveで操作を連携', '推論モデルは機器を直接操作しない'], 'feature/music・cabin・drive', TEAL, size=20)
    p.card(1130, 605, 410, 215, '4  環境の意味へ統合', ['認識結果 ＋ GPS・天気等', 'DriveContextとして配信', '古い認識は期限切れにする'], 'data/perception', TEAL, size=21)
    p.arrow([(1335, 505), (1335, 605)])
    p.arrow([(1130, 710), (1005, 710)])
    p.arrow([(595, 710), (470, 710)])
    p.note(875, '共有パイプラインと音楽側の入力',
           ['破線：共有経路への実マイク・カメラ・実モデルの接続は未完了。合成フレームで検証する。',
            '音楽側のCabinMicrophoneは別系統で実装済み。PCMは保存・送信せず、発話割合だけを利用する。',
            'ナビの採点は実会話・実画像を直接使わない。共有経路と実音楽の収音はまだ統合していない。'])
    return p


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/media/architecture')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    plates = [build() for build in (system, search, evaluation, data, app, perception)]
    for plate in plates:
        plate.save(args.output)
    plates[0].image.save(args.output / 'theacar-architecture.pdf', 'PDF',
                         save_all=True, append_images=[p.image for p in plates[1:]], resolution=144)
    print(f'Built {len(plates)} plates: SVG, PNG, and PDF in {args.output}')


if __name__ == '__main__':
    main()
