(() => {
  'use strict';
  const card = document.querySelector('.sticker');
  const panel = document.querySelector('.sticker-editor');
  if (!card || !panel) return;
  const layer = document.createElement('div');
  layer.className = 'sticker-layer';
  layer.setAttribute('aria-label', '配置したステッカー');
  card.append(layer);
  const controls = document.getElementById('editor-controls');
  const size = document.getElementById('sticker-size');
  const angle = document.getElementById('sticker-angle');
  const status = document.getElementById('editor-status');
  const upload = document.getElementById('sticker-upload');
  const key = 'tabireco-stickers-v1:' + location.pathname;
  const symbols = Array.from(panel.querySelectorAll('[data-sticker]'), b => b.dataset.sticker);
  const imagePresets = Array.from(panel.querySelectorAll('[data-sticker-image]'), b => b.dataset.stickerImage);
  let items = [], selected = null, drag = null;
  const clamp = (v, min, max) => Math.min(max, Math.max(min, v));
  const announce = text => { status.textContent = text; };
  function save() {
    try {
      localStorage.setItem(key, JSON.stringify(items));
      announce('このブラウザに保存しました。画像はサーバーに送信されません。');
    } catch (_) { announce('保存容量が足りないか、保存が無効です。今回の編集は再読み込みすると失われます。'); }
  }
  function select(id) {
    selected = id;
    controls.hidden = !id;
    layer.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.id === id)));
    const item = items.find(i => i.id === id);
    if (item) { size.value = item.size; angle.value = item.angle; }
  }
  function position(button, item) {
    button.style.left = item.x + '%'; button.style.top = item.y + '%';
    button.style.width = item.size + '%';
    button.style.fontSize = (card.clientWidth * item.size / 100 * .8) + 'px';
    button.style.transform = 'translate(-50%,-50%) rotate(' + item.angle + 'deg)';
  }
  function render() {
    layer.replaceChildren();
    items.forEach(item => {
      const b = document.createElement('button');
      b.type = 'button'; b.className = 'placed-sticker'; b.dataset.id = item.id;
      b.setAttribute('aria-label', item.kind === 'image' ? (imagePresets.includes(item.value) ? (Array.from(panel.querySelectorAll('[data-sticker-image]')).find(p => p.dataset.stickerImage === item.value)?.title || 'ステッカー') + '。矢印キーで移動' : '追加した写真。矢印キーで移動') : item.value + ' ステッカー。矢印キーで移動');
      b.setAttribute('aria-pressed', String(selected === item.id));
      if (item.kind === 'image') {
        const img = document.createElement('img'); img.src = item.value; img.alt = ''; img.draggable = false; b.append(img);
      } else b.textContent = item.value;
      position(b, item);
      b.addEventListener('click', () => select(item.id));
      b.addEventListener('pointerdown', e => {
        if (e.button !== 0 || drag) return;
        select(item.id);
        drag = { id: item.id, pointer: e.pointerId, x: e.clientX, y: e.clientY, startX: item.x, startY: item.y };
        b.setPointerCapture(e.pointerId);
      });
      b.addEventListener('pointermove', e => {
        if (!drag || drag.id !== item.id || drag.pointer !== e.pointerId) return;
        const rect = card.getBoundingClientRect();
        item.x = clamp(drag.startX + (e.clientX - drag.x) / rect.width * 100, 5, 95);
        item.y = clamp(drag.startY + (e.clientY - drag.y) / rect.height * 100, 5, 95);
        position(b, item);
      });
      const finish = e => { if (drag && drag.pointer === e.pointerId) { drag = null; save(); } };
      b.addEventListener('pointerup', finish); b.addEventListener('pointercancel', finish); b.addEventListener('lostpointercapture', finish);
      b.addEventListener('keydown', e => {
        const step = e.shiftKey ? 5 : 1;
        if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Delete', 'Backspace', 'Escape'].includes(e.key)) return;
        e.preventDefault(); select(item.id);
        if (e.key === 'Escape') { select(null); return; }
        if (e.key === 'Delete' || e.key === 'Backspace') { remove(); return; }
        item.x = clamp(item.x + (e.key === 'ArrowRight' ? step : e.key === 'ArrowLeft' ? -step : 0), 5, 95);
        item.y = clamp(item.y + (e.key === 'ArrowDown' ? step : e.key === 'ArrowUp' ? -step : 0), 5, 95);
        position(b, item); save();
      });
      layer.append(b);
    });
  }
  function add(kind, value) {
    if (items.length >= 20) { announce('追加できるのは20個までです。不要なステッカーを削除してください。'); return; }
    const item = { id: crypto.randomUUID(), kind, value, x: 50, y: 32, size: kind === 'image' ? 35 : 22, angle: 0 };
    items.push(item); selected = item.id; render(); select(item.id); save();
  }
  function remove() {
    items = items.filter(i => i.id !== selected); select(null); render(); save();
    upload.focus();
  }
  panel.querySelectorAll('[data-sticker]').forEach(b => b.addEventListener('click', () => add('emoji', b.dataset.sticker)));
  panel.querySelectorAll('[data-sticker-image]').forEach(b => b.addEventListener('click', () => add('image', b.dataset.stickerImage)));
  for (const input of [size, angle]) input.addEventListener('input', () => {
    const item = items.find(i => i.id === selected); if (!item) return;
    item.size = Number(size.value); item.angle = Number(angle.value);
    position(layer.querySelector('[data-id="' + item.id + '"]'), item);
  });
  for (const input of [size, angle]) input.addEventListener('change', save);
  document.getElementById('sticker-remove').addEventListener('click', remove);
  document.getElementById('sticker-front').addEventListener('click', () => {
    const index = items.findIndex(i => i.id === selected); if (index < 0) return;
    items.push(items.splice(index, 1)[0]); render(); save();
  });
  document.getElementById('sticker-done').addEventListener('click', () => { select(null); save(); upload.focus(); });
  card.addEventListener('click', e => { if (!e.target.closest('.placed-sticker')) select(null); });
  upload.addEventListener('change', async () => {
    const file = upload.files[0]; upload.value = ''; if (!file) return;
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024) {
      announce('10 MB以下のJPG・PNG・WebP画像を選んでください。'); return;
    }
    if (items.length >= 20) { announce('追加できるのは20個までです。'); return; }
    const url = URL.createObjectURL(file);
    try {
      const img = new Image(); img.src = url; await img.decode();
      const scale = Math.min(1, 800 / Math.max(img.naturalWidth, img.naturalHeight));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(img.naturalWidth * scale)); canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
      add('image', canvas.toDataURL('image/webp', .85));
    } catch (_) { announce('この画像を読み込めませんでした。別の画像を選んでください。'); }
    finally { URL.revokeObjectURL(url); }
  });
  try {
    const stored = JSON.parse(localStorage.getItem(key) || '[]');
    if (Array.isArray(stored)) items = stored.filter(i => i && typeof i.id === 'string' &&
      ['x', 'y', 'size', 'angle'].every(k => Number.isFinite(i[k])) && i.x >= 5 && i.x <= 95 && i.y >= 5 && i.y <= 95 &&
      i.size >= 10 && i.size <= 65 && Math.abs(i.angle) <= 180 && typeof i.value === 'string' &&
      ((i.kind === 'emoji' && symbols.includes(i.value)) || (i.kind === 'image' && (imagePresets.includes(i.value) || /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(i.value)))))
      .slice(0, 20).map(i => ({ ...i, id: crypto.randomUUID() }));
  } catch (_) { announce('保存済みの配置を読み込めませんでした。新しく配置できます。'); }
  render();
  new ResizeObserver(() => layer.querySelectorAll('button').forEach((b, index) => position(b, items[index]))).observe(card);
})();
