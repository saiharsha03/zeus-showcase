/* Small moments of motion: cards rise in on first load, the temperature counts up when a city
   loads, and the page takes a faint tint from the current conditions. CSS lives in fold-theme.js.
   Everything is skipped under prefers-reduced-motion. */
(() => {
 const main=document.querySelector('main'),still=matchMedia('(prefers-reduced-motion: reduce)').matches;
 let shownFor='';
 function mood(c,day){return c>=95?'storm':c>=71&&c<=77||c===85||c===86?'snow':c>=51&&c<=67||c>=80&&c<=82?'rain':c===45||c===48?'fog':!day?'night':c<=1?'sun':'cloudy'}
 function countUp(node,to,suffix){if(still||!Number.isFinite(to)){node.textContent=to+suffix;return}const from=0,start=performance.now(),dur=900;
  const step=t=>{const k=Math.min(1,(t-start)/dur),e=1-Math.pow(1-k,3);node.textContent=Math.round(from+(to-from)*e)+suffix;if(k<1)requestAnimationFrame(step);else if(node.id==='fahrenheit'){node.classList.remove('bounce');void node.offsetWidth;node.classList.add('bounce')}};requestAnimationFrame(step)}
 function enter(){if(still)return;cards().forEach((c,i)=>c.style.setProperty('--i',i));main.classList.add('entering');setTimeout(()=>main.classList.remove('entering'),1800)}
 const cards=()=>[...main.children].filter(n=>n.dataset.cardId&&n.dataset.cardId!=='hero');
 const render=renderWeather;renderWeather=function(d){render(d);if(data===d)apply(d)};
 function apply(d){const c=d.current;
  document.body.dataset.mood=mood(c.weather_code,c.is_day===1);
  const key=place.lat.toFixed(3)+','+place.lon.toFixed(3);if(key===shownFor)return;shownFor=key;
  countUp($('fahrenheit'),Math.round(c.temperature_2m*9/5+32),'°F');countUp($('celsius'),Math.round(c.temperature_2m),'°C');
 }
 if(data&&place)apply(data);enter();
})();
