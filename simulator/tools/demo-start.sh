#!/bin/zsh
# デモの起動を1回で済ませる。スマホ（確認用の旅れこ）を USB でつないでから打つ。
#
#   simulator/tools/demo-start.sh
#
# 1. スマホ用の中継（8790）とシミュレータの開発サーバー（5173、位置の中継 8788 を含む）を開く
# 2. USB の転送（adb reverse 8788・8790、adb forward 5277）を張る
# 3. スマホの Android Auto のヘッドユニットサーバーを起動し、DHU（車の画面）を開く
# 4. 旅れこを開いてシミュレータとつなぎ、Chrome でシミュレータを開く
#
# DHU が閉じたり、スマホの線が抜けたりしたら、2〜4 をやり直す。Ctrl+C で止める（開発サーバーと中継は残す）。
# DHU へは、$DHU_FIFO に `screenshot <パス>` などを書くと渡る。

APP=${APP:-com.tabireco.app.dev}
VALHALLA_URL=${VALHALLA_URL:-https://tabireco-routing-6qjfx5tzba-an.a.run.app}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
DHU_DIR=$SDK/extras/google/auto
SIM=${0:A:h:h}
LOG=${TMPDIR:-/tmp}/tabireco-demo
DHU_FIFO=$LOG/dhu.fifo
mkdir -p $LOG

say() { print -- "$(date +%T) $*" }
listening() { lsof -nP -iTCP:$1 -sTCP:LISTEN >/dev/null 2>&1 }

start_servers() {
  if ! listening 8790; then
    say 'スマホ用の中継を開きます（8790）'
    (cd $SIM && VALHALLA_URL=$VALHALLA_URL nohup node tools/phone-proxy.mjs >> $LOG/proxy.log 2>&1 &)
  fi
  if ! listening 5173; then
    say 'シミュレータの開発サーバーを開きます（5173）'
    (cd $SIM && nohup npx vite >> $LOG/vite.log 2>&1 &)
  fi
  for _ in {1..30}; do listening 5173 && listening 8788 && listening 8790 && return; sleep 1; done
  say '開発サーバーか中継が開きません。ログを見てください：' $LOG
}

# スマホ側のヘッドユニットサーバー（5277）が待っているか
headunit_ready() { $ADB shell 'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' | grep -qi ':149D ' }

# Android Auto の設定の右上のメニューから「ヘッドユニットサーバーを起動」を押す。
# 文字で場所を探すので、画面の大きさが違うスマホでも動く。開発者モードを先に有効にしておくこと
start_headunit() {
  headunit_ready && return
  say 'スマホのヘッドユニットサーバーを起動します'
  $ADB shell input keyevent KEYCODE_WAKEUP
  $ADB shell am start -n com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity >/dev/null
  sleep 3
  tap_text '' 'more_vert|その他のオプション|More options' || $ADB shell input tap $(( $($ADB shell wm size | grep -o '[0-9]*x' | tr -d x) - 40 )) 110
  sleep 1.5
  tap_text 'ヘッドユニットサーバーを起動|Start head unit server' ''
  sleep 2
  $ADB shell input keyevent KEYCODE_BACK
  headunit_ready || say 'ヘッドユニットサーバーを起動できませんでした。スマホの Android Auto の設定の右上のメニューから起動してください'
}

# 画面の部品を、文字（text）か説明（content-desc）で探して真ん中を押す
tap_text() {
  local xml=$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null)
  local pattern attribute
  if [[ -n $1 ]]; then pattern=$1; attribute=text; else pattern=$2; attribute=content-desc; fi
  local bounds=$(print -r -- $xml | grep -oE "<node[^>]*${attribute}=\"(${pattern})\"[^>]*>" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  [[ -z $bounds ]] && return 1
  local n=(${(s: :)${bounds//[^0-9]/ }})
  $ADB shell input tap $(( (n[1] + n[3]) / 2 )) $(( (n[2] + n[4]) / 2 ))
}

connect_phone() {
  say 'スマホを待っています（USB でつないでください）'
  $ADB wait-for-device
  $ADB reverse tcp:8788 tcp:8788 >/dev/null
  $ADB reverse tcp:8790 tcp:8790 >/dev/null
  $ADB forward tcp:5277 tcp:5277 >/dev/null
  start_headunit
}

open_app() {
  $ADB shell input keyevent KEYCODE_WAKEUP
  $ADB shell am start -n $APP/com.theacar.android.navigation.NavigationActivity >/dev/null 2>&1
  $ADB shell am broadcast -n $APP/com.theacar.android.debug.SimulatorLinkReceiver -a com.theacar.debug.SIMULATOR >/dev/null
}

trap 'say 止めます; pkill -f "desktop-head-unit" ; exit 0' INT TERM

start_servers
open -a 'Google Chrome' http://localhost:5173
while true; do
  connect_phone
  open_app
  say '車の画面（DHU）を開きます'
  rm -f $DHU_FIFO; mkfifo $DHU_FIFO
  # 管でつなぐと、DHU が閉じても tail が残って次へ進まない。DHU だけを待つ
  (cd $DHU_DIR && ./desktop-head-unit -c config/all_720p.ini < <(tail -f $DHU_FIFO) >> $LOG/dhu.log 2>&1)
  pkill -f "tail -f $DHU_FIFO"
  say 'DHU が閉じました。つなぎ直します'
  sleep 3
done
