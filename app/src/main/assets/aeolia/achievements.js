/* Silly achievements with a sassy toast, an Achievements list in Settings, and a few fictional
   secret places you can search for. All offline; nothing here touches weather data. */
(() => {
 const prefs=window.BetweenPreferences,P=prefs.get(),el=(t,x,c)=>{const e=document.createElement(t);if(x!==undefined)e.textContent=x;if(c)e.className=c;return e};
 if(!P.achievements)P.achievements={};
 const store={get(k,d){try{const v=localStorage.getItem('ach-'+k);return v==null?d:JSON.parse(v)}catch(e){return d}},set(k,v){try{localStorage.setItem('ach-'+k,JSON.stringify(v))}catch(e){}}};
 const LIST=[
  ['storm-chaser','Storm chaser','You opened a weather app during a thunderstorm. Brave. Or bored.'],
  ['obsessed','Weather obsessed','Ten checks in one day. The sky has filed a restraining order.'],
  ['sub-zero','Below zero club','Below 0°F. Your membership card is frozen to your hand.'],
  ['triple-digits','Hundred club','A hundred degrees Fahrenheit. You are basically a baked potato.'],
  ['night-owl','3 AM forecast','Checking the weather at 3 AM. Normal. Totally normal.'],
  ['early-bird','Early bird','Up before six. The worms are worried.'],
  ['globetrotter','Globetrotter','Buffalo and Mumbai in under a minute. Jet lag speedrun.'],
  ['decorator','Interior decorator','You rearranged the cards. Feng shui for forecasts.'],
  ['explorer','Off the map','You found a place that does not exist. Neither does its weather.'],
  ['snow-day','Snow day','It is snowing and you are on your phone. Classic.'],
  ['drenched','Drenched','Heavy rain outside, dry phone inside. Smart.'],
  ['foggy','Lost in the fog','Fog check complete. You still cannot see anything.'],
  ['sunburn','Sun worshipper','Very high UV. Your skin has sent a strongly worded letter.'],
  ['stargazer','Stargazer','A clear night. Look up from your phone, for once.'],
  ['frostbite','Frostbite','Feels like twenty below Celsius or worse. Go back inside.'],
  ['windbag','Windbag','Gale-force gusts. Hold on to the phone.'],
  ['on-alert','On alert','An official alert is active. This one is not a joke. Read it.'],
  ['radar-nerd','Radar nerd','Five radar loops. You are basically a meteorologist now. Not legally.'],
  ['identity-crisis','Identity crisis','Three personality changes. Nimbus is concerned about you.'],
  ['shuffler','Never satisfied','Ten shuffles. No line is good enough for you, huh?'],
  ['tile-tapper','Tile tapper','Twenty tile taps. They are wobbling from exhaustion.'],
  ['perfect','Perfect day','A perfect weather day. Screenshot it, it will not last.']];
 const byId=Object.fromEntries(LIST.map(a=>[a[0],a]));
 // Toasts, one at a time.
 const toast=el('div',undefined,'toast');toast.setAttribute('role','status');document.body.append(toast);const queue=[];let busy=false;
 function next(){if(busy||!queue.length)return;busy=true;const [t,m]=queue.shift();toast.replaceChildren(el('b','Achievement: '+t),el('span',m));toast.classList.add('show');setTimeout(()=>{toast.classList.remove('show');setTimeout(()=>{busy=false;next()},400)},3800)}
 function unlock(id){if(!byId[id]||P.achievements[id])return;P.achievements[id]=Date.now();prefs.save();queue.push([byId[id][1],byId[id][2]]);next()}
 const GOALS={radar:['radar-nerd',5],shuffle:['shuffler',10],level:['identity-crisis',3],wobble:['tile-tapper',20]};
 function count(name){const n=store.get('n-'+name,0)+1;store.set('n-'+name,n);const g=GOALS[name];if(g&&n>=g[1])unlock(g[0])}

 // Checks on every render and on open.
 const today=new Date().toDateString();const opens=store.get('opens',{});opens[today]=(opens[today]||0)+1;store.set('opens',{[today]:opens[today]});
 const h=new Date().getHours();/* phone time on purpose: the user's own clock, not the selected place's */if(h===3)unlock('night-owl');if(h===5)unlock('early-bird');if(opens[today]>=10)unlock('obsessed');
 function check(){if(!data)return;const c=data.current,code=c.weather_code;
  if(code>=95)unlock('storm-chaser');if(code>=71&&code<=77||code===85||code===86)unlock('snow-day');if(code===65||code===82)unlock('drenched');if(code===45||code===48)unlock('foggy');
  if(c.temperature_2m<=-17.8)unlock('sub-zero');if(c.temperature_2m>=37.8)unlock('triple-digits');if(c.apparent_temperature<=-20)unlock('frostbite');if((c.wind_gusts_10m??0)>=50)unlock('windbag');
  if((c.uv_index??0)>=8)unlock('sunburn');if(code<=1&&c.is_day===0)unlock('stargazer');
  // Both starter cities within a minute.
  const visits=store.get('visits',{});const name=place?.name;if(name==='Buffalo'||name==='Mumbai'){visits[name]=Date.now();store.set('visits',visits);if(visits.Buffalo&&visits.Mumbai&&Math.abs(visits.Buffalo-visits.Mumbai)<60000)unlock('globetrotter')}}
 const render=renderWeather;renderWeather=function(d){render(d);if(data===d)check()};
 const alerts=window.renderAlerts;window.renderAlerts=d=>{alerts(d);if(document.querySelector('.nws-alert'))unlock('on-alert')};
 document.querySelector('main').addEventListener('click',e=>{if(e.target.closest('.metric'))count('wobble')});
 document.querySelector('.radar-bar button')?.addEventListener('click',()=>count('radar'));
 // Card order saved = rearranged.
 const save=prefs.save;let lastOrder=JSON.stringify(P.cardOrder||null);prefs.save=function(){const o=JSON.stringify(P.cardOrder||null);if(o!==lastOrder){lastOrder=o;if(P.cardOrder)unlock('decorator')}return save.apply(this,arguments)};

 // ---------- secret places (fictional; clearly labelled, no weather data)
 const SECRETS={atlantis:['Atlantis','Forecast: underwater, all day, every day. Bring goggles.'],'the moon':['The Moon','No air, no clouds, no weather. Days roast, nights freeze, and nobody can hear you complain.'],
  'el dorado':['El Dorado','Sunny with a chance of gold. Nobody has found it, including the weather.'],'cloud nine':['Cloud Nine','Permanently fluffy. Mood: excellent. Altitude: emotional.'],
  'middle of nowhere':['The middle of nowhere','Weather: also nowhere. Very peaceful.'],nimbus:['Home of Nimbus','Forecast: sarcasm, heavy at times, clearing never.']};
 const secret=el('dialog',undefined,'settings-sheet');document.body.append(secret);
 const find=window.findCity;window.findCity=function(e){const q=(document.getElementById('query').value||'').trim().toLowerCase();const s=SECRETS[q];if(!s)return find(e);e.preventDefault();
  secret.replaceChildren();const close=el('button','Close','close');close.onclick=()=>secret.close();secret.append(close,el('h2',s[0]),el('p',s[1],'secret-line'),el('p','This place is fictional. No real weather here.'));secret.showModal();
  document.querySelector('.hero-char')?.classList.add('hop');unlock('explorer')};
 findCity=window.findCity;

 // ---------- Settings > Achievements
 const list=el('dialog',undefined,'settings-sheet');document.body.append(list);
 function draw(){list.replaceChildren();const close=el('button','Done','close');close.onclick=()=>list.close();const got=LIST.filter(a=>P.achievements[a[0]]).length;list.append(close,el('h2','Achievements'),el('p',got+' of '+LIST.length+' unlocked.'));
  const ul=el('ul',undefined,'ach-list');for(const [id,t,m] of LIST){const on=!!P.achievements[id],li=el('li',undefined,on?'on':'');li.append(el('b',t),el('span',on?m:'Locked. Keep checking the weather.'));ul.append(li)}list.append(ul)}
 const btn=el('button','Achievements');btn.onclick=()=>{draw();list.showModal()};const sheet=document.getElementById('weather-settings');sheet.insertBefore(btn,sheet.querySelector('.about'));
 const css=el('style');css.textContent=`.toast{position:fixed;left:50%;bottom:calc(var(--inset-bottom) + 24px);transform:translate(-50%,40px) scale(.9);opacity:0;z-index:80;max-width:min(92vw,420px);padding:12px 16px 13px;border-radius:12px;background:var(--ink);color:var(--bg);box-shadow:0 8px 24px rgba(0,0,0,.3);transition:transform .25s ease-out,opacity .25s ease-out;pointer-events:none}
 .toast.show{opacity:1;transform:translate(-50%,0) scale(1)}.toast b{display:block;font-size:12px;letter-spacing:.4px;text-transform:uppercase}.toast span{display:block;font-size:14px;line-height:1.4;margin-top:3px}
 .ach-list{list-style:none;margin:8px 0 0;padding:0}.ach-list li{padding:10px 0;border-bottom:1px solid var(--line);opacity:.55}.ach-list li.on{opacity:1}.ach-list b{display:block;font-size:14px}.ach-list span{display:block;font-size:12px;margin-top:2px;color:var(--ink2)}
 
 @media(prefers-reduced-motion:reduce){.toast{transition:none}}`;document.head.append(css);
 check();window.WeatherAchievements={unlock,count,list:LIST};
})();
