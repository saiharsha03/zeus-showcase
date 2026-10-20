/* Samsung Weather scene: sky, layered landscape with gentle parallax, condition particles, and the
   Samsung character video. The character MP4s pack colour (top 640x640) and a half-resolution alpha
   mask (bottom 640x160, four 160x160 blocks in column order: top-left, bottom-left, top-right,
   bottom-right). A WebGL shader applies that mask as real alpha instead of keying out black.
   The scene pack (assets/aeolia/samsung/) is Samsung's art and is local-only: it is not in this repository and must
   not be published. Without it the page never calls this scene (window.SAMSUNG_PACK is false). */
(() => {
 const LABELS={'sunny':'Sunny','sunny-hot':'Sunny, hot','sunny-cold':'Sunny, below zero','clear':'Clear night','clear-hot':'Clear night, warm','clear-cold':'Clear night, freezing',
  'cloudy':'Cloudy','cloudy-night':'Cloudy night','rain':'Rain','rain-heavy':'Heavy rain','rain-shower':'Showers','rain-night':'Rain at night','rain-night-heavy':'Heavy rain at night','rain-night-shower':'Showers at night',
  'snow':'Snow','snow-heavy':'Heavy snow','snow-hail':'Hail','snow-night':'Snow at night','snow-night-heavy':'Heavy snow at night','snow-night-hail':'Hail at night','thunder':'Thunderstorm','windy':'Windy','windy-night':'Windy night'};
 window.SamsungSceneCatalog=Object.entries(LABELS).map(([k,v])=>({id:'samsung:'+k,title:'Samsung: '+v}));
 const still=matchMedia('(prefers-reduced-motion: reduce)').matches;

 // Character for the current conditions.
 function pick(){
  const D=typeof data!=='undefined'?data:null,c=typeof weatherCode==='number'?weatherCode:0,day=typeof isDay==='boolean'?isDay:true,t=D?.current?.temperature_2m??15,wind=D?.current?.wind_speed_10m??0,n=day?'':'-night';
  if(c>=95)return 'thunder';
  if(c===77||c===96||c===99)return 'snow'+n+'-hail';
  if(c>=71&&c<=77||c===85||c===86)return 'snow'+n+(c===75||c===86?'-heavy':'');
  if(c>=51&&c<=67||c>=80&&c<=82)return 'rain'+n+(c===65||c===67||c===82?'-heavy':c>=80?'-shower':'');
  if(wind>=35)return 'windy'+n;
  if(c===3||c===45||c===48)return day?'cloudy':'cloudy-night';
  return (day?'sunny':'clear')+(t>27?'-hot':t<0?'-cold':'');
 }
 const kindOf=k=>k.startsWith('thunder')?'storm':k.startsWith('snow')?'snow':k.startsWith('rain')?'rain':k.startsWith('windy')?'wind':k.startsWith('cloudy')?'cloudy':k.startsWith('clear')?'night':'sunny';
 const LAND={sunny:'sunny',night:'night',cloudy:'cloudy',rain:'rain',storm:'rain',snow:'snow',wind:'cloudy'};
 const SKY={sunny:['#2f86e0','#7cc1f2','#cfeaf9'],night:['#08131f','#17304a','#365069'],cloudy:['#6f8799','#a9bac6','#dbe2e7'],rain:['#3e5566','#6f8796','#a8b7bf'],storm:['#232a33','#46505d','#7a8591'],snow:['#8fb0cc','#c3d6e6','#eef4f9'],wind:['#5d86a8','#9fbfd6','#d7e6ef']};

 // ---- WebGL character layer
 const hero=document.querySelector('.hero'),glc=document.createElement('canvas');glc.className='hero-char';glc.setAttribute('aria-hidden','true');
 glc.style.cssText='position:absolute;z-index:-2;pointer-events:none;transform-origin:50% 92%';hero.append(glc);
 const gl=glc.getContext('webgl',{premultipliedAlpha:true,alpha:true,antialias:false});
 let prog=null,tex=null;
 if(gl){
  const vs='attribute vec2 p;varying vec2 uv;void main(){uv=vec2(p.x*.5+.5,.5-p.y*.5);gl_Position=vec4(p,0.,1.);}';
  // colour = top 640x640 of the frame; alpha = half-res mask packed into the bottom 640x160 strip.
  const fs=`precision mediump float;varying vec2 uv;uniform sampler2D v;
   void main(){vec3 col=texture2D(v,vec2(uv.x,uv.y*.8)).rgb;vec2 m=clamp(uv*320.,vec2(.5),vec2(319.5));float bx=floor(m.x/160.),by=floor(m.y/160.);
    vec2 inb=clamp(mod(m,160.),vec2(.75),vec2(159.25));vec2 sp=vec2((bx*2.+by)*160.+inb.x,640.+inb.y);float a=texture2D(v,sp/vec2(640.,800.)).g;
    a=smoothstep(.06,.94,a);gl_FragColor=vec4(col*a,a);}`;
  const sh=(t,s)=>{const o=gl.createShader(t);gl.shaderSource(o,s);gl.compileShader(o);return o};
  prog=gl.createProgram();gl.attachShader(prog,sh(gl.VERTEX_SHADER,vs));gl.attachShader(prog,sh(gl.FRAGMENT_SHADER,fs));gl.linkProgram(prog);gl.useProgram(prog);
  const b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
  const loc=gl.getAttribLocation(prog,'p');gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);
  tex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tex);for(const [k,v] of [[gl.TEXTURE_MIN_FILTER,gl.LINEAR],[gl.TEXTURE_MAG_FILTER,gl.LINEAR],[gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]])gl.texParameteri(gl.TEXTURE_2D,k,v);
 }
 // Seconds at which each clip's character is fully inside its frame (measured from the packed alpha);
 // the first second or so is the character walking in, so playback loops from here.
 const START={"clear-cold":0.8,"clear-hot":0.8,"clear":0.8,"cloudy-night":0.8,"cloudy":0.8,"rain-heavy":1.0,"rain-night-heavy":0.8,"rain-night-shower":1.0,"rain-night":1.0,"rain-shower":1.0,"rain":1.0,"snow-hail":1.4,"snow-heavy":1.2,"snow-night-hail":1.4,"snow-night-heavy":1.2,"snow-night":1.2,"snow":1.2,"sunny-cold":0.8,"sunny-hot":0.8,"sunny":0.8,"thunder":0.8,"windy-night":1.0,"windy":1.0};
 const video=document.createElement('video');video.muted=true;video.loop=true;video.playsInline=true;video.setAttribute('playsinline','');video.preload='auto';
 let current='',blobUrl='',ready=false;
 async function load(key){
  if(key===current)return;current=key;ready=false;const src='samsung/chars/'+key+'.mp4';
  try{const r=await fetch(src);if(!r.ok)throw 0;const url=URL.createObjectURL(await r.blob());if(current!==key){URL.revokeObjectURL(url);return}if(blobUrl)URL.revokeObjectURL(blobUrl);blobUrl=url;video.src=url}catch(e){video.src=src}
  const from=START[key]||0;// No seeking while it plays: seeks from timeupdate froze the clip on this WebView. Each loop starts with the walk-in.
  video.ontimeupdate=null;
  video.onloadeddata=()=>{ready=true;if(still){video.currentTime=from+.7;video.pause()}else video.play().catch(()=>{})};
 }
 function placeCharacter(w,h){// The character walks across its frame, so the whole frame stays on screen, anchored bottom right.
  const narrow=w<600,S=Math.round(Math.min(h*(narrow?.85:1),narrow?w:w*.7)),left=Math.round(narrow?w-S:w-S-w*.04),top=Math.round(h-S*.96);
  if(glc._k!==S+'|'+left+'|'+top){glc._k=S+'|'+left+'|'+top;Object.assign(glc.style,{width:S+'px',height:S+'px',left:left+'px',top:top+'px'});const px=Math.min(2,devicePixelRatio||1);glc.width=Math.round(S*px);glc.height=Math.round(S*px)}}
 function drawCharacter(){if(!gl||!ready||video.readyState<2)return;gl.viewport(0,0,glc.width,glc.height);gl.bindTexture(gl.TEXTURE_2D,tex);
  try{gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,video)}catch(e){return}gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);gl.drawArrays(gl.TRIANGLE_STRIP,0,4)}

 // ---- background: sky, sun/moon glow, far lake, ground, particles
 const img={};const layer=(name)=>{if(!img[name]){const i=new Image();i.src='samsung/'+name+'.webp';img[name]=i}return img[name]};
 const drops=Array.from({length:90},(_,i)=>({x:Math.random(),y:Math.random(),s:.6+Math.random()*.8,l:.6+Math.random()*.6}));
 // The UI palette is sampled from the scene: the mid sky colour and the average ground colour.
 let sampledFor='';
 function samplePalette(kind,ground){const key=kind+(ground.complete&&ground.naturalWidth?'+g':'');if(key===sampledFor)return;sampledFor=key;const root=document.documentElement.style;root.setProperty('--scene-sky',(SKY[kind]||SKY.sunny)[1]);
  if(ground.complete&&ground.naturalWidth){try{const c=document.createElement('canvas');c.width=48;c.height=8;const x=c.getContext('2d',{willReadFrequently:true});x.drawImage(ground,0,ground.naturalHeight*.15,ground.naturalWidth,ground.naturalHeight*.5,0,0,48,8);const d=x.getImageData(0,0,48,8).data;let r=0,g=0,b=0,n=0;for(let i=0;i<d.length;i+=4)if(d[i+3]>200){r+=d[i];g+=d[i+1];b+=d[i+2];n++}if(n)root.setProperty('--scene-ground',`rgb(${Math.round(r/n)},${Math.round(g/n)},${Math.round(b/n)})`)}catch(e){}}}
 function background(ctx,w,h,t,kind){
  const sky=SKY[kind]||SKY.sunny,g=ctx.createLinearGradient(0,0,0,h*.75);g.addColorStop(0,sky[0]);g.addColorStop(.6,sky[1]);g.addColorStop(1,sky[2]);ctx.fillStyle=g;ctx.fillRect(0,0,w,h);
  if(kind==='sunny'){const r=ctx.createRadialGradient(w*.82,h*.18,0,w*.82,h*.18,h*.6);r.addColorStop(0,'rgba(255,248,214,'+(.75+.08*Math.sin(t*.8))+')');r.addColorStop(.18,'rgba(255,236,170,.35)');r.addColorStop(1,'rgba(255,236,170,0)');ctx.fillStyle=r;ctx.fillRect(0,0,w,h)}
  if(kind==='night'){ctx.fillStyle='#f3f1ff';for(let i=0;i<60;i++){const x=(i*97.3%1)*w,y=(i*53.7%1)*h*.5,tw=.4+.6*Math.abs(Math.sin(t*.7+i));ctx.globalAlpha=tw*.8;ctx.fillRect((x*7.13)%w,y,1.5,1.5)}ctx.globalAlpha=1;
   const m=ctx.createRadialGradient(w*.8,h*.17,0,w*.8,h*.17,40);m.addColorStop(0,'#fffbe8');m.addColorStop(.35,'#fff6d0');m.addColorStop(.45,'rgba(255,246,208,.25)');m.addColorStop(1,'rgba(255,246,208,0)');ctx.fillStyle=m;ctx.fillRect(0,0,w,h)}
  const land=LAND[kind]||'sunny',far=layer(land+'-far'),ground=layer(land+'-ground');samplePalette(kind,ground);
  if(far.complete&&far.naturalWidth){const fh=h*.2,fw=Math.max(w*1.3,fh*10.5),x=(w-fw)/2+Math.sin(t*.12)*w*.03;ctx.drawImage(far,x,h*.56,fw,fh)}
  if(ground.complete&&ground.naturalWidth){const gh=h*.36,gw=Math.max(w*1.15,gh*10.5),x=(w-gw)/2+Math.sin(t*.12)*w*.012;ctx.drawImage(ground,x,h*.7,gw,gh)}
  // particles
  const T=still?0:t;
  if(kind==='rain'||kind==='storm'){ctx.strokeStyle='rgba(225,240,255,.55)';ctx.lineWidth=1.2;ctx.beginPath();for(const d of drops){const y=((d.y+T*.9*d.s)%1)*h,x=((d.x+T*.08)%1)*w;ctx.moveTo(x,y);ctx.lineTo(x-4*d.l,y+16*d.l)}ctx.stroke()}
  if(kind==='snow'){ctx.fillStyle='rgba(255,255,255,.9)';for(const d of drops){const y=((d.y+T*.06*d.s)%1)*h,x=((d.x+Math.sin(T*.6+d.y*12)*.01)%1)*w;ctx.beginPath();ctx.arc(x,y,1.2+d.l*1.8,0,6.3);ctx.fill()}}
  if(kind==='cloudy'||kind==='wind'){for(let i=0;i<3;i++){const y=h*(.18+i*.12),x=((T*.02*(i+1)+i*.33)%1.4-.2)*w,r=ctx.createRadialGradient(x,y,0,x,y,w*.35);r.addColorStop(0,'rgba(255,255,255,.28)');r.addColorStop(1,'rgba(255,255,255,0)');ctx.fillStyle=r;ctx.fillRect(0,0,w,h)}}
  if(kind==='storm'&&!still&&Math.sin(t*1.7)>.995){ctx.fillStyle='rgba(235,230,255,.55)';ctx.fillRect(0,0,w,h)}
 }

 // Pause everything when the hero is off screen or the app is in the background.
 let onScreen=true;const visibility=()=>{const r=hero.getBoundingClientRect();onScreen=r.bottom>0&&!document.hidden;if(!ready)return;if(onScreen&&!still)video.play().catch(()=>{});else video.pause()};
 addEventListener('scroll',visibility,{passive:true});document.addEventListener('visibilitychange',visibility);

 // Tap the character: a little hop and a burst of speed.
 hero.addEventListener('click',e=>{if(e.target.closest('button,a,input'))return;const r=glc.getBoundingClientRect();if(e.clientX<r.left+r.width*.5||glc.style.display==='none')return;if(still)return;
  glc.classList.remove('hop');void glc.offsetWidth;glc.classList.add('hop');video.playbackRate=1.8;setTimeout(()=>video.playbackRate=1,900)});

 window.SamsungScene={pick,kindOf,video,
  draw(ctx,w,h,t,key){glc.style.display='';load(key);if(!onScreen)return true;const kind=kindOf(key);background(ctx,w,h,t,kind);placeCharacter(w,h);drawCharacter();document.body.dataset.scene=kind;return true},
  hide(){glc.style.display='none';video.pause()}};
})();
