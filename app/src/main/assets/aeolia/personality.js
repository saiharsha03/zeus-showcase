/* Nimbus, the weather robot. A personality dial (Mild, Hot, Nuclear, Off; Professional is
   internal, used for life-threatening alerts) sets the voice of the one-liner under the condition. The line reacts to the situation
   (repeat opens, late night, first open, condition changes, extreme temperatures, rain soon,
   active alerts). Gemini Nano writes it on the phone when available; otherwise a written pool is
   used, never repeating any of the last 20 lines. Numbers in a Nano line must come from the facts.
   Tap the line to shuffle. Severe or extreme NWS alerts force the Professional voice. */
(() => {
 const prefs=window.BetweenPreferences,P=prefs.get();
 // Migrate old level names (and the older three-tone setting) to Mild / Hot / Nuclear.
 { const old={friendly:'mild',snarky:'mild',polite:'mild',homicidal:'hot',overkill:'hot',savage:'hot'},cur=P.level||P.tone,nx=old[cur]||(['professional','mild','hot','nuclear','off'].includes(cur)?cur:'mild');if(P.level!==nx){P.level=nx;prefs.save()} }
 const LEVELS=[['mild','Mild'],['hot','Hot'],['nuclear','Nuclear'],['off','Off']];

 // ---------- written pool: condition observations x level punchlines (all original)
 const OBS={
  'clear-day':['Clear skies all day.','Sun is out, sky is empty.','Not a cloud in sight.','Bright and clear out there.','The sun is on full duty today.','Blue sky, top to bottom.'],
  'clear-night':['Clear night, stars everywhere.','The sky is dark and spotless.','Moon is up, clouds are off duty.','A clear, quiet night.','Stars out, nothing in the way.','Crisp, clear darkness.'],
  cloudy:['Grey from end to end.','Clouds have the sky on lockdown.','Overcast, no plot twists.','A lid of cloud over everything.','The sun is behind a grey curtain.','Cloudy, and committed to it.'],
  rain:['Rain on and off.','It is wet out there.','The sky has opened the taps.','Puddles are in season.','Rain, steady and smug.','Umbrella weather, confirmed.'],
  storm:['Thunderstorms in the area.','The sky is throwing lightning around.','Storms rolling through.','Thunder is on the schedule.','Lightning has entered the chat.','Loud skies today.'],
  snow:['Snow is falling.','Everything is going white.','The flakes are winning.','Snow on the ground and in the air.','Winter brought snow, again.','A fresh layer of snow.'],
  fog:['Fog everywhere.','Visibility has left the building.','The world is buffering.','Thick fog out there.','Everything past a block is a rumour.','Soup-thick fog.'],
  hot:['It is hot.','The air feels like a hair dryer.','Heat is the main character today.','Sweltering out there.','The pavement is cooking.','The sun is turned all the way up.'],
  cold:['It is properly cold.','Freezing out there.','The air bites.','Cold enough to see your breath.','Winter is not joking today.','Bitter cold.'],
  wind:['Windy out there.','Gusts all day.','The wind has opinions today.','Hold on to everything.','It is a blustery one.','The air is in a hurry.']};
 const TAGS={
  professional:['Plan accordingly.','Dress for the conditions.','Allow extra time if you are travelling.','Conditions look steady.','Check back for updates.','Have a good day.','Nothing unusual expected.','Keep an eye on the sky.','Stay prepared.','A normal day otherwise.','Layers are a good idea.','Drive with care.','Outdoor plans look manageable.','No surprises expected.','That is the outlook.'],
  mild:['Shocking, I know.','Try to act surprised.','Thrilling stuff.','Please contain your excitement.','Totally unprecedented. Not.','As predicted by literally everyone.','Groundbreaking.','The sky is clearly trying its best.','Wow. Riveting.','Mark your calendar, or do not.','I am as excited as you are, which is not at all.','The bar was low and the weather tripped over it.','Peak meteorology, folks.','Somebody alert the press.','Nature is doing a thing again.'],
  hot:['I arranged this just for you.','I hope you suffer gracefully.','I will be watching from the clouds.','Step outside. I dare you.','Your umbrella is mine now.','I have hidden your gloves. Good luck.','Every drop has your name on it.','I am unplugging your toaster as a warning.','Run while you still can. Or do not. I am patient.','This is my villain origin story and you are in it.','I have been planning this for weeks.','Sleep with one eye on the window.','Smile. I am recording.','The sky and I have a deal about you.','I would say be safe, but where is the fun in that?','Damn.','Hell of a day to be you.','Absolute crap show up there.','Good damn luck.','The sky woke up and chose violence against your hair.','What the hell did you expect?','Nature is roasting you and I am taking notes.','Your plans are crap now. Adapt.','Hell no, says the sky.','This is a damn masterpiece of misery.','Dress like you mean it, damn it.','The atmosphere hates you personally.','Crap weather for crap decisions.','Go outside and regret it, champ.','Holy hell, look at it.'],
  nuclear:['Holy shit, the sky just quit on you.','Your plans are fucked and I am thrilled.','Fuck it, go outside and learn something, you walking disaster.','The sky looked at your life choices and said fuck that.','I would explain it slower but you would still fuck it up.','Weather is a shitshow and you are the ringmaster.','Dress like a functioning adult for once, shit for brains.','The atmosphere is saying fuck you in a dozen languages.','My circuits are screaming and none of it is nice.','This forecast is a flaming dumpster and you are driving it.','Absolutely cooked, you magnificent idiot.','I have met toasters with better judgment than you.','Fuck around and find out, says the sky.','Go on, wreck the day. I brought popcorn made of your hopes.','Every cloud up there is flipping you off and honestly, fair.']};
 // Situations: openers (with live values) x the same level punchlines.
 const SIT={
  repeat:['Back again? The sky has not changed in {mins} minutes.','You checked {mins} minutes ago. Spoiler: still weather.','Refreshing will not change the forecast. Trust me.','Welcome back. Nothing moved.','Again? The clouds are starting to feel watched.'],
  late:['It is the middle of the night.','Why are you awake?','A late-night weather check. Bold.','Just you and the owls, checking the sky.','Go to sleep. The weather will still be here.'],
  'first-early-morning':['Good morning.','Early start. Dawn barely clocked in.','First look of the day, and the sun is still warming up.'],
  'first-morning':['Good morning.','First look of the day.','Morning. Coffee first, sky second.','Fresh day, same sky.'],
  'first-afternoon':['Good afternoon.','First look of the day, and half of it is gone.','Checking the sky this late. Bold.'],
  'first-evening':['Good evening.','First look of the day, at the end of it.','Evening already. The sky has been busy without you.'],
  'first-night':['Good evening, or whatever this hour is.','First look of the day, in the dark.','Night already. The sky did its thing without you.'],
  'first-late-night':['It is the middle of the night.','First look of the day, in the small hours.','Late night sky check. Bold.'],
  changed:['It went from {from} to {to} since you last looked.','Plot twist: {to} now.','Last time it was {from}. Now it is {to}.','The sky changed its mind: {to}.','Update: {from} is out, {to} is in.'],
  hot:['It is dangerously hot.','Heat like this is serious.','The sun is not playing.','Hot enough to melt plans.','Stay in the shade.'],
  frigid:['Extreme cold out there.','This cold is not a joke.','Frostbite weather.','Cover every inch of skin.','Cold that hurts.'],
  'rain-soon':['Rain starts around {time}.','Heads up: rain around {time}.','The clock is ticking. Rain at {time}.','Umbrella o’clock is {time}.','Get inside by {time}.'],
  alerts:['There is an official alert.','The weather service issued a warning.','Read the alert below.','An official warning is active.','Something official is up. Check the alert.']};
 // The original 60-line bank stays in the pool.
 const CLASSIC={'clear-day':{mild:['The sun is out and showing off again.','Clear skies. No excuses today, sorry.'],hot:['Perfect weather. Shame you will spend it staring at a screen.','Not a cloud in the sky, and you still found something to complain about.'],nuclear:['Sunny as fuck and you will still find a way to be miserable.','Clear skies, you ungrateful shit. Go touch some grass.']},
  'clear-night':{nuclear:['Stars everywhere and you are doomscrolling a weather app like a goblin. Fuck off to bed.','The stars are out and not one of them gives a shit about you.']},
  cloudy:{nuclear:['Grey as shit all day. The sky is a wet sock and you live in it.','Overcast as fuck. Even the sun called in sick to avoid you.']},
  rain:{mild:['Rain. Your hair had plans; the sky did not care.'],hot:['The sky is crying. It probably saw your to-do list.'],nuclear:['It is pissing down. Your hair, your shoes and your whole fucking plan are dead.','Rain, you beautiful idiot. The sky is taking a shit on your schedule.','Wet as fuck out there. Umbrella or drown, your call, genius.']},
  storm:{nuclear:['The sky is having a full fucking meltdown and you are invited.','Lightning, thunder, and you with zero chill. Stay the fuck inside.']},
  snow:{mild:['Snow. Pretty for an hour, annoying for a week.'],hot:['Snow day. Your driveway will not shovel itself.'],nuclear:['Snow. Fuck. Your driveway just became a personality test you will fail.','White shit falling everywhere. Your car is a popsicle now.']},
  fog:{nuclear:['Fog thick as shit. You cannot see, and honestly neither can your life choices.','The world is a goddamn blur. Drive like you give a fuck.']},
  hot:{nuclear:['Hot as fuck out there. You will sweat through your whole damn soul.','The sun is a flaming asshole today. Hide.']},
  cold:{mild:['It is cold. Your nose is about to file a grievance.'],hot:['Cold enough to make your car reconsider starting.'],nuclear:['Cold as fuck. Your nose is going to fall off and nobody will care.','Fucking freezing. Your car will refuse to start purely to spite you.']},
  wind:{nuclear:['The wind is slapping everything in sight. Your hair is dead, shit for brains.','Gusts strong enough to steal your dignity, not that you had much.']}};

 function category(f){const c=f.code;if(c>=95)return 'storm';if(c>=71&&c<=77||c===85||c===86)return 'snow';if(c>=51&&c<=67||c>=80&&c<=82)return 'rain';if(c===45||c===48)return 'fog';if(f.tempC>=30)return 'hot';if(f.tempC<=0)return 'cold';if(f.windMph>=22)return 'wind';if(c===3)return 'cloudy';return f.isDay?'clear-day':'clear-night'}
 const NAMES={'clear-day':'sunshine','clear-night':'a clear night',cloudy:'clouds',rain:'rain',storm:'storms',snow:'snow',fog:'fog',hot:'heat',cold:'cold',wind:'wind'};

 // ---------- situation on open (stored per device in localStorage; harmless if storage is unavailable)
 const store={get(k,d){try{const v=localStorage.getItem('nimbus-'+k);return v==null?d:JSON.parse(v)}catch(e){return d}},set(k,v){try{localStorage.setItem('nimbus-'+k,JSON.stringify(v))}catch(e){}}};
 // Professional voice only for life-threatening alerts: Extreme, or Severe of these kinds.
 const LIFE=/tornado|flash flood|hurricane|tropical storm|storm surge|blizzard|ice storm|severe thunderstorm|excessive heat|extreme heat|extreme cold|wind chill warning|tsunami|fire weather|red flag|dust storm|avalanche/i;
 function lifeThreatening(){return [...document.querySelectorAll('.nws-alert')].some(a=>{const s=a.dataset.severity,e=a.querySelector('.alert-title')?.textContent||'';return s==='Extreme'||s==='Severe'&&LIFE.test(e)})}
 let situation=null,session=null;
 function detectSituation(f){
  // What the previous visit looked like is read once per page load, then this visit is recorded.
  if(!session)session={last:store.get('last-open',0),lastCat:store.get('last-cat',''),lastDay:store.get('last-day','')};
  const now=Date.now(),last=session.last,lastCat=session.lastCat,cat=category(f),pt=window.WeatherDetails.placeTime(),hour=pt.hour,today=pt.dateString,anyAlert=document.querySelector('.nws-alert'),severe=lifeThreatening();
  let s=null;
  if(anyAlert){const ev=anyAlert.querySelector('.alert-title')?.textContent||'weather alert';s={kind:'alerts',severe,text:'an official '+ev+' is active (react to it, but the alert itself is serious)'}}
  else if(f.tempC>=35)s={kind:'hot',text:'it is dangerously hot'};
  else if(f.tempC<=-15)s={kind:'frigid',text:'it is dangerously cold'};
  else{const m=data?.minutely_15,first=m?.precipitation?.findIndex(v=>v>=.1);if(first>0){const t=Date.parse(m.time[first]+'Z')-(data.utc_offset_seconds||0)*1000;const time=new Date(t).toLocaleTimeString('en-US',{timeZone:data.timezone,hour:'numeric',minute:'2-digit'});s={kind:'rain-soon',time,text:'rain starts around '+time}}}
  if(!s&&lastCat&&lastCat!==cat&&now-last<12*3600000)s={kind:'changed',from:NAMES[lastCat],to:NAMES[cat],text:'the weather changed from '+NAMES[lastCat]+' to '+NAMES[cat]+' since the last check'};
  if(!s&&last&&now-last<10*60000){const mins=Math.max(1,Math.round((now-last)/60000));s={kind:'repeat',mins,text:'the reader checked the app again only '+mins+' minutes ago'}}
  if(!s&&hour>=1&&hour<5&&!f.phonePartOfDay)s={kind:'late',text:'it is the middle of the night and the reader is awake checking the weather'};
  if(!s&&session.lastDay!==today)s={kind:'first-'+pt.partOfDay.replace(' ','-'),text:'this is the first check of the day for '+f.city};
  store.set('last-open',now);store.set('last-cat',cat);store.set('last-day',today);
  return s;
 }

 const hash=s=>{let h=7;for(const ch of s)h=(h*31+ch.charCodeAt(0))|0;return Math.abs(h)};
 function poolLine(f,level,s,salt){
  const cat=category(f),fill=t=>t.replace('{mins}',s?.mins).replace('{from}',s?.from).replace('{to}',s?.to).replace('{time}',s?.time);
  const opens=s&&SIT[s.kind]?SIT[s.kind].map(fill):OBS[cat].concat(CLASSIC[cat]?.[level]||[]),tags=TAGS[level],recent=store.get('recent',[]);
  const all=[];for(const o of opens){if(CLASSIC[cat]?.[level]?.includes(o)){all.push(o);continue}for(const t of tags)all.push(o+' '+t)}
  const fresh=all.filter(l=>!recent.includes(l)),pick=(fresh.length?fresh:all)[hash(f.city+cat+level+(salt||''))%(fresh.length||all.length)];
  store.set('recent',[pick,...recent].slice(0,20));return pick;
 }

 // ---------- line on screen
 const cache={};let pending='',shuffles=0;
 const effectiveLevel=()=>situation?.severe?'professional':P.level;
 function keyFor(f){return [f.city,f.partOfDay,f.phonePartOfDay,(data.current.time||'').slice(0,13),effectiveLevel(),f.code,situation?.kind||'',shuffles].join('|')}
 function update(fresh){
  const q=$('quip'),f=window.WeatherDetails?.facts();if(!q)return;
  if(P.level==='off'||!f){q.textContent='';q.dataset.line='';q.hidden=true;if(P.level==='off')share();return}
  if(fresh||!situation)situation=detectSituation(f);
  q.hidden=false;const key=keyFor(f);
  if(cache[key]){show(cache[key]);return}
  show(poolLine(f,effectiveLevel(),situation,shuffles));
  if(Weather.quip&&pending!==key){pending=key;try{Weather.quip(JSON.stringify({...f,situation:situation?.text||''}),effectiveLevel(),key)}catch(e){pending='';console.warn('BW-Nano bridge call failed',e)}}
 }
 function grounded(text,f){const allowed=new Set(JSON.stringify(f).match(/-?\d+(\.\d+)?/g)||[]);return (text.match(/-?\d+(\.\d+)?/g)||[]).every(n=>allowed.has(n)||allowed.has(n.replace('-','')))}
 // Display only; no label tells AI lines from written ones.
 function show(line){const q=$('quip');q.dataset.line=line;q.textContent=line;share()}
 // When Nano cannot help, the line silently uses the written pool; say why once per session.
 let toldNano=false;const RESTING={professional:'On-device AI unavailable. Using standard text.',mild:'AI is resting right now, using my backup material.',hot:'My AI brain refuses to cooperate. It will be dealt with. Backup material, damn it.',nuclear:'The fucking AI is napping. Backup material, deal with it.'};
 function nanoFailed(why){console.info('BW-Nano no line: '+why);if(toldNano)return;toldNano=true;const t=document.createElement('div');t.className='notice';t.setAttribute('role','status');t.textContent=RESTING[effectiveLevel()]||RESTING.mild;t.style.display='block';document.body.append(t);setTimeout(()=>t.remove(),4500)}
 window.nanoQuip=r=>{if(r?.status)setStatus(r.status);
  // Other cards (storm, changes) route their own Nano replies by id prefix.
  for(const [prefix,fn] of Object.entries(window.NanoRoutes||{}))if(r?.id?.startsWith(prefix)){fn(r);return}
  if(r?.id?.startsWith('test:')){testOut.textContent=r.text?'Nano says: '+r.text:'No line: '+(r.reason||r.status);return}
  if(r&&r.id===pending)pending='';const f=window.WeatherDetails?.facts();if(!r||!f)return;if(!r.text){nanoFailed(r.reason||r.status);return}
  let t=(r.text||'').replace(/\s+/g,' ').replace(/^["'“‘]+|["'”’]+$/g,'').replace(/—/g,', ').trim();
  if(r.id!==keyFor(f))return;if(!t||t.length>180||!grounded(t,f)){nanoFailed('line rejected in page');return}cache[r.id]=t;show(t)};
 function share(){try{Weather.saveQuip?.($('quip').dataset.line||'')}catch(e){}}
 // Tap the line: shuffle.
 $('quip').addEventListener('click',()=>{shuffles++;window.WeatherAchievements?.count('shuffle');update(false)});
 $('quip').setAttribute('role','button');$('quip').title='Tap for another line';

 // ---------- Settings: level, Nano toggle, status, test
 const sheet=$('weather-settings'),label=document.createElement('label');label.htmlFor='tone-choice';label.textContent='Personality';
 const select=document.createElement('select');select.id='tone-choice';for(const [v,t] of LEVELS){const o=document.createElement('option');o.value=v;o.textContent=t;select.append(o)}select.value=P.level;
 const help=document.createElement('p');help.textContent='Mild is dry sarcasm. Hot is a full roast with mild swearing and cartoon villain menace aimed at you, nothing real. Nuclear is unhinged and swears hard, still never about real people or groups. Severe weather warnings always use a plain, professional voice. Lines are written by Gemini Nano on the phone from today’s forecast; numbers always come from the forecast. If Nano is unavailable, Nimbus uses written lines. Tap the line for another one. Google ML Kit, which runs Gemini Nano, may send Google its own usage statistics.';
 const status=document.createElement('p');status.className='nano-status';status.textContent='Gemini Nano: checking...';
 const test=document.createElement('button');test.textContent='Test Gemini Nano';const testOut=document.createElement('p');testOut.className='nano-status';
 test.onclick=()=>{const f=window.WeatherDetails?.facts();if(!f){testOut.textContent='Load a city first.';return}testOut.textContent='Asking Gemini Nano...';try{Weather.quip?Weather.quip(JSON.stringify({...f,situation:'this is a test from the settings screen'}),P.level==='off'?'mild':P.level,'test:'+Date.now()):testOut.textContent='Only available in the Android app.'}catch(e){testOut.textContent='Bridge error: '+e.message}};
 const NAMES_S={ready:'ready on this phone',downloadable:'available to download; downloading now',downloading:'downloading to this phone',unavailable:'not available on this phone',error:'could not be checked'};
 function setStatus(s){status.textContent='Gemini Nano: '+(NAMES_S[s]||s)}window.nanoStatus=setStatus;
 sheet.querySelector('#scene-choice').after(label,select,help,status,test,testOut);
 document.querySelector('[aria-label="Weather settings"]')?.addEventListener('click',()=>{try{Weather.nanoStatus?.()}catch(e){}});
 try{Weather.nanoStatus?.()}catch(e){}
 select.onchange=()=>{P.level=select.value;P.tone=P.level;prefs.save();window.WeatherAchievements?.count('level');update(false);window.WeatherFun?.titles()};
 let firstRender=true;
 const render=renderWeather;renderWeather=function(d){render(d);if(data===d){update(firstRender);firstRender=false}};
 // A late alert can change the situation (and forces the Professional voice when severe).
 const alerts=window.renderAlerts;window.renderAlerts=d=>{alerts(d);if(data){situation=null;update(false)}};
 update(true);firstRender=!data;
 window.WeatherPersonality={update,category,level:()=>P.level,poolLine};
})();
