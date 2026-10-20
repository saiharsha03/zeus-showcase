/* Scene catalogue and viewport-aware animation mounting. No human figures. */
(() => {
 const stories=window.DistinctWeatherScenes.stories;
 const presets=Object.values(stories).map(s=>({id:s.id,title:s.title,description:s.description,alert:s.alert}));
 const render=(c,t,p,tap=100)=>window.DistinctWeatherScenes.render(c,t,p,tap);
 function mount(canvas,p){const ctx=canvas.getContext('2d');let t=0,last=0,trigger=-100,painted=false;const tile=canvas.closest('.tile');canvas.width=800;canvas.height=500;canvas.style.cssText='width:100%;height:100%;display:block;cursor:pointer';canvas.tabIndex=0;canvas.setAttribute('role','button');canvas.setAttribute('aria-label',p.title+'. Tap to interact with the animal.');const tap=()=>{trigger=t};canvas.addEventListener('pointerdown',tap);canvas.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();tap()}});if(tile){const h=tile.querySelector('h2'),badge=h?.querySelector('.badge');if(h){h.textContent=p.title;if(badge)h.append(badge)}const note=tile.querySelector('p');if(note)note.textContent=p.description+(p.alert?' Illustration only; live NWS alerts are shown separately.':'')}
 function loop(stamp){const dt=last?Math.min(.04,(stamp-last)/1000):0;last=stamp;const stopped=document.hidden||tile?.classList.contains('offscreen')||tile?.classList.contains('paused');if(!stopped||!painted){painted=true;if(!stopped)t+=dt;ctx.setTransform(2,0,0,2,0,0);render(ctx,t,p,t-trigger)}requestAnimationFrame(loop)}requestAnimationFrame(loop)}
 window.WeatherCharacterScenes={presets,render,mount};document.querySelectorAll('[data-character-scene]').forEach(canvas=>{const p=presets.find(p=>p.id===canvas.dataset.characterScene);if(p)mount(canvas,p)});
})();
