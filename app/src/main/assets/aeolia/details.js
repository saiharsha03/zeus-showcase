/* "Right now" metric tiles plus the UV, next-hour, 24-hour precipitation, today and moon cards.
   Every number shown comes from the forecast/air responses or SunCalc; nothing is estimated here. */
(() => {
 const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e},main=document.querySelector('main');
 const F=c=>Math.round(c*9/5+32),deg=c=>`${F(c)}°F / ${Math.round(c)}°C`;
 const css=el('style');css.textContent=`
 .metrics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
 @media(min-width:560px){.metrics{grid-template-columns:repeat(4,minmax(0,1fr))}}
 @media(min-width:1280px){.metrics{grid-template-columns:repeat(8,minmax(0,1fr))}}
 .metric{container-type:inline-size;aspect-ratio:1;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:12% 10%;min-width:0;position:relative;isolation:isolate}
 .metric::before{content:"";position:absolute;inset:0;z-index:-1;-webkit-mask-size:100% 100%;mask-size:100% 100%;background:var(--tint)}
 .metric .wx-mini{width:.62em;height:.62em;margin-right:.12em;align-self:center}
 .uv-head{display:flex;align-items:baseline;gap:12px;margin:0 0 4px}.uv-head strong{font-size:44px;font-weight:300;line-height:1;font-variant-numeric:tabular-nums}.uv-head span{font-size:16px}
 .uv-curve{width:100%;max-width:520px;height:auto;display:block;margin:10px 0 4px;overflow:visible}.uv-curve text{font-size:10px;fill:var(--ink3,#9a907f)}
 .metric label{font-size:12px;letter-spacing:.2px}
 .metric strong{display:flex;align-items:baseline;justify-content:center;gap:.12em;white-space:nowrap;font-weight:400;font-size:clamp(20px,17cqi,40px);line-height:1.1;margin:6px 0 4px;font-variant-numeric:tabular-nums}
 .metric strong .unit{font-size:.5em;opacity:.8}
 .metric small{font-size:11px;line-height:1.35;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
 .metric .compass{width:clamp(16px,11cqi,24px);height:clamp(16px,11cqi,24px);align-self:center;margin-right:.2em}
 .facts{display:grid;gap:14px;margin:0;padding:0}.facts div{display:grid;gap:3px}.facts dt{font-size:12px}.facts dd{margin:0;font-size:15px;line-height:1.45}
 .lead{font-size:16px;line-height:1.5;margin:0}
 #next-hour{display:flex;flex-direction:column}.bars{display:flex;align-items:flex-end;gap:6px;height:64px;margin:auto 0 6px;padding-top:16px;box-sizing:content-box}.bars i{flex:1;border-radius:4px 4px 1px 1px;min-height:3px}
 .bar-scale{display:flex;justify-content:space-between;font-size:11px}
 .moon{display:flex;gap:18px;align-items:center}.moon svg{width:84px;height:84px;flex:none}
 .p24{display:flex;align-items:flex-end;gap:3px;height:96px;margin:14px 0 6px}.p24 span{flex:1;position:relative;height:100%;display:flex;align-items:flex-end}
 .p24 .chance{position:absolute;left:0;right:0;bottom:0;border-radius:3px 3px 1px 1px;background:currentColor;opacity:.12}.p24 .amt{position:relative;width:100%;border-radius:3px 3px 1px 1px;min-height:0}
 .amt.rain{background:#7fb3c8}.amt.snow{background:#d3e3f2}:root[data-theme=light] .amt.rain{background:#2f6680}:root[data-theme=light] .amt.snow{background:#6f8aa5}
 .p24-scale{display:flex;justify-content:space-between;font-size:11px}.p24-key{display:flex;gap:16px;font-size:11px;margin-top:10px}.p24-key i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px;vertical-align:-1px}`;
 document.head.append(css);
 const card=(id,title)=>{const s=el('section',undefined,'card');s.dataset.cardId=id;s.id=id;s.append(el('h2',title));main.append(s);return s};
 const uvCard=card('uv','UV index'),nextHour=card('next-hour','Next 2 hours'),today=card('day-plan','Today'),moon=card('moon','Moon'),precip=card('precip-24','Precipitation, next 24 hours');
 let air=null,airLoaded=false;

 // Time formatting in the selected city's zone (instants), and for Open-Meteo local strings.
 const cityTime=ms=>{try{return new Date(ms).toLocaleTimeString('en-US',{timeZone:data.timezone,hour:'numeric',minute:'2-digit'})}catch(e){return new Date(ms).toLocaleTimeString([],{hour:'numeric',minute:'2-digit'})}};
 const instant=s=>Date.parse(s+'Z')-(data.utc_offset_seconds||0)*1000;
 /* Selected place's own clock (never the phone's): hour, date and part of day from data.utc_offset_seconds / data.timezone, nudged by current.is_day. */
 const partOf=(h,day)=>{let p=h<5?'late night':h<8?'early morning':h<12?'morning':h<17?'afternoon':h<21?'evening':'night';
  if(day===true&&p==='night')p='evening';else if(day===true&&p==='late night')p='early morning';else if(day===false&&(p==='morning'||p==='afternoon'))p=h<12?'early morning':'evening';return p};
 function placeTime(){const ms=Date.now(),isDay=data?.current?.is_day===1;let d,h,m;
  if(data&&typeof data.utc_offset_seconds==='number'){d=new Date(ms+data.utc_offset_seconds*1000);h=d.getUTCHours();m=d.getUTCMinutes();d=d.toISOString().slice(0,10)}
  else{let parts;try{parts=new Intl.DateTimeFormat('en-US',{timeZone:data?.timezone||undefined,hour:'numeric',minute:'numeric',hourCycle:'h23',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date(ms))}catch(e){parts=[]}
   const g=t=>+(parts.find(p=>p.type===t)?.value);if(parts.length){h=g('hour')%24;m=g('minute');d=g('year')+'-'+String(g('month')).padStart(2,'0')+'-'+String(g('day')).padStart(2,'0')}else{const n=new Date(ms);h=n.getHours();m=n.getMinutes();d=n.toDateString()}}
  return {hour:h,dateString:d,partOfDay:partOf(h,data?.current?data.current.is_day===1:undefined),localTimeText:(h%12||12)+':'+String(m).padStart(2,'0')+(h<12?' AM':' PM'),isDay}}
 /* The phone owner's own clock, for "the reader is far away" cases. */
 const phoneTime=()=>{const n=new Date();return {hour:n.getHours(),partOfDay:partOf(n.getHours()),far:!!data&&typeof data.utc_offset_seconds==='number'&&-n.getTimezoneOffset()*60!==data.utc_offset_seconds}};
 const cityDay=ms=>new Date(ms+(data.utc_offset_seconds||0)*1000).toISOString().slice(0,10);
 const todayIndex=()=>Math.max(0,data.daily.time.indexOf(cityDay(Date.now())));
 const compassName=d=>['N','NE','E','SE','S','SW','W','NW'][Math.round(d/45)%8];

 function tile(label,value,unit,sub,shape,extra,vars){const t=el('div',undefined,'metric');t.dataset.shape=shape;if(vars)for(const k in vars)t.style.setProperty(k,vars[k]);t.setAttribute('role','group');t.setAttribute('aria-label',`${label}: ${value}${unit?' '+unit:''}. ${sub}`);const s=el('strong');if(extra)s.append(extra);s.append(document.createTextNode(value));if(unit)s.append(el('span',unit,'unit'));t.append(el('label',label),s,el('small',sub));return t}
 function arrow(from){const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('class','compass');svg.setAttribute('aria-hidden','true');svg.innerHTML=`<circle cx="12" cy="12" r="10.5" fill="none" stroke="currentColor" stroke-opacity=".35"/><path d="M12 5l4 12-4-3-4 3z" fill="currentColor" transform="rotate(${(from+180)%360} 12 12)"/>`;return svg}
 function comfort(dew){return dew<10?'Dry air':dew<16?'Comfortable':dew<21?'Sticky':'Muggy'}
 function uvWord(u){return u<3?'Low':u<6?'Moderate':u<8?'High':u<11?'Very high':'Extreme'}
 // WHO UV bands, muted to sit on the warm palette (used for tile tint, curve and hourly label).
 const UV_BAND=[[0,'#6aa84f'],[3,'#d9b93a'],[6,'#e8913a'],[8,'#e0563f'],[11,'#a865b5']];
 const uvColor=u=>UV_BAND.filter(b=>u>=b[0]).pop()[1];
 const mix=(hex,a)=>`color-mix(in srgb, ${hex} 50%, var(--mixbase,#0d0b09))`;
 window.uvColor=uvColor;
 const AQI_BAND=[[0,'#5b9b4b'],[51,'#c9a838'],[101,'#de8a3a'],[151,'#d4503b'],[201,'#9a5aa8'],[301,'#8a3446']];
 const aqiColor=a=>AQI_BAND.filter(b=>a>=b[0]).pop()[1];
 const DROP='<path d="M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z" fill="currentColor"/>',FLAKE='<path d="M12 2v20M3.3 7l17.4 10M3.3 17 20.7 7" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" fill="none"/>';
 function mini(kind){const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('class','wx-mini');svg.setAttribute('aria-hidden','true');svg.innerHTML=kind==='snow'?FLAKE:DROP;return svg}
 window.WeatherGlyphs={DROP,FLAKE};
 // Today's rain (rain + showers, mm) and snow (cm) from the daily sums.
 function precipToday(d,i){const n=v=>v!=null&&Number.isFinite(v)?v:0;return {rain:n(d.rain_sum?.[i])+n(d.showers_sum?.[i]),snow:n(d.snowfall_sum?.[i]),total:n(d.precipitation_sum?.[i]),chance:d.precipitation_probability_max?.[i]}}
 function aqiWord(a){return a<=50?'Good':a<=100?'Moderate':a<=150?'Sensitive groups':a<=200?'Unhealthy':a<=300?'Very unhealthy':'Hazardous'}

 function drawTiles(){
  const box=$('metrics');box.replaceChildren();if(!data)return;
  const c=data.current,d=data.daily,i=todayIndex(),num=v=>v!=null&&Number.isFinite(v);
  const out=[];
  out.push(tile('Humidity',c.relative_humidity_2m+'%','',num(c.dew_point_2m)?comfort(c.dew_point_2m):'Relative','drop',null,{'--level':c.relative_humidity_2m+'%'}));
  out.push(num(c.dew_point_2m)?tile('Dew point',F(c.dew_point_2m)+'°F','',Math.round(c.dew_point_2m)+'°C','pill'):tile('Dew point','--','','Unavailable','pill'));
  out.push(tile('Wind',String(Math.round(c.wind_speed_10m/1.609)),'mph',Math.round(c.wind_speed_10m)+' km/h'+(num(c.wind_direction_10m)?', from '+compassName(c.wind_direction_10m):''),'clover',num(c.wind_direction_10m)?arrow(c.wind_direction_10m):null,{'--spin':c.wind_speed_10m<2?'0s':Math.max(4,Math.round(160/c.wind_speed_10m))+'s'}));
  const uvMax=d.uv_index_max?.[i],uvNow=num(c.uv_index)?c.uv_index:null;
  out.push(num(uvNow)?tile('UV index',String(Math.round(uvNow)),'',uvWord(uvNow)+(num(uvMax)?', peak '+Math.round(uvMax):''),'sun',null,{'--tint':mix(uvColor(uvNow),34)}):tile('UV index',num(uvMax)?String(Math.round(uvMax)):'--','',num(uvMax)?uvWord(uvMax)+' peak today':'Unavailable','sun',null,num(uvMax)?{'--tint':mix(uvColor(uvMax),34)}:null));
  const aq=air?.current?.us_aqi;out.push(tile('Air quality',num(aq)?String(Math.round(aq)):'--','',num(aq)?aqiWord(aq):airLoaded?'Unavailable':'Checking','cookie',null,num(aq)?{'--tint':mix(aqiColor(aq),30)}:null));
  out.push(tile('Pressure',String(Math.round(c.surface_pressure)),'hPa',(c.surface_pressure*0.02953).toFixed(2)+' inHg','gem'));
  const vis=c.visibility;out.push(num(vis)?tile('Visibility',String(Math.round(vis/1609.34)),'mi',Math.round(vis/1000)+' km','arch'):tile('Visibility','--','','Unavailable','arch'));
  const pt=precipToday(d,i),pct=num(pt.chance)?pt.chance+'% chance':'';
  if(pt.snow>0&&pt.rain>0)out.push(tile('Rain and snow',(pt.total/25.4).toFixed(2),'in','Rain '+pt.rain.toFixed(1)+' mm, snow '+pt.snow.toFixed(1)+' cm','hex',mini('snow')));
  else if(pt.snow>0)out.push(tile('Snow today',(pt.snow/2.54).toFixed(1),'in',pt.snow.toFixed(1)+' cm'+(pct?', '+pct:''),'hex',mini('snow')));
  else if(pt.rain>0)out.push(tile('Rain today',(pt.rain/25.4).toFixed(2),'in',pt.rain.toFixed(1)+' mm'+(pct?', '+pct:''),'flower',mini('rain')));
  else out.push(tile('Precipitation',num(pt.chance)?pt.chance+'%':'--','',num(pt.chance)?'None expected':'Unavailable','flower'));
  box.append(...out);
 }

 function drawNextHour(){
  nextHour.replaceChildren(el('h2','Next 2 hours'));
  const m=data?.minutely_15;if(!m?.time?.length){nextHour.append(el('p',data?'Short-range precipitation is not available for this city.':'Choose a city first.','empty'));return}
  const p=m.precipitation.map(v=>v??0),wet=v=>v>=.1,snowy=(data.current.weather_code>=71&&data.current.weather_code<=77)||data.current.temperature_2m<=0,kind=snowy?'Snow':'Rain',span=p.length*15;
  let text;const first=p.findIndex(wet);
  if(first<0)text=`No ${kind.toLowerCase()} expected for the next ${span/60} hours.`;
  else if(first===0){const stop=p.findIndex(v=>!wet(v));text=stop<0?`${kind} continuing for at least ${span/60} hours.`:`${kind} easing off around ${cityTime(instant(m.time[stop]))}.`}
  else text=`${kind} starting around ${cityTime(instant(m.time[first]))}.`;
  nextHour.append(el('p',text,'lead'));
  const max=Math.max(1,...p),bars=el('div',undefined,'bars');bars.setAttribute('aria-hidden','true');for(const v of p){const b=el('i');b.style.height=Math.max(5,v/max*100)+'%';if(!wet(v))b.style.opacity='.3';bars.append(b)}const scale=el('div',undefined,'bar-scale');scale.append(el('span','Now'),el('span','1 hour'),el('span','2 hours'));nextHour.append(bars,scale);
  if(first>=0){const total=p.reduce((a,b)=>a+b,0);nextHour.append(el('p',`About ${(total/25.4).toFixed(2)} in / ${total.toFixed(1)} mm in total.`,'card-subtitle'))}
  else nextHour.append(el('p',nextLikely(),'card-subtitle'));
 }

 // Beyond the 2-hour window: the first hour in the next 2 days with a 40% chance or a measurable amount.
 function nextLikely(){const h=data.hourly,now=Date.now()+2*3600000;for(let i=0;i<h.time.length;i++){const t=instant(h.time[i]);if(t<now)continue;if(t>now+46*3600000)break;const amt=(h.rain?.[i]||0)+(h.showers?.[i]||0)+(h.snowfall?.[i]||0),p=h.precipitation_probability?.[i]??0;
  if(p>=40||amt>=.2){const snow=(h.snowfall?.[i]||0)>0||h.temperature_2m?.[i]<=0,day=cityDay(t)===cityDay(Date.now())?'':new Date(t).toLocaleDateString('en-US',{timeZone:data.timezone,weekday:'long'})+' ';return `Next likely ${snow?'snow':'rain'}: ${day}around ${cityTime(t)} (${p}%).`}}
  return 'Nothing likely in the next 2 days.'}
 function wear(feels,chance,uv){let w=feels<-10?'Heavy coat, hat and gloves':feels<0?'Winter coat and gloves':feels<8?'A warm jacket':feels<14?'A light jacket or sweater':feels<20?'Long sleeves':feels<27?'T-shirt weather':'Light, breathable clothes';const add=[];if(chance>=50)add.push('an umbrella');if(uv>=6)add.push('sunscreen');return w+(add.length?', plus '+add.join(' and '):'')+'.'}
 function bestHour(){
  const h=data.hourly,now=Date.now();let best=null;
  for(let i=0;i<h.time.length;i++){const t=instant(h.time[i]);if(t<now-30*60000)continue;if(t>now+24*3600000)break;if(h.is_day&&h.is_day[i]===0)continue;
   const temp=h.temperature_2m[i],chance=h.precipitation_probability[i]??0,gust=h.wind_gusts_10m?.[i]??0;if(temp==null)continue;
   const score=chance*1.5+Math.abs((h.apparent_temperature?.[i]??temp)-21)*2+Math.max(0,gust-25)*.6+(h.precipitation?.[i]>0?40:0);
   if(!best||score<best.score)best={i,score,t,temp,chance}}
  if(!best)return 'No daylight hours left in the next day.';
  const sameDay=cityDay(best.t)===cityDay(now);
  return `${sameDay?'':'Tomorrow, '}${cityTime(best.t)}: ${deg(best.temp)}, ${best.chance}% chance of rain.`;
 }
 function goldenHour(){
  const now=Date.now(),day=cityDay(now);
  for(const offset of [0,1]){const noon=Date.parse(day+'T12:00:00Z')-(data.utc_offset_seconds||0)*1000+offset*864e5,t=SunCalc.getTimes(new Date(noon),place.lat,place.lon);
   const windows=[[t.sunrise,t.goldenHourEnd],[t.goldenHour,t.sunset]];
   for(const [a,b] of windows){if(!a||!b||isNaN(a)||isNaN(b))continue;if(b.getTime()>now)return `${offset?'Tomorrow, ':''}${cityTime(a.getTime())} to ${cityTime(b.getTime())}.`}}
  return 'Not available at this latitude today.';
 }
 function drawToday(){
  today.replaceChildren(el('h2','Today'));if(!data||!place){today.append(el('p','Choose a city first.','empty'));return}
  const d=data.daily,i=todayIndex(),list=el('dl',undefined,'facts');
  const row=(k,v)=>{const g=el('div');g.append(el('dt',k),el('dd',v));list.append(g)};
  row('What to wear',wear(data.current.apparent_temperature,d.precipitation_probability_max?.[i]??0,d.uv_index_max?.[i]??0));
  row('Best time to be outside',bestHour());
  row('Golden hour',goldenHour());
  today.append(list);
 }

 function moonPath(fraction,waxing){const k=fraction,rx=Math.abs(1-2*k)*40,sweep=k<.5?0:1;const d=`M50 10A40 40 0 0 1 50 90A${rx.toFixed(2)} 40 0 0 ${sweep} 50 10Z`;return `<circle cx="50" cy="50" r="40" fill="currentColor" fill-opacity=".12"/><path d="${d}" fill="currentColor" ${waxing?'':'transform="matrix(-1 0 0 1 100 0)"'}/>`}
 function phaseName(p){return p<.03||p>.97?'New moon':p<.22?'Waxing crescent':p<.28?'First quarter':p<.47?'Waxing gibbous':p<.53?'Full moon':p<.72?'Waning gibbous':p<.78?'Last quarter':'Waning crescent'}
 function nextMoonEvents(){
  const h0=0.133*Math.PI/180,start=Date.now(),step=10*60000;let prev=SunCalc.getMoonPosition(new Date(start),place.lat,place.lon).altitude-h0,rise=null,set=null;
  for(let t=start+step;t<=start+30*3600000&&(!rise||!set);t+=step){const alt=SunCalc.getMoonPosition(new Date(t),place.lat,place.lon).altitude-h0;if(prev<0&&alt>=0&&!rise)rise=t-step*alt/(alt-prev);if(prev>=0&&alt<0&&!set)set=t-step*alt/(alt-prev);prev=alt}
  const label=t=>t?(cityDay(t)===cityDay(start)?'':'Tomorrow, ')+cityTime(t):'Not in the next day';return {rise:label(rise),set:label(set),riseAt:rise,setAt:set};
 }
 function drawMoon(){
  moon.replaceChildren(el('h2','Moon'));if(!data||!place){moon.append(el('p','Choose a city first.','empty'));return}
  const m=SunCalc.getMoonIllumination(new Date()),waxing=m.phase<.5,box=el('div',undefined,'moon'),ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');
  svg.setAttribute('viewBox','0 0 100 100');svg.setAttribute('role','img');svg.setAttribute('aria-label',phaseName(m.phase));svg.innerHTML=moonPath(m.fraction,waxing);
  const list=el('dl',undefined,'facts'),ev=nextMoonEvents(),row=(k,v)=>{const g=el('div');g.append(el('dt',k),el('dd',v));list.append(g)};
  row(phaseName(m.phase),Math.round(m.fraction*100)+'% illuminated');const events=[['Moonrise',ev.rise,ev.riseAt],['Moonset',ev.set,ev.setAt]].sort((x,y)=>(x[2]||Infinity)-(y[2]||Infinity));for(const [k,v] of events)row(k,v);
  box.append(svg,list);moon.append(box);
 }

 // Next 24 hours from the hourly forecast: bar height = amount, faint column = chance. No estimates added.
 function drawPrecip(){
  precip.replaceChildren(el('h2','Precipitation, next 24 hours'));if(!data){precip.append(el('p','Choose a city first.','empty'));return}
  const h=data.hourly,num=v=>Number.isFinite(+v)&&v!=null?+v:0,rows=[];for(let i=0;i<h.time.length&&rows.length<24;i++){const t=instant(h.time[i]);if(t<Date.now()-3600000)continue;rows.push({t,label:h.time[i],rain:num(h.rain?.[i])+num(h.showers?.[i]),snow:num(h.snowfall?.[i]),chance:num(h.precipitation_probability?.[i])})}
  if(!rows.length){precip.append(el('p','Hourly precipitation is not available.','empty'));return}
  // Snow (cm) is compared to rain (mm) as water: 1 cm of snow is about 0.7 mm of water in Open-Meteo's model.
  const water=r=>r.rain+r.snow*0.7,max=Math.max(1,...rows.map(water)),rain=rows.reduce((a,r)=>a+r.rain,0),snow=rows.reduce((a,r)=>a+r.snow,0),top=rows.reduce((a,r)=>r.chance>a.chance?r:a);
  const parts=[];if(rain>=.1)parts.push(`Rain ${(rain/25.4).toFixed(2)} in (${rain.toFixed(1)} mm)`);if(snow>=.1)parts.push(`snow ${(snow/2.54).toFixed(1)} in (${snow.toFixed(1)} cm)`);
  precip.append(el('p',parts.length?parts.join(', ').replace(/^./,c=>c.toUpperCase())+' expected.':'No rain or snow expected.','lead'));
  precip.append(el('p',top.chance>0?`Highest chance ${top.chance}% around ${cityTime(top.t)}.`:'Chance stays at 0% all day.','card-subtitle'));
  const bars=el('div',undefined,'p24');bars.setAttribute('role','img');bars.setAttribute('aria-label','Hourly precipitation for the next 24 hours');
  for(const r of rows){const col=el('span'),ch=el('i',undefined,'chance'),amt=el('i',undefined,'amt '+(r.snow>0&&r.snow*0.7>=r.rain?'snow':'rain'));ch.style.height=r.chance+'%';amt.style.height=(water(r)/max*100)+'%';col.title=`${cityTime(r.t)}: ${r.chance}%`;col.append(ch,amt);bars.append(col)}
  const scale=el('div',undefined,'p24-scale');for(const k of [0,6,12,18,23])if(rows[k])scale.append(el('span',k?cityTime(rows[k].t):'Now'));
  const key=el('div',undefined,'p24-key');for(const [cls,label] of [['rain','Rain'],['snow','Snow'],['chance','Chance']]){const s=el('span'),i=el('i');i.className=cls;if(cls==='chance')i.style.cssText='background:currentColor;opacity:.25';else i.className='amt '+cls;s.append(i,document.createTextNode(label));key.append(s)}
  // A flat, empty chart says nothing: show it only when something is forecast or the chance reaches 10%.
  if(rain+snow>=.1||top.chance>=10)precip.append(bars,scale,key);
 }
 // Today's hourly UV for the selected city: current, peak, the window at or above 3, and a curve.
 function uvToday(){const h=data.hourly,day=cityDay(Date.now()),pts=[];for(let i=0;i<h.time.length;i++)if(h.time[i].startsWith(day)&&h.uv_index?.[i]!=null)pts.push({t:instant(h.time[i]),hour:+h.time[i].slice(11,13),uv:h.uv_index[i]});return pts}
 function drawUv(){
  uvCard.replaceChildren(el('h2','UV index'));if(!data){uvCard.append(el('p','Choose a city first.','empty'));return}
  const pts=uvToday();if(!pts.length){uvCard.append(el('p','Hourly UV is not available for this city.','empty'));return}
  const now=data.current.uv_index??pts.find(p=>p.t>=Date.now()-1800000)?.uv??0,peak=pts.reduce((a,b)=>b.uv>a.uv?b:a),high=pts.filter(p=>p.uv>=3);
  const head=el('div',undefined,'uv-head'),big=el('strong',String(Math.round(now)));big.style.color=uvColor(now);head.append(big,el('span',uvWord(now)+' now'));uvCard.append(head);
  // Curve over 5 AM to 9 PM city time; colour bands follow the WHO scale.
  const x=hr=>((hr-5)/16)*300,top=Math.max(8,peak.uv+1),y=u=>90-(u/top)*80,ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');
  svg.setAttribute('viewBox','0 0 300 104');svg.setAttribute('class','uv-curve');svg.setAttribute('role','img');svg.setAttribute('aria-label',`Hourly UV today, peak ${Math.round(peak.uv)} at ${cityTime(peak.t)}`);
  const line=pts.filter(p=>p.hour>=5&&p.hour<=21).map((p,k)=>(k?'L':'M')+x(p.hour).toFixed(1)+','+y(p.uv).toFixed(1)).join('');
  const stops=UV_BAND.map(([u,c])=>`<stop offset="${Math.min(1,Math.max(0,(90-y(u))/80))}" stop-color="${c}"/>`).join('');
  const nowHour=(Date.now()+(data.utc_offset_seconds||0)*1000)/3600000%24,nx=x(nowHour);
  svg.innerHTML=`<defs><linearGradient id="uvg" x1="0" y1="90" x2="0" y2="10" gradientUnits="userSpaceOnUse">${stops}</linearGradient></defs>`+
   (line?`<path d="${line}L${x(21)},90L${x(5)},90Z" fill="url(#uvg)" fill-opacity=".28"/><path d="${line}" fill="none" stroke="url(#uvg)" stroke-width="2.5" stroke-linejoin="round"/>`:'')+
   `<path d="M0 90H300" stroke="currentColor" stroke-opacity=".2"/>`+(nowHour>=5&&nowHour<=21?`<path d="M${nx} 6V90" stroke="currentColor" stroke-opacity=".45" stroke-dasharray="2 3"/>`:'')+
   `<text x="${x(6)}" y="103" text-anchor="middle">6 AM</text><text x="${x(12)}" y="103" text-anchor="middle">Noon</text><text x="${x(18)}" y="103" text-anchor="middle">6 PM</text>`;
  uvCard.append(svg);
  const list=el('dl',undefined,'facts'),row=(k,v)=>{const g=el('div');g.append(el('dt',k),el('dd',v));list.append(g)};
  row('Peak',`${Math.round(peak.uv)} (${uvWord(peak.uv).toLowerCase()}) at ${cityTime(peak.t)}`);
  row('Sunscreen',high.length?`${cityTime(high[0].t)} to ${cityTime(high[high.length-1].t+3600000)}`:'Not needed today; UV stays low.');
  uvCard.append(list);
 }
 function drawAll(){drawTiles();drawUv();drawNextHour();drawPrecip();drawToday();drawMoon()}
 const render=renderWeather;renderWeather=function(d){render(d);if(data===d)drawAll()};
 const baseRefresh=refresh;refresh=function(){if(!data){air=null;airLoaded=false;drawAll()}baseRefresh()};
 window.WeatherDetails={air(d){air=d;airLoaded=true;drawTiles()},redraw:drawAll,placeTime,phoneTime,
  /* Plain facts for the personality line and Gemini Nano; numbers here are the only ones it may use. */
  facts(){if(!data||!place)return null;const c=data.current,d=data.daily,i=todayIndex();const pt=placeTime(),ph=phoneTime();return {city:place.name,placeLocalTime:pt.localTimeText,partOfDay:pt.partOfDay,phonePartOfDay:ph.far?ph.partOfDay:'',condition:describe(c.weather_code),code:c.weather_code,isDay:c.is_day===1,tempF:F(c.temperature_2m),tempC:Math.round(c.temperature_2m),feelsF:F(c.apparent_temperature),feelsC:Math.round(c.apparent_temperature),highF:F(d.temperature_2m_max[i]),highC:Math.round(d.temperature_2m_max[i]),lowF:F(d.temperature_2m_min[i]),lowC:Math.round(d.temperature_2m_min[i]),rainChance:d.precipitation_probability_max?.[i]??0,windMph:Math.round(c.wind_speed_10m/1.609),uv:Math.round(d.uv_index_max?.[i]??0),humidity:c.relative_humidity_2m,versusYesterday:versusYesterday(data)}}};
 drawAll();setInterval(()=>{if(!document.hidden&&data){drawUv();drawNextHour();drawPrecip();drawToday();drawMoon()}},5*60000);
})();
