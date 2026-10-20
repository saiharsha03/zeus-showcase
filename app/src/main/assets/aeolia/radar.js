/* Radar card. Continental US: NEXRAD composite (past hour) and HRRR forecast reflectivity
   (next hours), both from the Iowa Environmental Mesonet tile cache. Elsewhere: RainViewer
   past radar at zoom 7 or below. Basemap: Esri dark/light gray canvas, labels drawn above the radar.
   All frames are preloaded before autoplay so playback never shows half-loaded tiles. */
(() => {
 const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e};
 const IEM='https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/',ESRI='https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_';
 // Legend colours: IEM N0Q reflectivity palette, and RainViewer's "universal blue" scheme.
 const LEGEND={iem:['#04e9e7','#019ff4','#0300f4','#02fd02','#01c501','#008e00','#fdf802','#e5bc00','#fd9500','#fd0000','#bc0000','#f800fd'],rv:['#88ddee','#0099cc','#0077aa','#005588','#ffee00','#ffaa00','#ff4400','#c10000','#ffaaff']};
 // IEM N0Q colours below 20 dBZ (from IEM's N0Q lookup table; HRRR REFD tiles use the same ramp).
 // Those pixels are clear-air returns, ground clutter and drizzle-level noise, so they are hidden.
 const WEAK=new Set('415b9e,435e9f,4361a2,4568a6,4761a0,486faa,4a76ae,4b64a1,4bd690,4d7db2,4f67a2,4f84b6,518bbb,52d6a2,5699c3,576da4,599fc7,59d6b3,5b70a6,5ba6cb,5eadcf,5f73a7,60b4d4,62bbd8,6376a8,65c2dc,6779a9,67c9e0,68d6d7,6ad0e4,6c7daa,6fd6e8,7080ab,7483ac,7886ae,7c89af,808cb0,85718f,85728f,86738d,87758b,87768b,887789,8892b2,897987,897a87,8a7b85,8b7d84,8b7e84,8c7f82,8c95b3,8d8180,8d8280,8e837e,8f847c,8f857c,90877b,9098b4,918879,918979,919ab4,928b77,938d75,949bb5,949db4,969153,97a0b4,989457,9aa2b4,9b975b,9d9a60,9da5b4,a09d64,a0a8b4,a3a068,a4abb4,a5a36d,a8a671,aaa976,aab0b4,adac7a,adb3b4,b0af7e,b0b6b4,b2b283,b3b9b4,b6bbb4,b7b88c,b9beb4,babb90,bdbe94,bdc1b4,bfc199,c0c4b4,c2c49d,c3c7b4,c4c7a2,c6c9b4,c7caa6,c9ccb4,cacdaa,ccd0af,cfd2b4,d2d4b4'.split(',').map(h=>parseInt(h,16)));
 const STRONG=new Set('11e20,11f20,12020,12f30,13030,23d40,23e40,23f40,24d50,24e50,24f50,25e60,25f60,36d70,36e70,36f70,37d80,37e80,38c90,38d90,38e90,49ca0,49da0,49ea0,4adb0,4aeb0,4bbc0,4bcc0,4bdc0,5ccd0,5cdd0,5dbe0,5dce0,5dde0,5eaf0,5ebf0,5ecf0,3a62b5,3a63b5,3a64b5,3a65b5,3a66b5,3a67b5,6900db,710000,7200dd,780000,7900e2,7f0000,8300e8,8800ea,8d0000,9300ef,940000,9b0000,9b00f4,a30000,a400f7,aa0000,ac00fc,b10000,b200ff,b80000,bf0000,c60000,cd0000,d50000,e10be3,e30000,e720e9,ea0000,ea2bec,ed36ef,f040f1,f10000,f34bf4,f656f7,f80000,f960fa,fc6bfd,ff0000,ff75ff,ff8000,ff8500,ff8a00,ff8f00,ff92ff,ff9400,ff9900,ff9dff,ffa200,ffa700,ffac00,ffb100,ffb3ff,ffb600,ffbb00,ffbeff,ffc000,ffc400,ffc900,ffc9ff,ffce00,ffd300,ffd4ff,ffd800,ffdfff,ffe200,ffeaff,fff5ff,ffffff'.split(',').map(h=>parseInt(h,16)));
 function filterTile(im,t){try{const c=document.createElement('canvas');c.width=c.height=256;const x=c.getContext('2d',{willReadFrequently:true});x.drawImage(im,0,0);const d=x.getImageData(0,0,256,256),a=d.data;
  for(let i=0;i<a.length;i+=4)if(a[i+3]&&WEAK.has((a[i]<<16)|(a[i+1]<<8)|a[i+2]))a[i+3]=0;
  // Despeckle: drop pixels with fewer than 2 visible neighbours (isolated clutter).
  const keep=new Uint8Array(65536);for(let y=0;y<256;y++)for(let x0=0;x0<256;x0++){const i=(y*256+x0)*4;if(!a[i+3])continue;let n=0;for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++){if(!dx&&!dy)continue;const yy=y+dy,xx=x0+dx;if(yy>=0&&yy<256&&xx>=0&&xx<256&&a[((yy*256+xx)*4)+3])n++}keep[y*256+x0]=n>=2?1:0}
  for(let k=0;k<65536;k++)if(!keep[k])a[k*4+3]=0;x.putImageData(d,0,0);
  // 64x64 summary for storm tracking: 0 none, 1 precipitation (20+ dBZ), 2 strong (40+ dBZ).
  const m=new Uint8Array(4096);for(let y=0;y<64;y++)for(let x1=0;x1<64;x1++){const i=((y*4+2)*256+x1*4+2)*4;if(a[i+3])m[y*64+x1]=STRONG.has((a[i]<<16)|(a[i+1]<<8)|a[i+2])?2:1}c._mask=m;c._t=t;c.style.cssText=`position:absolute;width:256px;height:256px;left:${t.left}px;top:${t.top}px`;im.replaceWith(c)}catch(e){}}
 const css=el('style');css.textContent=`
 .radar-map{position:relative;height:320px;margin:0 -12px;overflow:hidden;touch-action:none;cursor:grab}
 @media(min-width:680px){.radar-map{height:440px}}
 .radar-map .layer{position:absolute;inset:0;pointer-events:none;transform-origin:var(--ox,50%) var(--oy,50%)}.radar-map img{position:absolute;width:256px;height:256px;user-select:none;-webkit-user-drag:none}
 .radar-map .wx{opacity:.75}.radar-map .wx>div{position:absolute;inset:0;opacity:0;transition:opacity .35s ease}.radar-map .wx>div.on{opacity:1}
 .radar-pin{position:absolute;width:14px;height:14px;margin:-7px 0 0 -7px;border-radius:50%;border:3px solid #14110e;box-shadow:0 0 0 2px #f4efe6;pointer-events:none}
 .radar-stamp{position:absolute;left:12px;top:12px;padding:7px 12px 8px;border-radius:14px 18px 12px 16px;background:rgba(14,11,9,.72);color:#fff;pointer-events:none;line-height:1.2}
 .radar-stamp b{display:block;font-size:11px;letter-spacing:1.2px;text-transform:uppercase;color:var(--accent)}.radar-stamp span{font-size:16px;font-variant-numeric:tabular-nums}
 .radar-msg{position:absolute;left:12px;right:64px;bottom:12px;font-size:12px;line-height:1.45;padding:8px 10px;border-radius:12px;display:none}
 .radar-zoom{position:absolute;right:10px;bottom:10px;display:flex;flex-direction:column;gap:8px}
 .radar-zoom button{width:44px;height:44px;min-height:0;padding:0;display:grid;place-items:center}
 .radar-zoom svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round}
 .radar-bar{display:flex;align-items:center;gap:14px;margin-top:14px}
 .radar-bar button{width:56px;height:56px;flex:none;padding:0;display:grid;place-items:center}
 .radar-bar svg{width:24px;height:24px;fill:currentColor}
 .radar-track{flex:1;min-width:0}.radar-track input{width:100%;margin:0}
 .radar-scale{display:flex;font-size:11px;margin-top:4px;letter-spacing:.3px}.radar-scale span{overflow:hidden;white-space:nowrap}.radar-scale span+span{border-left:1px solid;padding-left:6px}
 .radar-legend{display:flex;align-items:center;gap:8px;font-size:11px;margin-top:12px}.radar-legend i{flex:1;height:8px;border-radius:4px}
 .radar-note{font-size:11px;line-height:1.6;margin:8px 0 0}
 .storm-eta{margin-top:12px;padding:12px 14px;border-radius:18px 22px 16px 20px;background:var(--bg3)}.storm-eta b{display:block;font-size:16px}.storm-eta span{display:block;font-size:12px;color:var(--ink2);margin-top:3px}.storm-eta i{display:block;font-size:15px;margin-top:8px}`;
 document.head.append(css);
 const card=el('section',undefined,'card radar-card');card.dataset.cardId='radar';card.id='radar';
 const map=el('div',undefined,'radar-map'),base=el('div',undefined,'layer base'),wx=el('div',undefined,'layer wx'),labels=el('div',undefined,'layer labels'),pin=el('div',undefined,'radar-pin'),msg=el('div',undefined,'radar-msg');
 const stamp=el('div',undefined,'radar-stamp'),stampKind=el('b','Radar'),stampTime=el('span','');stamp.append(stampKind,stampTime);stamp.setAttribute('aria-live','polite');
 const zoomBox=el('div',undefined,'radar-zoom');
 const btn=(label,svg,fn)=>{const b=el('button');b.setAttribute('aria-label',label);b.innerHTML=`<svg viewBox="0 0 24 24">${svg}</svg>`;b.onclick=fn;return b};
 zoomBox.append(btn('Zoom in','<path d="M12 5v14M5 12h14"/>',()=>zoomBy(1)),btn('Zoom out','<path d="M5 12h14"/>',()=>zoomBy(-1)),btn('Center on city','<circle cx="12" cy="12" r="6"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4"/>',()=>{center=null;zoom=defaultZoom();layout()}));
 const layers=[base,wx,labels,pin];
 map.append(base,wx,labels,pin,stamp,msg,zoomBox);map.setAttribute('role','img');map.setAttribute('aria-label','Precipitation map. Drag to pan, pinch to zoom.');
 const play=el('button');play.setAttribute('aria-label','Play radar animation');
 const slider=el('input');slider.type='range';slider.min=0;slider.max=0;slider.value=0;slider.setAttribute('aria-label','Radar time');
 const scale=el('div',undefined,'radar-scale'),track=el('div',undefined,'radar-track');track.append(slider,scale);
 const bar=el('div',undefined,'radar-bar');bar.append(play,track);
 const legend=el('div',undefined,'radar-legend'),swatch=el('i');legend.append(el('span','Light'),swatch,el('span','Heavy'));
 const note=el('p','','radar-note'),storm=el('div',undefined,'storm-eta');storm.hidden=true;storm.setAttribute('aria-live','polite');
 card.append(el('h2','Radar'),map,bar,storm,legend,note);document.querySelector('main').append(card);
 const PLAY='<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',PAUSE='<svg viewBox="0 0 24 24"><path d="M7 5h4v14H7zM13 5h4v14h-4z"/></svg>';play.innerHTML=PLAY;

 let playing=false,center=null,zoom=8,frames=[],index=0,timer=null,loadedFor='',loadedAt=0,us=false,geometry=null,visible=false,source='iem';
 const still=matchMedia('(prefers-reduced-motion: reduce)').matches;let autoplayed='';
 const cityKey=()=>place?place.lat.toFixed(3)+','+place.lon.toFixed(3):'';
 const isCONUS=p=>p.lat>24&&p.lat<50&&p.lon>-125.5&&p.lon<-66;
 const maxZoom=()=>us?10:7,defaultZoom=()=>place&&isCONUS(place)?8:6;
 const project=(lat,lon,n)=>{const r=Math.max(-85,Math.min(85,lat))*Math.PI/180;return [(lon+180)/360*n*256,(1-Math.log(Math.tan(r)+1/Math.cos(r))/Math.PI)/2*n*256]};
 const fmt=ms=>{try{return new Date(ms).toLocaleTimeString('en-US',{timeZone:data?.timezone,hour:'numeric',minute:'2-digit'})}catch(e){return new Date(ms).toLocaleTimeString([],{hour:'numeric',minute:'2-digit'})}};
 function say(text){msg.textContent=text||'';msg.style.display=text?'block':'none'}
 function setStamp(kind,time){stampKind.textContent=kind;stampTime.textContent=time}

 // Builds the grid of tile positions that covers the map at the current center and zoom.
 function layout(){
  if(!place)return;const r=map.getBoundingClientRect();if(!r.width)return;
  const c=center||{lat:place.lat,lon:place.lon},n=2**zoom,[cx,cy]=project(c.lat,c.lon,n),left=cx-r.width/2,top=cy-r.height/2;
  const cells=[];for(let ty=Math.floor(top/256);ty<=Math.floor((top+r.height)/256);ty++){if(ty<0||ty>=n)continue;for(let tx=Math.floor(left/256);tx<=Math.floor((left+r.width)/256);tx++)cells.push({x:((tx%n)+n)%n,y:ty,left:tx*256-left,top:ty*256-top})}
  geometry={cells,zoom};const [px,py]=project(place.lat,place.lon,n);pin.style.left=(px-left)+'px';pin.style.top=(py-top)+'px';
  const style=document.documentElement.dataset.theme==='light'?'Light':'Dark';
  base.replaceChildren(...cells.map(t=>img(`${ESRI}${style}_Gray_Base/MapServer/tile/${zoom}/${t.y}/${t.x}`,t)));
  labels.replaceChildren(...cells.map(t=>img(`${ESRI}${style}_Gray_Reference/MapServer/tile/${zoom}/${t.y}/${t.x}`,t)));
  wx.replaceChildren();if(frames.length){const was=playing;show(index);if(was){stop();if(visible)start()}}
 }
 function img(src,t,done,filter){const im=new Image();im.alt='';im.decoding='async';if(filter)im.crossOrigin='anonymous';im.style.left=t.left+'px';im.style.top=t.top+'px';if(done){im.onload=()=>{if(filter)filterTile(im,t);done(true)};im.onerror=function(){this.remove();done(false)}}im.src=src;return im}
 // One layer per frame; counts finished tiles so playback can wait for every frame.
 function frameLayer(i){
  let layer=wx.querySelector(`[data-frame="${i}"]`);if(layer||!geometry)return layer;
  layer=el('div');layer.dataset.frame=i;let finished=0,failed=0;const f=frames[i],total=geometry.cells.length;
  layer.append(...geometry.cells.map(t=>img(f.url(geometry.zoom,t.x,t.y),t,ok=>{finished++;if(!ok)failed++;if(finished===total){layer.dataset.ready='1';if(failed===total&&index===i)say('Radar images could not be loaded right now. This does not mean clear weather.')}},source==='iem')));
  if(!total)layer.dataset.ready='1';wx.append(layer);return layer;
 }
 function show(i){
  if(!frames.length)return;index=Math.max(0,Math.min(frames.length-1,i));slider.value=index;const f=frames[index];
  const layer=frameLayer(index);wx.querySelectorAll(':scope>div').forEach(d=>d.classList.toggle('on',d===layer));if(index+1<frames.length)frameLayer(index+1);
  setStamp(f.kind==='radar'?(f.live?'Radar, latest':'Radar'):'Forecast (HRRR model)',fmt(f.time));
  if(msg.textContent.startsWith('Radar images'))say('');
 }
 // Build every frame and wait (up to 10 s) until all tiles have arrived.
 let preloading=0;
 function preload(then){frames.forEach((_,i)=>frameLayer(i));const token=++preloading,t0=Date.now();
  const wait=()=>{if(token!==preloading)return;const ready=wx.querySelectorAll(':scope>div[data-ready]').length;if(ready>=frames.length||Date.now()-t0>10000){analyze();then();return}
   setStamp('Loading frames',ready+' of '+frames.length);setTimeout(wait,200)};wait()}
 // ---------- storm narration: track precipitation near the city across radar frames and check HRRR.
 // Only shown when precipitation is within 100 km and moving toward the city (or already overhead).
 function points(i,pinX,pinY,R){const layer=wx.querySelector(`[data-frame="${i}"]`),pts=[];if(!layer)return pts;
  for(const c of layer.querySelectorAll('canvas')){const m=c._mask,t=c._t;if(!m)continue;for(let y=0;y<64;y++)for(let x=0;x<64;x++){const v=m[y*64+x];if(!v)continue;const gx=t.left+x*4+2,gy=t.top+y*4+2,dx=gx-pinX,dy=gy-pinY;if(dx*dx+dy*dy<=R*R)pts.push({x:gx,y:gy,s:v===2})}}return pts}
 let stormKey='';
 function analyze(){
  storm.hidden=true;if(source!=='iem'||!geometry||!place||!data)return;
  const r=map.getBoundingClientRect(),n=2**geometry.zoom,c=center||{lat:place.lat,lon:place.lon},[cx,cy]=project(c.lat,c.lon,n),[px,py]=project(place.lat,place.lon,n),pinX=px-(cx-r.width/2),pinY=py-(cy-r.height/2);
  const mpp=156543.03*Math.cos(place.lat*Math.PI/180)/n,R=100000/mpp,near=8000/mpp,band=12000/mpp;
  const radar=frames.map((f,i)=>({f,i})).filter(o=>o.f.kind==='radar').map(o=>({t:o.f.time,p:points(o.i,pinX,pinY,R)}));
  const fc=frames.map((f,i)=>({f,i})).filter(o=>o.f.kind==='forecast').map(o=>({t:o.f.time,hit:points(o.i,pinX,pinY,near).length>0}));
  const last=radar[radar.length-1];if(!last||last.p.length<12)return;
  // Centroid motion by least squares over the radar frames that have echoes.
  const cs=radar.filter(o=>o.p.length>=12).map(o=>({t:o.t,x:o.p.reduce((a,b)=>a+b.x,0)/o.p.length,y:o.p.reduce((a,b)=>a+b.y,0)/o.p.length}));
  let vx=0,vy=0;if(cs.length>=3){const tm=cs.reduce((a,b)=>a+b.t,0)/cs.length,xm=cs.reduce((a,b)=>a+b.x,0)/cs.length,ym=cs.reduce((a,b)=>a+b.y,0)/cs.length,den=cs.reduce((a,b)=>a+(b.t-tm)**2,0);if(den>0){vx=cs.reduce((a,b)=>a+(b.t-tm)*(b.x-xm),0)/den;vy=cs.reduce((a,b)=>a+(b.t-tm)*(b.y-ym),0)/den}}
  const speed=Math.hypot(vx,vy),overhead=last.p.some(q=>Math.hypot(q.x-pinX,q.y-pinY)<=near),strongCount=last.p.filter(q=>q.s).length;
  let arrive=null,end=null;
  if(speed*3600000*mpp>5000){const ux=vx/speed,uy=vy/speed,ahead=last.p.map(q=>({s:(pinX-q.x)*ux+(pinY-q.y)*uy,l:Math.abs((pinX-q.x)*uy-(pinY-q.y)*ux)})).filter(o=>o.l<=band&&o.s>-near);
   if(ahead.length){const sMin=Math.max(0,Math.min(...ahead.map(o=>o.s))),sMax=Math.max(...ahead.map(o=>o.s));arrive=overhead?Date.now():last.t+sMin/speed;end=last.t+(sMax+near)/speed}}
  else if(overhead)arrive=Date.now();
  const hStart=fc.find(o=>o.hit)?.t,hEnd=[...fc].reverse().find(o=>o.hit)?.t;
  if(!arrive&&!hStart)return;
  const a=arrive??hStart,e=end??hEnd??null;if(a-Date.now()>6*3600000)return;
  const agree=arrive&&hStart&&Math.abs(arrive-hStart)<=30*60000,conf=arrive&&hStart?(agree?'High':'Low'):'Medium',how=arrive&&hStart?(agree?'radar motion and HRRR agree':'radar motion and HRRR disagree'):arrive?'radar motion only':'HRRR model only';
  const cold=data.current.temperature_2m<=1,kind=strongCount>=8?'Storm':cold?'Snow':'Rain';
  const title=(overhead&&a<=Date.now()+60000?kind+' overhead now':kind+' arrives around '+fmt(a))+(e&&e>a?', done by '+fmt(e):'')+'.';
  storm.replaceChildren(el('b',title),el('span',conf+' confidence ('+how+'). Estimated from radar movement; not a warning.'),el('i',''));storm.hidden=false;
  const level=window.WeatherPersonality?.level()||'mild',f=window.WeatherDetails?.facts();
  if(level!=='off'&&f){storm.querySelector('i').textContent=window.WeatherPersonality.poolLine(f,level,null,'storm'+title);
   const key='storm:'+f.city+':'+title;stormKey=key;if(Weather.quip)try{Weather.quip(JSON.stringify({...f,situation:title+' Comment on it in one line; do not change the times.'}),level,key)}catch(err){}}
 }
 (window.NanoRoutes||(window.NanoRoutes={}))['storm:']=r=>{if(r.id===stormKey&&r.text){const i=storm.querySelector('i');if(i)i.textContent=r.text}};
 function zoomBy(step){const z=Math.max(4,Math.min(maxZoom(),zoom+step));if(z===zoom)return;zoom=z;layout()}
 function stop(){preloading++;playing=false;if(timer){clearTimeout(timer);timer=null}play.innerHTML=PLAY;play.setAttribute('aria-label','Play radar animation');if(frames.length)show(index)}
 // Loops past to future, pausing on "now" and on the last frame so the change is readable.
 function start(){if(playing||!frames.length)return;playing=true;play.innerHTML=PAUSE;play.setAttribute('aria-label','Pause radar animation');
  preload(()=>{const tick=()=>{const next=(index+1)%frames.length;show(next);const f=frames[next];timer=setTimeout(tick,f.live||next===frames.length-1?1600:650)};timer=setTimeout(tick,500)})}
 play.onclick=()=>{if(playing)stop();else start()};
 slider.oninput=()=>{stop();show(Number(slider.value))};

 // One finger pans; two fingers pinch-zoom (snapped to whole zoom levels on release).
 const pts=new Map();let drag=null,pinch=null;
 const setT=t=>layers.forEach(l=>l.style.transform=t);
 map.addEventListener('pointerdown',e=>{if(e.target.closest('button')||!place)return;map.setPointerCapture(e.pointerId);pts.set(e.pointerId,{x:e.clientX,y:e.clientY});
  if(pts.size===2){const [a,b]=[...pts.values()],r=map.getBoundingClientRect();pinch={d0:Math.hypot(a.x-b.x,a.y-b.y),mx:(a.x+b.x)/2-r.left,my:(a.y+b.y)/2-r.top,k:1};drag=null;layers.forEach(l=>{l.style.setProperty('--ox',pinch.mx+'px');l.style.setProperty('--oy',pinch.my+'px')})}
  else if(pts.size===1){drag={x:e.clientX,y:e.clientY,c:center||{lat:place.lat,lon:place.lon}};map.style.cursor='grabbing'}});
 map.addEventListener('pointermove',e=>{if(!pts.has(e.pointerId))return;pts.set(e.pointerId,{x:e.clientX,y:e.clientY});
  if(pinch&&pts.size===2){const [a,b]=[...pts.values()];pinch.k=Math.max(.25,Math.min(4,Math.hypot(a.x-b.x,a.y-b.y)/pinch.d0));setT(`scale(${pinch.k})`)}
  else if(drag)setT(`translate(${e.clientX-drag.x}px,${e.clientY-drag.y}px)`)});
 const release=e=>{pts.delete(e.pointerId);map.style.cursor='';
  if(pinch){if(pts.size<2){const dz=Math.round(Math.log2(pinch.k));pinch=null;drag=null;setT('');layers.forEach(l=>{l.style.removeProperty('--ox');l.style.removeProperty('--oy')});if(dz)zoomBy(dz)}return}
  if(!drag)return;const n=2**zoom,[cx,cy]=project(drag.c.lat,drag.c.lon,n),x=cx-(e.clientX-drag.x),y=Math.max(1,Math.min(n*256-1,cy-(e.clientY-drag.y)));
  const moved=Math.hypot(e.clientX-drag.x,e.clientY-drag.y)>3;if(moved)center={lon:((x/(n*256)*360+180)%360+360)%360-180,lat:Math.atan(Math.sinh(Math.PI*(1-2*y/(n*256))))*180/Math.PI};drag=null;setT('');if(moved)layout()};
 map.addEventListener('pointerup',release);map.addEventListener('pointercancel',e=>{pts.delete(e.pointerId);drag=null;pinch=null;setT('')});

 function scaleLabels(){
  const past=frames.filter(f=>f.kind==='radar').length,future=frames.length-past;scale.replaceChildren();
  const a=el('span',past?'Radar':'');a.style.flex=String(Math.max(past-.5,0));scale.append(a);if(future){const b=el('span','Forecast');b.style.flex=String(future+.5);scale.append(b)}
 }
 // Called by the Android bridge with {iem:{n0q,hrrr},rainviewer} (any part may be null).
 window.radarReady=d=>{
  if(!place)return;stop();frames=[];us=isCONUS(place);const now=Date.now();
  const n0q=Date.parse(d?.iem?.n0q?.meta?.valid),init=Date.parse(d?.iem?.hrrr?.model_init_utc);
  if(us&&Number.isFinite(n0q)){source='iem';
   for(const lag of [50,40,30,20,10,0]){const layer='nexrad-n0q-900913'+(lag?'-m'+String(lag).padStart(2,'0')+'m':'');frames.push({kind:'radar',time:n0q-lag*60000,live:!lag,url:(z,x,y)=>`${IEM}${layer}/${z}/${x}/${y}.png`})}
   if(Number.isFinite(init)){const base15=Math.ceil(now/900000)*900000;for(const ahead of [30,60,90,120,180,240,360]){const t=base15+ahead*60000,minute=Math.round((t-init)/900000)*15;if(minute<15||minute>1080)continue;const layer='hrrr::REFD-F'+String(minute).padStart(4,'0')+'-0';frames.push({kind:'forecast',time:init+minute*60000,url:(z,x,y)=>`${IEM}${layer}/${z}/${x}/${y}.png`})}}
   note.textContent='Radar: NOAA via IEM. Weak echoes hidden.';
  }else{source='rv';
   const rv=d?.rainviewer;if(rv?.host==='https://tilecache.rainviewer.com')for(const f of (rv.radar?.past||[]).filter(f=>/^\/v2\/radar\/[a-f0-9]+$/.test(f.path)))frames.push({kind:'radar',time:f.time*1000,url:(z,x,y)=>`${rv.host}${f.path}/256/${Math.min(z,7)}/${x}/${y}/2/1_1.png`});
   if(frames.length)frames[frames.length-1].live=true;zoom=Math.min(zoom,7);
   note.textContent=(us?'':'Forecast frames cover the continental US only. ')+'Radar: RainViewer.';
  }
  swatch.style.background=`linear-gradient(90deg,${LEGEND[source].join(',')})`;
  slider.max=Math.max(0,frames.length-1);scaleLabels();loadedFor=cityKey();loadedAt=now;
  if(!frames.length){say('Radar is unavailable right now. This does not mean clear weather.');setStamp('Radar','unavailable');layout();return}
  say('');layout();show(frames.findIndex(f=>f.live));
  if(still)preload(()=>{});
  // Autoplay once per city when the card is on screen (not under reduced motion), after preloading.
  if(!still&&visible&&autoplayed!==loadedFor){autoplayed=loadedFor;start()}
 };
 let askedFor='',askedAt=0;
 function load(force){if(!place||!visible)return;if(!force&&loadedFor===cityKey()&&Date.now()-loadedAt<10*60000)return;if(askedFor===cityKey()&&Date.now()-askedAt<15000)return;askedFor=cityKey();askedAt=Date.now();if(loadedFor!==cityKey()){center=null;zoom=defaultZoom();frames=[];wx.replaceChildren();setStamp('Radar','loading');layout()}Weather.radar?.()}
 window.radarUnavailable=()=>{if(!frames.length){setStamp('Radar','unavailable');say('Radar could not be loaded. Check your connection, then refresh.')}};
 // Load only when the card is on or near the screen.
 function check(){const r=card.getBoundingClientRect(),near=r.bottom>-300&&r.top<innerHeight+300;if(near!==visible){visible=near;if(!near)stop();else if(!still&&frames.length&&autoplayed!==loadedFor){autoplayed=loadedFor;start()}}if(near)load(false)}
 addEventListener('scroll',check,{passive:true});addEventListener('resize',check);
 const render=renderWeather;renderWeather=function(d){render(d);if(data===d)check()};
 const baseRefresh=refresh;refresh=function(){baseRefresh();load(true)};
 new ResizeObserver(()=>{const w=map.getBoundingClientRect().width;if(w&&w!==map._w){map._w=w;layout()}}).observe(map);
 new MutationObserver(layout).observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
 document.addEventListener('visibilitychange',()=>{if(document.hidden)stop()});
 if(place)setStamp('Radar','loading');setTimeout(check,0);
})();
