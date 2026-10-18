const room = location.pathname.split('/')[2];
const status = document.querySelector('#status');
const form = document.querySelector('#search');
async function api(path, data) {
  const response = await fetch('/api/' + room + '/' + path, data ? {method:'POST', headers:{'Content-Type':'application/json'},body:JSON.stringify(data)} : {});
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || '通信に失敗しました。');
  return result;
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(data) {
  const job = await api('submit', data);
  for (let i=0; i<45; i++) {
    await pause(1000);
    const result = await api('result/' + job.id);
    if (result.status === 'failed') throw new Error(result.error);
    if (result.status === 'done') return result;
  }
  throw new Error('応答を待っています。追加操作は自動で再送しません。履歴を確認してください。');
}
function showTracks(tracks) {
  const container = document.querySelector('#results'); container.replaceChildren();
  for (const track of tracks) {
    const row = document.createElement('article'); row.className='track';
    const info = document.createElement('div');
    const title = document.createElement('strong'); title.textContent=track.title;
    const artist = document.createElement('p'); artist.textContent=track.artist;
    const link = document.createElement('a'); link.textContent='Spotifyで見る'; link.href='https://open.spotify.com/track/' + track.uri.split(':')[2]; link.target='_blank'; link.rel='noreferrer';
    info.append(title, artist, link);
    const button = document.createElement('button'); button.textContent='追加';
    button.onclick=async()=>{
      button.disabled=true; status.textContent='Spotifyに送信しています…';
      try { await request({kind:'add',uri:track.uri}); button.textContent='追加済み'; status.textContent='Spotifyへ送信しました。'; await history(); }
      catch(error) { status.textContent=error.message; button.textContent='結果を履歴で確認'; }
    };
    row.append(info,button); container.append(row);
  }
}
form.onsubmit=async event=>{
  event.preventDefault(); const button=form.querySelector('button'); button.disabled=true; status.textContent='検索しています…';
  try {const result=await request({kind:'search',query:document.querySelector('#query').value}); showTracks(result.tracks); status.textContent=result.tracks.length ? '好きな曲を追加してください。' : '曲が見つかりませんでした。';}
  catch(error){status.textContent=error.message;}
  finally{button.disabled=false;}
};
async function history() {
  const result=await api('result'); const list=document.querySelector('#history'); list.replaceChildren();
  for(const track of result.history) {const item=document.createElement('li'); item.textContent=track.title+' / '+track.artist; list.append(item);}
}
history().catch(error=>status.textContent=error.message);
setInterval(()=>history().catch(error=>status.textContent=error.message),10000);
