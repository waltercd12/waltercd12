(()=>{"use strict";
const $=x=>document.getElementById(x), intro=$("intro"),stream=$("stream"),btn=$("play"),icon=$("playSvg"),stat=$("status"),lab=$("playlabel"),song=$("song");
let active=false,stage="off",generation=0,retryTimer=null,attempts=0;
const native=typeof window.RadioAndroid!=="undefined";
function message(s,error=false){stat.textContent=s;stat.classList.toggle("error",error)}
function draw(){btn.setAttribute("aria-pressed",active?"true":"false");btn.setAttribute("aria-label",active?"Stop de radio":"Start de radio");icon.innerHTML=active?'<path d="M6 5h4v14H6zm8 0h4v14h-4z"/>':'<path d="M8 5v14l11-7z"/>';lab.textContent=active?"TIK OM TE STOPPEN":"TIK OM TE LUISTEREN";if("mediaSession" in navigator && !native){navigator.mediaSession.playbackState=active?"playing":"none";}}
function cancelTimer(){if(retryTimer){clearTimeout(retryTimer);retryTimer=null}}
function stop(){generation++;active=false;stage="off";attempts=0;cancelTimer();intro.pause();intro.currentTime=0;stream.pause();stream.removeAttribute("autoplay");stream.load();message("Radio staat uit");draw();}
async function start(){if(active)return;generation++;const current=generation;attempts=0;active=true;stage="intro";draw();message("De jingle wordt afgespeeld…");intro.currentTime=0;try{await intro.play();}catch(e){if(current!==generation)return;stop();message("Jingle kon niet starten. Tik opnieuw om te luisteren.",true)}}
intro.addEventListener("ended",()=>{if(active && stage==="intro"){stage="radio";attemptStream();}});
intro.addEventListener("error",()=>{if(active && stage==="intro"){stop();message("De jingle ontbreekt of is niet beschikbaar.",true)}});
async function attemptStream(){if(!active||stage!=="radio")return;message("Verbinden met Radio Schipluiden…");try{await stream.play();}catch(e){if(active)scheduleRetry();}}
function scheduleRetry(){if(!active||stage!=="radio"||retryTimer)return;if(!navigator.onLine){message("Geen internet. Controleer je verbinding.",true);return}if(attempts++>=7){stop();message("Kan niet verbinden met de stream. Tik om opnieuw te proberen.",true);return;}message("Verbinding herstellen…");retryTimer=setTimeout(()=>{retryTimer=null;attemptStream()},Math.min(1500*attempts,10000));}
stream.addEventListener("playing",()=>{if(active && stage==="radio"){attempts=0;cancelTimer();message("Je luistert naar Radio Schipluiden");}});
stream.addEventListener("error",scheduleRetry);stream.addEventListener("stalled",scheduleRetry);stream.addEventListener("waiting",()=>{if(active&&stage==="radio")message("Even bufferen…")});
window.addEventListener("online",()=>{if(active&&stage==="radio"){cancelTimer();attempts=0;attemptStream()}});
window.addEventListener("offline",()=>{if(active&&stage==="radio")message("Geen internetverbinding",true)});
btn.addEventListener("click",()=>{if(native){try{window.RadioAndroid.toggle();}catch(e){message("Kan Android-speler niet bedienen",true)}return}if(active)stop();else start()});
window.RadioPlayer={setNativeState:function(s){if(!native)return;stage=s;active=(s==="intro"||s==="connecting"||s==="playing"||s==="buffering");draw();if(s==="intro")message("De jingle wordt afgespeeld…");else if(s==="playing")message("Je luistert naar Radio Schipluiden");else if(s==="connecting")message("Verbinden met de livestream…");else if(s==="buffering")message("Even bufferen…");else if(s==="error")message("Verbinding mislukt. Tik om opnieuw te proberen.",true);else message("Radio staat uit");}};
if("mediaSession" in navigator&&!native){try{navigator.mediaSession.metadata=new MediaMetadata({title:"Radio Schipluiden",artist:"De beste hits van nu en toen",artwork:[{src:"icon-512.png",sizes:"512x512",type:"image/png"}]});navigator.mediaSession.setActionHandler("play",()=>{if(!active)start()});navigator.mediaSession.setActionHandler("pause",stop);navigator.mediaSession.setActionHandler("stop",stop)}catch(e){}}
// De titel is optioneel; zonder werkende statusbron blijft de neutrale tracktekst staan.
async function trackInfo(){try{const u=native?"https://radioschipluiden.nl/app/nowplaying.php":"nowplaying.php";const c=new AbortController();const t=setTimeout(()=>c.abort(),3500);const r=await fetch(u,{cache:"no-store",signal:c.signal});clearTimeout(t);if(!r.ok)return;const j=await r.json();if(j.title&&typeof j.title==="string"&&j.title.length<180)song.textContent=j.title;}catch(e){}}
trackInfo();setInterval(trackInfo,20000);draw();
if(!native&&"serviceWorker" in navigator&&(location.protocol==="https:"||location.hostname==="localhost")){window.addEventListener("load",()=>navigator.serviceWorker.register("./service-worker.js").catch(()=>{}))}
})();