/* Personality layer: places tabs with live local time, playful card titles that follow the
   Personality setting, tap wobble, pull to refresh, and small weather moments (confetti on a
   perfect day, raindrops on the screen edge, frost in the corners). No data is invented here.
   Motion is skipped under prefers-reduced-motion. */
(() => {
 const prefs=window.BetweenPreferences,still=matchMedia('(prefers-reduced-motion: reduce)').matches,el=(t,x,c)=>{const e=document.createElement(t);if(x!==undefined)e.textContent=x;if(c)e.className=c;return e};
 const choose=p=>window.choosePlace?.(p);

 // ---------- places: Current location + Buffalo + Mumbai, each with its local time
 // Zeus: Home and Work from Themis > Places when they are named; else Buffalo and Mumbai.
 let mine=null;try{mine=JSON.parse(Weather.places?.()||'null')}catch(e){}
 const HERE={id:'here',name:'Current location',short:'Here'},KNOWN_TZ={buffalo:'America/New_York',mumbai:'Asia/Kolkata'};
 let PLACES=[HERE];
 const tabs=document.querySelector('.location-tabs');tabs.replaceChildren();
 const fmt=(tz)=>{try{return new Date().toLocaleTimeString('en-US',{timeZone:tz||undefined,hour:'numeric',minute:'2-digit'})}catch(e){return new Date().toLocaleTimeString('en-US',{hour:'numeric',minute:'2-digit'})}};
 const isHere=()=>!!place?.here;
 const near=(a,b)=>Math.abs(a.lat-b.lat)<.3&&Math.abs(a.lon-b.lon)<.3;
 const idOf=()=>!place?'':isHere()?'here':PLACES.find(p=>p.lat&&Math.abs(p.lat-place.lat)<.05&&Math.abs(p.lon-place.lon)<.05)?.id||'';
 // Saved places: Themis Home/Work take the city name of the saved favourite at the same spot (Buffalo, not "Home"),
 // then every other saved favourite, so none are dropped.
 function buildPlaces(){const favs=prefs.get().favorites||[],out=[HERE],add=(p,name)=>{if(!p||!isFinite(p.lat)||!isFinite(p.lon)||out.some(o=>o.lat&&near(o,p)))return;
   const key=(name||'').toLowerCase();out.push({id:'p'+Math.round(p.lat*100)+'_'+Math.round(p.lon*100),lat:p.lat,lon:p.lon,name,country:p.country||'',tz:p.tz||KNOWN_TZ[key]})};
  let mine=null;try{mine=JSON.parse(Weather.places?.()||'null')}catch(e){}
  if(Array.isArray(mine))for(const m of mine)add(m,favs.find(f=>near(f,m))?.name||m.name);
  for(const f of favs)add(f,f.name);
  if(out.length===1)for(const c of [{lat:42.8864,lon:-78.8784,name:'Buffalo',country:'US'},{lat:19.076,lon:72.8777,name:'Mumbai',country:'IN'}])add(c,c.name);
  return out}
 function drawTabs(){const next=buildPlaces(),sig=JSON.stringify(next.map(p=>[p.id,p.name]));if(sig===drawTabs.sig)return;drawTabs.sig=sig;PLACES=next;
  const hidden=tabs.querySelector('[data-place=here]')?.hidden;tabs.replaceChildren();
  for(const p of PLACES){const b=el('button',undefined,'place-tab');b.dataset.place=p.id;b.append(el('span',p.id==='here'?p.short:p.name,'place-name'),el('span','','place-time'));
   b.onclick=()=>{if(p.id==='here'){if(Weather.locate)Weather.locate();else navigator.geolocation?.getCurrentPosition(g=>window.deviceLocation({lat:g.coords.latitude,lon:g.coords.longitude,name:'Current location'}),()=>window.locationDenied())}else choose({lat:p.lat,lon:p.lon,name:p.name,country:p.country})};tabs.append(b)}
  const h=tabs.querySelector('[data-place=here]');if(h&&(hidden||prefs.get().hereDenied||Weather.hereAllowed?.()===false))h.hidden=true}
 drawTabs();
 const hereTab={set hidden(v){const h=tabs.querySelector('[data-place=here]');if(h)h.hidden=v;this._h=v}};
 // Android tells us when location permission is declined: hide the tab rather than nag.
 window.locationDenied=()=>{prefs.get().hereDenied=true;prefs.save();hereTab.hidden=true};
 window.locationUnavailable=()=>{hereTab.hidden=true;if(isHere()){}};
 const baseLocate=window.deviceLocation;window.deviceLocation=p=>{prefs.get().hereDenied=false;hereTab.hidden=false;baseLocate({...p,name:'Current location',here:true})};
 const localTime=el('p','','localtime');document.querySelector('header h1').after(localTime);
 function tick(){drawTabs();for(const p of PLACES){const b=tabs.querySelector(`[data-place="${p.id}"]`);if(b)b.querySelector('.place-time').textContent=p.id==='here'||p.tz?fmt(p.id==='here'?undefined:p.tz):''}
  const id=idOf();tabs.querySelectorAll('button').forEach(b=>{if(b.dataset.place===id)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current')});
  localTime.textContent=place?fmt(data?.timezone)+' local time':''}
 setInterval(()=>{if(!document.hidden)tick()},15000);

 // ---------- titles and empty states with personality
 const T={
  hourly:['Hour by hour','The next 24 hours, roughly','Hour by hour, if you must'],daily:['The next 10 days','Ten days of educated guesses','Ten days. Plan accordingly.'],
  details:['Right now','The vital signs','Numbers you will ignore'],uv:['UV index','Sunburn forecast','How fast you will fry'],'next-hour':['Next 2 hours','Umbrella? Umbrella.','Will you get wet?'],
  'precip-24':['Rain and snow, next 24 hours','Umbrella math','Will the sky ruin your day?'],radar:['Radar','What is coming','Incoming'],'day-plan':['Today','Your day, planned','Life advice nobody asked for'],
  'sun-path':['Sun path','Where the sun is at','The big lamp'],moon:['Moon','Moon report','The moon, being dramatic'],holidays:['Holidays','Days off, maybe','Excuses to skip work'],alerts:['Weather alerts','Official warnings','Things that might hurt you'],changes:['What changed since yesterday','Plot twists since yesterday','What the sky changed behind your back']};
 const NO_ALERTS=['No active alerts.','No alerts. Nothing is trying to get you today.','No alerts. Honestly, a little disappointing.'];
 const toneIndex=()=>({mild:1,hot:2,nuclear:2})[prefs.get().level]??-1;
 const PLAIN={hourly:'Hourly forecast',daily:'10-day forecast',details:'Right now',uv:'UV index','next-hour':'Next 2 hours','precip-24':'Precipitation, next 24 hours',radar:'Radar','day-plan':'Today','sun-path':'Sun path',moon:'Moon',holidays:'Holidays',alerts:'Weather alerts',changes:'What changed since yesterday'};
 function titles(){const k=toneIndex();for(const card of document.querySelectorAll('main>[data-card-id]')){const h=card.querySelector(':scope>h2'),id=card.dataset.cardId,want=k<0?PLAIN[id]:T[id]?.[k];if(h&&want&&h.textContent!==want)h.textContent=want}
  const s=document.querySelector('.nws-status');if(k>=0&&s&&s.textContent.startsWith('No active alerts.')){const rest=s.textContent.slice('No active alerts.'.length);if(NO_ALERTS[k]!=='No active alerts.')s.textContent=NO_ALERTS[k]+rest}}
 new MutationObserver(()=>titles()).observe(document.querySelector('main'),{childList:true,subtree:true,characterData:true});
 document.getElementById('tone-choice')?.addEventListener('change',titles);

 // ---------- tap a tile: it wobbles
 document.querySelector('main').addEventListener('click',e=>{const t=e.target.closest('.metric');if(!t||still)return;t.classList.remove('wobble');void t.offsetWidth;t.classList.add('wobble')});
 document.querySelector('main').addEventListener('animationend',e=>{if(e.animationName==='bw-wobble')e.target.classList.remove('wobble')});

 // ---------- pull to refresh, with a spinning sun
 const pull=el('div',undefined,'pull');pull.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5"/><path d="M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M4.6 19.4l2.1-2.1M17.3 6.7l2.1-2.1"/></svg>';document.body.append(pull);
 let startY=null,dist=0;
 addEventListener('touchstart',e=>{startY=scrollY<=0&&!document.querySelector('dialog[open]')&&!e.target.closest('.radar-map,.hours,#days')?e.touches[0].clientY:null;dist=0},{passive:true});
 addEventListener('touchmove',e=>{if(startY==null)return;dist=Math.max(0,e.touches[0].clientY-startY);if(dist<=0)return;const k=Math.min(1,dist/110);pull.style.opacity=k;pull.style.transform=`translate(-50%,${Math.min(dist*.6,80)}px) rotate(${dist*2}deg) scale(${.6+.4*k})`},{passive:true});
 addEventListener('touchend',()=>{if(startY==null)return;startY=null;if(dist>110){pull.classList.add('spinning');refresh();setTimeout(()=>{pull.classList.remove('spinning');pull.style.opacity=0;pull.style.transform=''},1400)}else{pull.style.opacity=0;pull.style.transform=''}});

 // ---------- weather moments
 const fx=el('div',undefined,'fx');fx.setAttribute('aria-hidden','true');document.body.append(fx);
 function moments(){
  fx.replaceChildren();if(!data)return;const c=data.current,code=c.weather_code,rainy=code>=51&&code<=67||code>=80&&code<=82||code>=95;
  document.body.classList.toggle('frosty',c.temperature_2m<=0);
  if(rainy&&!still)for(let i=0;i<10;i++){const d=el('i','','edge-drop');d.style.left=(i%2?'calc(100% - '+(4+Math.random()*10)+'px)':(2+Math.random()*10)+'px');d.style.animationDelay=(Math.random()*6).toFixed(2)+'s';d.style.animationDuration=(3+Math.random()*3).toFixed(2)+'s';fx.append(d)}
  // Perfect day: clear, daytime, 18 to 26 C, light wind, low rain chance. Once per day per city.
  const i=Math.max(0,data.daily.time.indexOf(data.current.time.slice(0,10))),perfect=code<=1&&c.is_day===1&&c.temperature_2m>=18&&c.temperature_2m<=26&&c.wind_speed_10m<20&&(data.daily.precipitation_probability_max?.[i]??0)<20;
  const key='confetti-'+data.current.time.slice(0,10)+'-'+(place?.name||'');let seen=false;try{seen=localStorage.getItem(key)==='1';localStorage.setItem(key,'1')}catch(e){}
  if(perfect&&!seen&&!still)confetti();
 }
 function confetti(){window.WeatherAchievements?.unlock('perfect');const char=document.querySelector('.hero-char');char?.classList.remove('hop');void char?.offsetWidth;char?.classList.add('hop');return;const colors=['#ffb547','#ff7a59','#7fd8ff','#b9a6ff','#7be0a0'];for(let i=0;i<70;i++){const p=el('i','','confetti');p.style.left=(Math.random()*100)+'vw';p.style.background=colors[i%colors.length];p.style.animationDelay=(Math.random()*.6).toFixed(2)+'s';p.style.setProperty('--x',(Math.random()*160-80).toFixed(0)+'px');p.style.setProperty('--r',(Math.random()*720-360).toFixed(0)+'deg');fx.append(p)}setTimeout(()=>fx.querySelectorAll('.confetti').forEach(p=>p.remove()),3600)}

 const render=renderWeather;renderWeather=function(d){render(d);if(data===d){tick();titles();moments()}};
 tick();titles();if(data)moments();
 // Swipe the hero left or right to move between places (a horizontal pager over the text row).
 {let sx=null,sy=0;const hero=document.querySelector('.hero');
  hero.addEventListener('touchstart',e=>{const t=e.touches[0];sx=e.target.closest('button,a,input')?null:t.clientX;sy=t.clientY},{passive:true});
  hero.addEventListener('touchend',e=>{if(sx==null)return;const t=e.changedTouches[0],dx=t.clientX-sx,dy=t.clientY-sy;sx=null;if(Math.abs(dx)<60||Math.abs(dy)>45)return;
   const list=[...tabs.querySelectorAll('button:not([hidden])')],cur=list.findIndex(b=>b.getAttribute('aria-current')==='page'),next=list[Math.max(0,Math.min(list.length-1,(cur<0?0:cur)+(dx<0?1:-1)))];
   if(!next||next.getAttribute('aria-current')==='page')return;const txt=document.querySelector('.hero-text');txt.classList.remove('slide-l','slide-r');void txt.offsetWidth;txt.classList.add(dx<0?'slide-l':'slide-r');next.click()},{passive:true});}
 // From the Places widget (Android re-validates the values before calling this).
 window.openPlace=p=>{if(!p||!Number.isFinite(p.lat)||!Number.isFinite(p.lon))return;if(p.here)window.deviceLocation({lat:p.lat,lon:p.lon});else window.choosePlace({lat:p.lat,lon:p.lon,name:String(p.name||'').slice(0,80),country:String(p.country||'').slice(0,3)})};
 // Settings: live app icon.
 {const sheet=document.getElementById('weather-settings'),lab=el('label',undefined,'toggle'),box=document.createElement('input');box.type='checkbox';box.checked=prefs.get().liveIcon!==false;
  lab.append(box,document.createTextNode(' Live icon'));const note=el('p','The app icon changes with the weather (checked at most every 30 minutes). Some launchers briefly refresh or move the icon when it switches. For a live temperature, add the 1x1 Icon widget.');
  box.onchange=()=>{prefs.get().liveIcon=box.checked;prefs.save()};const before=sheet.querySelector('#add-widget-settings');sheet.insertBefore(lab,before);sheet.insertBefore(note,before)}
 // ---------- Settings: personality in notifications, and the wake-up forecast.
 {const sheet=document.getElementById('weather-settings'),before=sheet.querySelector('#add-widget-settings');
  const vl=el('label',undefined,'toggle'),vb=document.createElement('input');vb.type='checkbox';vb.checked=prefs.get().notifyVoice!==false;vl.append(vb,document.createTextNode(' Personality in notifications'));
  vb.onchange=()=>{prefs.get().notifyVoice=vb.checked;prefs.save()};
  const vn=el('p','Notifications keep the facts first; Nimbus adds one line after them. Severe warnings always use the Professional voice.');
  const wl=el('label',undefined,'toggle'),wb=document.createElement('input');wb.type='checkbox';const wt=document.createElement('input');wt.type='time';wt.value='07:00';wt.className='wake-time';
  wl.append(wb,document.createTextNode(' Wake-up forecast at '),wt);
  const wn=el('p','At this time the phone reads Nimbus\u2019s line and today\u2019s forecast aloud. The line is written ahead of time while the app is open in the evening.');
  const exact=el('button','Allow exact alarm timing');exact.hidden=true;exact.onclick=()=>Weather.openExactAlarmSettings?.();
  try{const w=JSON.parse(Weather.wakeAlarm?.()||'null');if(w){wb.checked=w.on;wt.value=w.time;exact.hidden=w.exact}}catch(e){}
  const apply=()=>{Weather.setWakeAlarm?.(wb.checked,wt.value||'07:00');if(wb.checked)writeWakeLine(true)};wb.onchange=apply;wt.onchange=apply;
  for(const n of [vl,vn,wl,wn,exact])sheet.insertBefore(n,before);}
 // Tomorrow's line for the alarm: written in the evening (or right after the alarm is set). Facts are tomorrow's forecast only.
 function writeWakeLine(force){try{const w=JSON.parse(Weather.wakeAlarm?.()||'null');if(!w?.on||!data)return;const h=new Date().getHours();/* phone time on purpose: this is the phone owner's own evening, not the selected place's */if(!force&&h<18)return;
  const k='wake-'+new Date().toDateString();if(!force&&store.get(k))return;store.set(k,1);const d=data.daily,F=c=>Math.round(c*9/5+32),level=prefs.get().level==='off'?'mild':prefs.get().level;
  const f={tomorrow:true,city:place?.name,condition:describe(d.weather_code[1]),code:d.weather_code[1],isDay:true,highF:F(d.temperature_2m_max[1]),highC:Math.round(d.temperature_2m_max[1]),lowF:F(d.temperature_2m_min[1]),tempC:Math.round(d.temperature_2m_max[1]),windMph:0,rainChance:d.precipitation_probability_max?.[1]??0,uv:Math.round(d.uv_index_max?.[1]??0),situation:'this line will be read aloud at the wake-up alarm tomorrow morning; talk about tomorrow'};
  const pool=window.WeatherPersonality?.poolLine({...f,tempC:f.highC},level,{kind:'first'},'wake')||'';Weather.setWakeLine?.(pool);
  if(Weather.quip)Weather.quip(JSON.stringify(f),level,'wake:'+Date.now())}catch(e){console.warn('wake line',e)}}
 (window.NanoRoutes||(window.NanoRoutes={}))['wake:']=r=>{if(r.text)Weather.setWakeLine?.(r.text)};
 const store={get(k){try{return localStorage.getItem('fun-'+k)}catch(e){return null}},set(k,v){try{localStorage.setItem('fun-'+k,v)}catch(e){}}};
 const wakeRender=renderWeather;renderWeather=function(d){wakeRender(d);if(data===d)setTimeout(()=>writeWakeLine(false),2000)};
 window.WeatherFun={titles,confetti};
})();
