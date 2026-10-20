/* Sun path and holiday cards. Placement and order are owned by layout.js. */
(() => {
 const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e},main=document.querySelector('main');
 const preferences=window.BetweenPreferences;
 const css=el('style');css.textContent=`
 .card-subtitle{font-size:12px;line-height:1.6;margin:12px 0 0}.sun-graphic{width:100%;height:auto;max-height:170px;display:block}.sun-times{display:flex;justify-content:space-between;font-size:14px;font-variant-numeric:tabular-nums}.sun-times small{display:block;font-size:11px;margin-bottom:4px}
 .card-tools{display:flex;gap:18px;margin-bottom:6px}.card-tools button{font:inherit;font-size:13px;min-height:36px;padding:4px 0;border-radius:0;border-bottom:2px solid transparent}.card-tools button[aria-pressed=true]{border-bottom-color:currentColor}
 .holiday-list{list-style:none;padding:0;margin:0}.holiday-list li{display:grid;grid-template-columns:52px 1fr;gap:12px;padding:12px 0;border-bottom:1px solid;font-size:14px;line-height:1.5}.holiday-list li:last-child{border-bottom:0}.holiday-list time{font-size:12px}.holiday-list small{display:block;font-size:11px;margin-top:3px}`;
 document.head.append(css);
 const sun=el('section',undefined,'card');sun.id='sun-path';sun.dataset.cardId='sun-path';main.append(sun);
 const holidays=el('section',undefined,'card');holidays.id='holidays';holidays.dataset.cardId='holidays';main.append(holidays);
 let sunShownFor='';const still=matchMedia('(prefers-reduced-motion: reduce)').matches;
 function drawSun(){
  sun.replaceChildren(el('h2','Sun path'));if(!data||!place){sun.append(el('p','Choose a city to see the sun’s path.','empty'));return}
  const d=data,offset=d.utc_offset_seconds||0,toInstant=s=>Date.parse(s+'Z')-offset*1000;
  const cityDate=new Date(Date.now()+offset*1000).toISOString().slice(0,10);const index=d.daily.time.indexOf(cityDate);
  if(index<0){sun.append(el('p','Refresh the forecast to see today’s sun path.','empty'));return}
  const rise=toInstant(d.daily.sunrise[index]),set=toInstant(d.daily.sunset[index]),now=Date.now();
  const pos=SunCalc.getPosition(new Date(now),place.lat,place.lon),altitude=pos.altitude*180/Math.PI;
  if(!Number.isFinite(rise)||!Number.isFinite(set)||set<=rise){sun.append(el('p',altitude>=0?'Sun above the horizon. Sunrise and sunset are unavailable for this date.':'Sun below the horizon. Sunrise and sunset are unavailable for this date.','empty'));return}
  const progress=Math.max(0,Math.min(1,(now-rise)/(set-rise))),x=30+260*progress,y=115-340*progress*(1-progress),daylight=now>=rise&&now<=set;
  const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');svg.setAttribute('viewBox','0 0 320 128');svg.classList.add('sun-graphic');svg.setAttribute('role','img');svg.setAttribute('aria-label',`Sun altitude ${Math.round(altitude)} degrees. ${daylight?'Daytime':'Sun below horizon'}.`);
  svg.innerHTML=`<path d="M20 115H300" stroke="currentColor" stroke-opacity=".25" fill="none"/><path d="M30 115Q160 -55 290 115" stroke="currentColor" stroke-opacity=".45" stroke-width="2" fill="none" stroke-dasharray="3 5"/><circle cx="${x}" cy="${daylight?y:115}" r="8" fill="${daylight?'#f0b44c':'#879aad'}"/>`;
  sun.append(svg);
  // First time a city is shown, the sun glides along the arc to where it is now.
  const key=place.lat.toFixed(3)+','+place.lon.toFixed(3);if(daylight&&!still&&key!==sunShownFor){const dot=svg.querySelector('circle'),t0=performance.now();const step=t=>{const k=Math.min(1,(t-t0)/1400),e=1-Math.pow(1-k,3),p=progress*e;dot.setAttribute('cx',30+260*p);dot.setAttribute('cy',115-340*p*(1-p));if(k<1)requestAnimationFrame(step)};requestAnimationFrame(step)}sunShownFor=key;
  const times=el('div','','sun-times');for(const [label,value] of [['Sunrise',clock(d.daily.sunrise[index])],['Daylight',Math.floor((set-rise)/3600000)+'h '+Math.round((set-rise)%3600000/60000)+'m'],['Sunset',clock(d.daily.sunset[index])]]){const item=el('div');item.append(el('small',label),el('span',value));times.append(item)}sun.append(times);
  sun.append(el('p',daylight?`The sun is ${Math.round(altitude)}° above the horizon.`:`The sun is below the horizon.`,'card-subtitle'));
 }
 let calendar=BundledHolidays.events,filter=preferences.get().holidayCountry||'both';
 function parseICS(text,country){const events=[];for(const block of text.replace(/\r?\n[ \t]/g,'').split('BEGIN:VEVENT').slice(1)){const body=block.split('END:VEVENT')[0],date=body.match(/^DTSTART;VALUE=DATE:(\d{4})(\d{2})(\d{2})/m),name=body.match(/^SUMMARY:(.*)$/m),description=body.match(/^DESCRIPTION:(.*)$/m);if(!date||!name||body.includes('STATUS:CANCELLED'))continue;const unescape=s=>s.replace(/\\([nN,;\\])/g,(_,c)=>c.toLowerCase()==='n'?'\n':c);events.push({date:date.slice(1).join('-'),name:unescape(name[1].trim()),country,type:description?unescape(description[1].trim()).split('\n')[0]:'Holiday or observance'})}return events.sort((a,b)=>a.date.localeCompare(b.date)||a.name.localeCompare(b.name))}
 window.parseHolidayCalendar=parseICS;
 function drawHolidays(){
  holidays.replaceChildren(el('h2','Holidays'));const tools=el('div',undefined,'card-tools');for(const [id,name] of [['both','Both'],['US','United States'],['IN','India']]){const b=el('button',name);b.setAttribute('aria-pressed',String(id===filter));b.onclick=()=>{filter=id;preferences.get().holidayCountry=id;preferences.save();drawHolidays()};tools.append(b)}holidays.append(tools);
  // Only today (in the selected city's own timezone) and the next date that has something.
  let today;try{today=new Date().toLocaleDateString('en-CA',{timeZone:(typeof data!=='undefined'&&data?.timezone)||undefined})}catch(e){today=new Date().toLocaleDateString('en-CA')}
  const all=(filter==='both'?[...calendar.US,...calendar.IN]:calendar[filter]||[]).sort((a,b)=>a.date.localeCompare(b.date)||a.country.localeCompare(b.country));
  const todays=all.filter(e=>e.date===today),nextDate=all.find(e=>e.date>today)?.date,nexts=nextDate?all.filter(e=>e.date===nextDate):[];
  const block=(label,events)=>{if(!events.length)return;holidays.append(el('div',label,'holiday-label'));const list=el('ul',undefined,'holiday-list');for(const event of events){const row=el('li'),time=el('time',new Date(event.date+'T12:00:00').toLocaleDateString([],{month:'short',day:'numeric'}));time.dateTime=event.date;const body=el('div',event.name);body.append(el('small',(filter==='both'?(event.country==='US'?'United States, ':'India, '):'')+event.type.toLowerCase()));row.append(time,body);list.append(row)}holidays.append(list)};
  block('Today',todays);
  if(nextDate){const days=Math.round((new Date(nextDate+'T12:00:00')-new Date(today+'T12:00:00'))/864e5);block(days===1?'Tomorrow':'In '+days+' days',nexts)}
  if(!todays.length&&!nextDate)holidays.append(el('p','No upcoming dates for this selection.','empty'));
 }
 window.renderHolidays=result=>{const us=parseICS(result.US||'','US'),india=parseICS(result.IN||'','IN');if(!us.length||!india.length)return;calendar={US:us,IN:india};drawHolidays()};
 window.holidaysUnavailable=()=>drawHolidays();
 const render=renderWeather;renderWeather=function(d){render(d);drawSun()};
 const baseRefresh=refresh;refresh=function(){if(!data)drawSun();baseRefresh()};
 drawSun();drawHolidays();Weather.holidays?.();setInterval(()=>{if(!document.hidden)drawSun()},60000);
 window.WeatherCards={drawSun,drawHolidays};
})();
