/* "What changed since yesterday?" The page keeps a small snapshot of each day's forecast per
   place (in this browser's storage). Today it compares the live forecast with yesterday's
   snapshot: today's high/low against what was forecast yesterday, the rain chance, tomorrow's
   outlook, and alerts. The differences are computed here; Gemini Nano (or the written pool)
   only phrases them in the current personality. Nothing is shown without a real snapshot. */
(() => {
 const el=(t,x,c)=>{const e=document.createElement(t);if(x!==undefined)e.textContent=x;if(c)e.className=c;return e};
 const card=el('section',undefined,'card');card.dataset.cardId='changes';card.id='changes';card.append(el('h2','What changed since yesterday'));document.querySelector('main').append(card);
 const store={get(k,d){try{const v=localStorage.getItem(k);return v==null?d:JSON.parse(v)}catch(e){return d}},set(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}}};
 const F=c=>Math.round(c*9/5+32);
 const placeKey=()=>place?place.lat.toFixed(2)+','+place.lon.toFixed(2):'';
 function snapshot(){const d=data.daily,alerts=document.querySelectorAll('.nws-alert').length;return {at:Date.now(),time:d.time.slice(0,4),max:d.temperature_2m_max.slice(0,4),min:d.temperature_2m_min.slice(0,4),pp:(d.precipitation_probability_max||[]).slice(0,4),alerts}}
 function diffs(){
  const today=data.daily.time[0],k='snap-'+placeKey(),all=store.get(k,{}),dates=Object.keys(all).filter(x=>x<today).sort(),prev=dates.length?all[dates[dates.length-1]]:null;
  // Keep one snapshot per day (the first of the day), last 3 days.
  if(!all[today]){all[today]=snapshot();for(const x of Object.keys(all).sort().slice(0,-3))delete all[x];store.set(k,all)}
  if(!prev)return null;
  const out=[],idx=t=>prev.time.indexOf(t);
  const i0=idx(today);if(i0>=0){const dh=F(data.daily.temperature_2m_max[0])-F(prev.max[i0]),dl=F(data.daily.temperature_2m_min[0])-F(prev.min[i0]),dp=(data.daily.precipitation_probability_max?.[0]??0)-(prev.pp[i0]??0);
   if(Math.abs(dh)>=2)out.push(`Today's high is now ${F(data.daily.temperature_2m_max[0])}°F, ${Math.abs(dh)}°F ${dh>0?'warmer':'cooler'} than yesterday's forecast.`);
   if(Math.abs(dl)>=3)out.push(`Tonight's low is now ${F(data.daily.temperature_2m_min[0])}°F, ${Math.abs(dl)}°F ${dl>0?'milder':'colder'} than forecast.`);
   if(Math.abs(dp)>=20)out.push(`Rain chance today ${dp>0?'rose':'dropped'} to ${data.daily.precipitation_probability_max[0]}% (was ${prev.pp[i0]}%).`)}
  const t1=data.daily.time[1],i1=idx(t1);if(i1>=0){const dh=F(data.daily.temperature_2m_max[1])-F(prev.max[i1]),dp=(data.daily.precipitation_probability_max?.[1]??0)-(prev.pp[i1]??0);
   if(Math.abs(dh)>=3)out.push(`Tomorrow now looks ${Math.abs(dh)}°F ${dh>0?'warmer':'cooler'}, high ${F(data.daily.temperature_2m_max[1])}°F.`);
   if(Math.abs(dp)>=20)out.push(`Tomorrow's rain chance ${dp>0?'rose':'dropped'} to ${data.daily.precipitation_probability_max[1]}%.`)}
  const alerts=document.querySelectorAll('.nws-alert').length;if(alerts>prev.alerts)out.push(`${alerts-prev.alerts} new weather ${alerts-prev.alerts===1?'alert':'alerts'} since yesterday.`);
  return out;
 }
 let pending='',lastKey='';
 function draw(){
  card.replaceChildren(el('h2','What changed since yesterday'));if(!data||!place){card.append(el('p','Choose a city first.','empty'));return}
  const d=diffs();
  if(!d){card.append(el('p','Nothing to compare yet. I need one day of history; check back tomorrow.','empty'));return}
  if(!d.length){card.append(el('p','Nothing important changed since yesterday’s forecast.','lead'));return}
  const line=el('p','','lead changes-line'),list=el('ul',undefined,'changes-list');for(const x of d)list.append(el('li',x));
  card.append(line,list);
  // Personality line: written pool now, Nano phrasing if it arrives.
  const level=window.WeatherPersonality?.level()||'mild',f=window.WeatherDetails?.facts();
  line.textContent=level==='off'||!f?'':window.WeatherPersonality.poolLine(f,level==='off'?'mild':level,{kind:'changed',from:'yesterday’s forecast',to:'today’s'},'chg');
  const key='changes:'+placeKey()+':'+d.join('|');lastKey=key;
  if(f&&level!=='off'&&Weather.quip&&pending!==key){pending=key;try{Weather.quip(JSON.stringify({...f,situation:'Since yesterday: '+d.join(' ')+' Sum this up in one or two lines.'}),level,key)}catch(e){pending=''}}
 }
 (window.NanoRoutes||(window.NanoRoutes={}))['changes:']=r=>{if(r.id===lastKey&&r.text){const l=card.querySelector('.changes-line');if(l)l.textContent=r.text}};
 const css=el('style');css.textContent='.changes-list{margin:10px 0 0;padding-left:18px}.changes-list li{font-size:13px;line-height:1.5;color:var(--ink2);margin:3px 0}.changes-line .ai-mark{font-size:11px;color:var(--ink3);margin-left:8px}';document.head.append(css);
 const render=renderWeather;renderWeather=function(d){render(d);if(data===d)setTimeout(draw,300)};
 const alerts=window.renderAlerts;window.renderAlerts=d=>{alerts(d);if(data)draw()};
 if(data)setTimeout(draw,300);else draw();
})();
