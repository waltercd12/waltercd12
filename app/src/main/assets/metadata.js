(()=>{'use strict';
  const cover=document.getElementById('albumCover');
  const song=document.getElementById('song');
  const trackArtist=document.getElementById('trackArtist');
  const coverLabel=document.getElementById('albumLabel');
  const FALLBACK='icon-512.png';
  const REFRESH_MS=15000;
  let lastTitle='', lastCover='', inFlight=false, pollTimeout=null;
  const isNative=!!(window.RadioAndroid && typeof window.RadioAndroid.refreshNowPlaying==='function');
  const qp=new URLSearchParams(window.location.search);
  const endpoint=(()=>{try{
    const custom=qp.get('nowplaying');
    const u=new URL(custom || 'nowplaying.php',window.location.href);
    if (u.protocol==='https:' || u.origin===window.location.origin) return u.href;
  }catch(e){}return null})();
  function display(data){
    if (!data || typeof data!=='object') return;
    const text=typeof data.title==='string'?data.title.trim():'';
    const artist=typeof data.artist==='string'?data.artist.trim():'';
    const title=typeof data.track==='string'?data.track.trim():'';
    const art=typeof data.cover==='string'?data.cover.trim():'';
    const safeCover=art.startsWith('https://') && art.length<2048 ? art : '';
    if(text && text!==lastTitle){
      song.textContent=title || text;
      trackArtist.textContent=artist || 'Radio Schipluiden';
      lastTitle=text;
      song.classList.remove('song-updated');
      void song.offsetWidth;
      song.classList.add('song-updated');
    }
    // Laat een oude albumhoes NOOIT staan als de zender een ander nummer speelt.
    if((text && lastTitle===text) && (safeCover!==lastCover)){
      lastCover=safeCover;
      cover.src=safeCover || FALLBACK;
      coverLabel.textContent=safeCover?'Albumhoes van het huidige nummer':'Radio Schipluiden-logo';
    }
    try{
      if('mediaSession' in navigator && !isNative && text){
        navigator.mediaSession.metadata=new MediaMetadata({
          title:title || text,artist:artist || 'Radio Schipluiden',album:'ON AIR',
          artwork:[{src:cover.src,sizes:'512x512'}]
        });
      }
    }catch(e){}
  }
  if(cover){cover.addEventListener('error',()=>{
    if(!cover.src.endsWith('/'+FALLBACK))cover.src=FALLBACK;
    coverLabel.textContent='Radio Schipluiden-logo';
  });}
  async function poll(){
    if(document.hidden || inFlight) return;
    if(isNative){try{window.RadioAndroid.refreshNowPlaying();}catch(e){}return;}
    if(!endpoint)return;
    inFlight=true;
    const abort=new AbortController();
    const timer=setTimeout(()=>abort.abort(),8000);
    try{
      const separator=endpoint.indexOf('?')>=0?'&':'?';
      const r=await fetch(endpoint+separator+'_rscp='+Date.now(),{cache:'no-store',signal:abort.signal});
      if(!r.ok) return;
      const data=await r.json();
      display(data);
    }catch(e){}finally{clearTimeout(timer);inFlight=false;}
  }
  window.RadioNowPlaying={receive:display,refresh:poll};
  coverLabel.textContent='Radio Schipluiden-logo';
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)poll();});
  window.addEventListener('focus',poll);
  poll();
  pollTimeout=setInterval(poll,REFRESH_MS);
})();
