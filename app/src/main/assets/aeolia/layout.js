/* The one place that owns card order and grid placement.
   Cover screen: one column. Inner screen: two columns; wide cards span both, half cards pair up,
   and a half card left without a partner spans both so the grid never has a hole.
   Reorder: long-press anywhere on a card and drag it, or Settings > Arrange cards. The current-weather
   scene always stays first. */
(() => {
 const main=document.querySelector('main'),prefs=window.BetweenPreferences,el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e};
 const DEFAULT=['alerts','changes','hourly','daily','details','uv','next-hour','radar','precip-24','day-plan','sun-path','moon','holidays'];
 const WIDE=new Set(['alerts','changes','hourly','daily','details','radar','precip-24']);
 const css=el('style');css.textContent=`
 main{display:grid;grid-template-columns:minmax(0,1fr);gap:14px;padding:0 14px 24px;align-items:stretch}
 main>.hero{grid-column:1/-1;margin:0 -14px}
 @media(min-width:680px){
  main{grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;padding:0 clamp(18px,3vw,40px) 32px}
  main>.hero{margin:0 calc(-1*clamp(18px,3vw,40px))}
  main>[data-span=wide],main>[data-span=solo]{grid-column:1/-1}
 }
 .card.dragging{position:fixed!important;z-index:60;pointer-events:none;margin:0!important;box-shadow:0 26px 60px rgba(0,0,0,.5);opacity:.94;transition:none!important;animation:none!important}
 .card-placeholder{border-radius:22px;border:2px dashed currentColor;opacity:.25}
 .arrange-row{display:flex;align-items:center;gap:4px;padding:6px 0;border-bottom:1px solid}.arrange-row span{flex:1;font-size:14px}.arrange-row button{min-width:44px}`;
 document.head.append(css);
 const cards=()=>[...main.children].filter(n=>n.dataset.cardId&&n.dataset.cardId!=='hero');
 const title=c=>c.querySelector('h2')?.textContent||c.dataset.cardId;
 function order(){
  const present=new Set(cards().map(c=>c.dataset.cardId)),saved=(prefs.get().cardOrder||[]).filter(id=>present.has(id)),ids=[...new Set(saved)];
  // New cards go in after the card that precedes them in the default order.
  for(const [i,id] of DEFAULT.entries()){if(!present.has(id)||ids.includes(id))continue;const prev=DEFAULT.slice(0,i).reverse().find(p=>ids.includes(p));ids.splice(prev?ids.indexOf(prev)+1:0,0,id)}
  for(const c of cards())if(!ids.includes(c.dataset.cardId))ids.push(c.dataset.cardId);
  return ids;
 }
 function spans(list){let run=[];const flush=()=>{if(run.length%2)run[run.length-1].dataset.span='solo';run.forEach((n,i)=>{n.dataset.col=n.dataset.span==='solo'?'':String(i%2+1)});run=[]};for(const n of list){const id=n.dataset.cardId||n.dataset.forId;if(WIDE.has(id)){flush();n.dataset.span='wide'}else{n.dataset.span='half';run.push(n)}}flush()}
 function apply(){const ids=order(),hero=main.querySelector('.hero');main.prepend(hero);ids.forEach(id=>{const c=main.querySelector(`:scope>[data-card-id="${id}"]`);if(c)main.append(c)});spans(cards())}
 // Pairs whose natural heights differ a lot would leave a hole beside the shorter one; such
 // pairs are split and each section takes the full width instead.
 // Measured with the grid stretch switched off, so a short section reports its own height.
 function natural(c){const was=c.style.alignSelf;c.style.alignSelf='start';const h=c.offsetHeight;c.style.alignSelf=was;return h}
 function balance(){if(!matchMedia('(min-width:680px)').matches)return;const list=cards();for(let i=0;i+1<list.length;i++){const a=list[i],b=list[i+1];if(a.dataset.col!=='1'||b.dataset.col!=='2')continue;
  const ha=natural(a),hb=natural(b);if(Math.max(ha,hb)>1.45*Math.max(1,Math.min(ha,hb))){for(const n of [a,b]){n.dataset.span='solo';n.dataset.col=''}}i++}}
 let relayoutTimer=0;function relayout(){clearTimeout(relayoutTimer);relayoutTimer=setTimeout(()=>{if(drag)return;spans(cards());balance()},150)}
 new MutationObserver(relayout).observe(main,{childList:true,subtree:true});addEventListener('resize',relayout);
 function save(){prefs.get().cardOrder=cards().map(c=>c.dataset.cardId);prefs.save()}

 // Launcher-style reordering: long-press anywhere on a card (not on its controls), the card lifts
 // and follows the finger, the others reflow live with FLIP animations, and it settles on drop.
 // Moving more than 10 px before the press completes cancels it, so scrolling is unaffected.
 let press=null,drag=null;const still=matchMedia('(prefers-reduced-motion: reduce)').matches;
 const twoColumns=()=>matchMedia('(min-width:680px)').matches;
 const INTERACTIVE='button,a,input,select,textarea,summary,details[open],.radar-map,.radar-bar,.hours,#days,.card-tools,[contenteditable]';
 main.addEventListener('pointerdown',e=>{if(e.button>0||drag)return;const card=e.target.closest('main>[data-card-id]');if(!card||card.dataset.cardId==='hero'||e.target.closest(INTERACTIVE))return;
  press={card,x:e.clientX,y:e.clientY,id:e.pointerId,timer:setTimeout(()=>lift(card,press.x,press.y),450)}});
 const cancelPress=()=>{if(press){clearTimeout(press.timer);press=null}};
 // FLIP: remember where every card is, change the DOM, then animate each card from old to new spot.
 function flip(change){const list=[...main.children].filter(n=>n!==drag?.card&&(n.dataset.cardId||n.classList.contains('card-placeholder'))),before=new Map(list.map(n=>[n,n.getBoundingClientRect()]));change();
  if(still)return;for(const n of list){const a=before.get(n),b=n.getBoundingClientRect();const dx=a.left-b.left,dy=a.top-b.top;if(Math.abs(dx)<1&&Math.abs(dy)<1)continue;
   n.animate([{transform:`translate(${dx}px,${dy}px)`},{transform:'none'}],{duration:240,easing:'cubic-bezier(.2,.8,.2,1)'})}}
 function lift(card,x,y){press=null;const r=card.getBoundingClientRect(),ph=el('div',undefined,'card-placeholder');ph.dataset.forId=card.dataset.cardId;ph.dataset.span=card.dataset.span;ph.style.height=r.height+'px';card.before(ph);
  drag={card,ph,dx:x-r.left,dy:y-r.top,x,y,left:r.left,top:r.top};Object.assign(card.style,{left:r.left+'px',top:r.top+'px',width:r.width+'px',height:r.height+'px'});card.classList.add('dragging');
  try{Weather.haptic?.()}catch(e){}requestAnimationFrame(autoScroll);follow(x,y)}
 function follow(x,y){const {card,ph}=drag;card.style.transform=`translate(${x-drag.dx-drag.left}px,${y-drag.dy-drag.top}px) scale(1.03)`;
  const target=document.elementFromPoint(x,y)?.closest('main>[data-card-id],main>.card-placeholder');if(!target||target===ph||target===card||target.classList.contains('hero'))return;
  const r=target.getBoundingClientRect(),half=twoColumns()&&target.dataset.span==='half',before=half?x<r.left+r.width/2:y<r.top+r.height/2;
  if(before?target.previousElementSibling===ph:target.nextElementSibling===ph)return;
  flip(()=>{if(before)target.before(ph);else target.after(ph);spans([...main.children].filter(n=>n!==card&&(n===ph||n.dataset.cardId&&n.dataset.cardId!=='hero')))})}
 function autoScroll(){if(!drag)return;const edge=90;let dy=0;if(drag.y<edge)dy=-Math.ceil((edge-drag.y)/5);else if(drag.y>innerHeight-edge)dy=Math.ceil((drag.y-innerHeight+edge)/5);if(dy){scrollBy(0,dy);follow(drag.x,drag.y)}requestAnimationFrame(autoScroll)}
 function drop(){const {card,ph}=drag;drag=null;const to=ph.getBoundingClientRect(),from=card.getBoundingClientRect();
  ph.replaceWith(card);card.classList.remove('dragging');for(const k of ['left','top','width','height','transform'])card.style[k]='';spans(cards());save();
  if(!still)card.animate([{transform:`translate(${from.left-to.left}px,${from.top-to.top}px) scale(1.03)`,boxShadow:'0 24px 60px rgba(0,0,0,.45)'},{transform:'none',boxShadow:'0 0 0 rgba(0,0,0,0)'}],{duration:240,easing:'cubic-bezier(.2,.8,.2,1)'})}
 addEventListener('pointermove',e=>{if(press&&Math.hypot(e.clientX-press.x,e.clientY-press.y)>10)cancelPress();else if(press){press.x=e.clientX;press.y=e.clientY}if(drag){drag.x=e.clientX;drag.y=e.clientY;follow(e.clientX,e.clientY)}});
 addEventListener('pointerup',()=>{cancelPress();if(drag)drop()});addEventListener('pointercancel',()=>{cancelPress();if(drag)drop()});
 document.addEventListener('touchmove',e=>{if(drag)e.preventDefault()},{passive:false});
 document.addEventListener('contextmenu',e=>{if(press||drag)e.preventDefault()});
 document.addEventListener('scroll',()=>{if(press&&!drag)cancelPress()},{passive:true});

 // Accessible alternative in Settings.
 const dialog=el('dialog',undefined,'settings-sheet');dialog.id='arrange-cards';document.body.append(dialog);
 function move(id,step){const ids=cards().map(c=>c.dataset.cardId),i=ids.indexOf(id),j=i+step;if(i<0||j<0||j>=ids.length)return;[ids[i],ids[j]]=[ids[j],ids[i]];prefs.get().cardOrder=ids;prefs.save();apply();drawArrange()}
 function drawArrange(){dialog.replaceChildren();const close=el('button','Done','close');close.onclick=()=>dialog.close();dialog.append(close,el('h2','Arrange cards'),el('p','Or long-press any card on the main screen and drag it. Current weather stays at the top.'));const list=cards();for(const [i,c] of list.entries()){const row=el('div',undefined,'arrange-row');row.append(el('span',title(c)));for(const [sym,step,label] of [['↑',-1,'Move up'],['↓',1,'Move down']]){const b=el('button',sym);b.setAttribute('aria-label',label+' '+title(c));b.disabled=i+step<0||i+step>=list.length;b.onclick=()=>move(c.dataset.cardId,step);row.append(b)}dialog.append(row)}}
 const open=el('button','Arrange cards');open.id='arrange-cards-button';open.onclick=()=>{drawArrange();dialog.showModal()};
 const about=el('section',undefined,'about');about.append(el('h3','About'),
  el('p','Forecasts, air quality and city search: Open-Meteo (CC BY 4.0), with GeoNames place data and CAMS air quality.'),
  el('p','US alerts: National Weather Service. Radar: NOAA NEXRAD composite and HRRR forecast reflectivity via Iowa Environmental Mesonet, with echoes below 20 dBZ and isolated pixels hidden; RainViewer outside the US. Radar shows intensity and cannot tell rain from snow, and is not a warning service. Map: Esri, HERE, Garmin, OpenStreetMap contributors.'),
  el('p','Holidays: Google public country calendars. Sun and moon: SunCalc.'),
  el('p','Your selected city is sent to these weather services. Location is used only when you tap Use device location. Gemini Nano runs on the phone through Google ML Kit, which may send Google its own usage statistics.'));
 document.getElementById('weather-settings')?.append(open,about);
 apply();relayout();window.WeatherLayout={apply,order,relayout};
})();
