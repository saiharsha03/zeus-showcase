/* Original, code-drawn scene studies. No external images or animation libraries. */
(() => {
  const TAU=Math.PI*2, clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));
  const ease=v=>{v=clamp(v);return v*v*(3-2*v)};
  function ellipse(c,x,y,rx,ry,color,rotation=0){c.fillStyle=color;c.beginPath();c.ellipse(x,y,rx,ry,rotation,0,TAU);c.fill()}
  function path(c,points,color,stroke,width=1){c.beginPath();for(const p of points){const [op,...args]=p;c[op](...args)}if(color){c.fillStyle=color;c.fill()}if(stroke){c.strokeStyle=stroke;c.lineWidth=width;c.lineCap='round';c.lineJoin='round';c.stroke()}}
  function line(c,x,y,x2,y2,color,width=1){path(c,[['moveTo',x,y],['lineTo',x2,y2]],null,color,width)}
  function sky(c,top,bottom){let g=c.createLinearGradient(0,0,0,250);g.addColorStop(0,top);g.addColorStop(1,bottom);c.fillStyle=g;c.fillRect(0,0,400,250)}
  function cloud(c,x,y,s,alpha=.4){c.save();c.globalAlpha=alpha;ellipse(c,x,y,28*s,8*s,'#fff7e9');ellipse(c,x-9*s,y-4*s,16*s,9*s,'#fff7e9');ellipse(c,x+13*s,y-5*s,13*s,10*s,'#fff7e9');c.restore()}
  function hills(c,t,night=false){path(c,[['moveTo',0,137],['bezierCurveTo',80,93,130,146,207,125],['bezierCurveTo',285,99,350,125,400,110],['lineTo',400,250],['lineTo',0,250]],night?'#769198':'#a1b8a4');path(c,[['moveTo',0,157],['bezierCurveTo',80,129,130,155,218,145],['bezierCurveTo',290,130,350,161,400,142],['lineTo',400,250],['lineTo',0,250]],night?'#628389':'#789e91')}
  function reeds(c,t,x,y,size=1){c.save();c.translate(x,y);c.scale(size,size);for(let i=0;i<5;i++){const lean=Math.sin(t*1.6+i*.7)*3;path(c,[['moveTo',i*4,0],['quadraticCurveTo',i*4-8+lean,-23-i*5,i*4-13+lean,-40-i*4]],null,i%2?'#5e7e65':'#758f6b',1.8)}c.restore()}
  function ripples(c,t,y,color,count=18){c.strokeStyle=color;c.lineWidth=.8;for(let i=0;i<count;i++){const yy=y+(i*23)%(250-y),x=(i*71+Math.sin(t*.9+i)*9)%410;path(c,[['moveTo',x,yy],['quadraticCurveTo',x+14,yy-1.8,x+28+(i%4)*5,yy]],null,color,.8)}}
  function birdScene(c,t,impulse){
    sky(c,'#b9d0d1','#ebead9');cloud(c,60+Math.sin(t*.05)*18,49,1.3,.36);cloud(c,286+Math.sin(t*.06)*22,70,1,.3);
    let glow=c.createRadialGradient(332,43,2,332,43,70);glow.addColorStop(0,'#f8edbbaa');glow.addColorStop(1,'#f8edbb00');c.fillStyle=glow;c.fillRect(240,0,160,130);ellipse(c,332,43,17,17,'#f4e9bc');hills(c,t);
    path(c,[['moveTo',0,179],['bezierCurveTo',90,162,144,179,241,175],['bezierCurveTo',300,166,357,175,400,165],['lineTo',400,250],['lineTo',0,250]],'#b3bf9d');
    path(c,[['moveTo',128,211],['bezierCurveTo',148,192,214,190,274,199],['bezierCurveTo',335,209,325,227,257,233],['bezierCurveTo',192,238,104,229,128,211]],'#85a9b0');
    path(c,[['moveTo',137,212],['bezierCurveTo',160,197,217,198,266,203]],null,'#d7e4d6',1.3);ripples(c,t,208,'#c8dbd0',7);
    const phase=t%8;
    // Crouch, leap, contact, recovery: the feet and ripple share a single event.
    const hop=phase>3.3&&phase<4.5?Math.sin(clamp((phase-3.3)/1.2)*Math.PI):0;
    const tap=impulse>0&&impulse<1.1?Math.sin(impulse/1.1*Math.PI):0;
    const jump=Math.max(hop,tap),pre=phase>2.95&&phase<3.3?Math.sin((phase-2.95)/.35*Math.PI):0;
    const land=phase>4.5&&phase<4.9?Math.sin((phase-4.5)/.4*Math.PI):0;
    const x=205+ease((phase-3.3)/1.2)*17-17*ease((phase-6.5)/1.4),y=196-jump*31;
    ellipse(c,x+2,215,22-jump*5,3-jump,'#597e7c44');
    if(phase>4.5&&phase<6.3){const r=(phase-4.5)/1.8;c.save();c.globalAlpha=1-r;for(let i=0;i<2;i++){c.beginPath();c.ellipse(x,215,9+r*35+i*9,2+r*7+i*2,0,0,TAU);c.strokeStyle='#dce9dc';c.lineWidth=1.2;c.stroke()}for(let i=0;i<5;i++){const dx=(i-2)*8*r,dy=Math.sin(r*Math.PI)*(i%2?13:19);ellipse(c,x+dx,212-dy,1.5,2.5,'#d8e8df')}c.restore()}
    reeds(c,t,40,226,.9);reeds(c,t,355,237,1.05);
    c.save();c.translate(x,y);c.scale(1+pre*.09+land*.1,1-pre*.15-land*.13);
    // Feet reach for the water just before landing.
    const kick=jump*5;
    path(c,[['moveTo',-5,11],['lineTo',-5-kick,18],['lineTo',-12-kick,19]],null,'#786251',2);
    path(c,[['moveTo',7,11],['lineTo',9+kick,18-jump*7],['lineTo',16+kick,18-jump*7]],null,'#786251',2);
    const tail=Math.sin(t*3.5)*.12+jump*.3;
    c.save();c.translate(-17,0);c.rotate(tail);path(c,[['moveTo',0,-5],['quadraticCurveTo',-14,-9,-24,-12],['quadraticCurveTo',-13,9,1,11]],'#8b7560');line(c,-21,-9,-5,3,'#b99e7c',1);c.restore();
    ellipse(c,0,0,23,19,'#a88c6d',-.08);ellipse(c,7,5,17,14,'#dfc7a0',-.2);
    // Wing opens in flight and settles with a tiny follow-through.
    c.save();c.translate(-6,-1);c.rotate(-.25-jump*.85+Math.sin(t*2)*.025);ellipse(c,-2,1,15,10,'#8d7961',.35);for(let i=0;i<3;i++)path(c,[['moveTo',-12+i*4,0],['quadraticCurveTo',-8+i*4,7,-1+i*3,6]],null,'#b19a7b',.8);c.restore();
    const look=phase<2?-.1:phase>6?.16:Math.sin(t)*.035;
    c.save();c.translate(13,-15);c.rotate(look);ellipse(c,0,0,14,14,'#b39a79');ellipse(c,5,3,10,10,'#e4d1b0');path(c,[['moveTo',-12,-7],['quadraticCurveTo',1,-19,12,-7],['quadraticCurveTo',-1,-10,-12,-7]],'#796d5b');
    const blink=Math.sin(t*1.7)> .989||phase>2.6&&phase<2.72;
    if(blink)path(c,[['moveTo',5,-2],['quadraticCurveTo',8,0,10,-2]],null,'#35423c',1.4);else{ellipse(c,8,-2,2.4,2.9,'#303d39');ellipse(c,8.6,-3, .65,.8,'#fff8e8')}
    path(c,[['moveTo',12,0],['lineTo',23,3],['lineTo',13,5]],'#a9773e');c.restore();c.restore();
    ellipse(c,83,235,10,4,'#97a48d');ellipse(c,340,220,8,3,'#9ba991');
    // A small insect provides a reason for the bird to look and hop.
    const fx=255+Math.sin(t*.8)*30,fy=155+Math.sin(t*1.7)*9;ellipse(c,fx,fy,2.5,1.6,'#647b72');ellipse(c,fx-2,fy-2.5,3,1.6,'#edf1d8aa',Math.sin(t*35));ellipse(c,fx+2,fy-2.5,3,1.6,'#edf1d8aa',-Math.sin(t*35));
  }
  function boatScene(c,t,impulse){
    sky(c,'#b8d0ce','#f3e7c5');const sunX=312,sunY=57;let glow=c.createRadialGradient(sunX,sunY,4,sunX,sunY,80);glow.addColorStop(0,'#f8e6afaa');glow.addColorStop(1,'#f8e6af00');c.fillStyle=glow;c.fillRect(200,0,200,150);ellipse(c,sunX,sunY,23,23,'#f4dfaa');cloud(c,70+Math.sin(t*.04)*18,50,1.4,.4);cloud(c,215+Math.sin(t*.03)*10,32,.7,.25);hills(c,t);
    // Little house across the lake, with irregular chimney smoke.
    c.fillStyle='#d7c8ad';c.fillRect(70,134,19,15);path(c,[['moveTo',65,134],['lineTo',78,123],['lineTo',94,134]],'#748478');c.fillStyle='#83948c';c.fillRect(76,138,5,8);c.fillStyle='#a1957e';c.fillRect(83,123,3,7);
    for(let i=0;i<4;i++){let a=(t*.2+i*.25)%1;c.save();c.globalAlpha=(1-a)*.3;ellipse(c,85+Math.sin(a*4+t*.4)*4,120-a*27,3+a*5,2+a*3,'#f4eee0');c.restore()}
    let water=c.createLinearGradient(0,155,0,250);water.addColorStop(0,'#8db8ba');water.addColorStop(1,'#4c8d9e');c.fillStyle=water;c.fillRect(0,156,400,94);ripples(c,t,162,'#c7ded3',24);
    for(let i=0;i<7;i++){let x=310+Math.sin(t+i)*6;line(c,x-i*4,162+i*7,x+10+i*3,162+i*7,'#efe0b988',2)}
    const gust=Math.pow(Math.max(0,Math.sin(t*.65)),3)+(impulse<2&&impulse>0?Math.sin(impulse/2*Math.PI):0);
    const bx=202+Math.sin(t*.19)*32+gust*5,by=201+Math.sin(t*1.7)*2;
    // Wake stretches behind the hull; separate ripples expand and disappear.
    for(let i=0;i<4;i++){let a=(t*.25+i*.25)%1;c.save();c.globalAlpha=(1-a)*.5;path(c,[['moveTo',bx-26-a*36,by+8+a*6],['quadraticCurveTo',bx-5,by+14+a*4,bx+30+a*12,by+8+a*6]],null,'#d0e3da',1);c.restore()}
    ellipse(c,bx,by+12,39,3,'#356f7b33');c.save();c.translate(bx,by);c.rotate(Math.sin(t*1.3)*.025+gust*.045);
    // Folded-paper geometry, creases and a sail that catches a gust.
    path(c,[['moveTo',-46,-6],['lineTo',46,-6],['lineTo',26,15],['lineTo',-26,15],['closePath']],'#eee9d9');path(c,[['moveTo',-46,-6],['lineTo',1,3],['lineTo',-26,15],['closePath']],'#fdf7e5');path(c,[['moveTo',46,-6],['lineTo',1,3],['lineTo',26,15],['closePath']],'#c8cebf');
    path(c,[['moveTo',-24,-6],['lineTo',0,-48],['quadraticCurveTo',2+gust*10,-25,0,3],['closePath']],'#fff9e9');path(c,[['moveTo',0,-48],['quadraticCurveTo',14+gust*12,-24,27,-6],['lineTo',0,3],['closePath']],'#dce1ce');line(c,0,-48,0,3,'#b2bdb0',.8);path(c,[['moveTo',-46,-6],['lineTo',1,3],['lineTo',46,-6]],null,'#b8c2b4',.75);c.restore();
    reeds(c,t,7,254,1.2);reeds(c,t,361,260,1.15);path(c,[['moveTo',0,249],['quadraticCurveTo',17,225,43,247]],'#698c72');
    // A dragonfly changes its path and briefly hovers near the sail.
    let dx=260+Math.sin(t*.9)*45,dy=145+Math.sin(t*1.8)*13;line(c,dx-5,dy,dx+5,dy,'#4f7776',1.4);ellipse(c,dx-1,dy-3,6,2,'#e0e9dbbb',Math.sin(t*45)*.5);ellipse(c,dx+1,dy+3,6,2,'#e0e9dbbb',-Math.sin(t*45)*.5);
    if(gust>.15){c.save();c.globalAlpha=gust*.35;path(c,[['moveTo',40,103],['quadraticCurveTo',78,98,110,103]],null,'#fcf4dc',1);path(c,[['moveTo',53,114],['quadraticCurveTo',95,119,126,112]],null,'#fcf4dc',1);c.restore()}
  }
  function windowScene(c,t,impulse){
    sky(c,'#d8d1c2','#e4d6bf');c.fillStyle='#8095a3';c.fillRect(34,17,332,178);
    c.save();c.beginPath();c.rect(34,17,332,178);c.clip();cloud(c,80+Math.sin(t*.03)*15,50,2,.14);cloud(c,300,65,2,.12);
    path(c,[['moveTo',34,135],['bezierCurveTo',120,89,150,143,221,123],['bezierCurveTo',295,94,325,127,366,104],['lineTo',366,195],['lineTo',34,195]],'#738b8e');
    for(let i=0;i<8;i++){let x=43+i*43,y=160-(i%3)*10;c.fillStyle=i%2?'#697f83':'#7a8d8c';c.fillRect(x,y,29,40);path(c,[['moveTo',x-3,y],['lineTo',x+14,y-13],['lineTo',x+32,y]],'#64797b');if(i%3===0){c.fillStyle='#e0c89a88';c.fillRect(x+11,y+9,5,7)}}
    // Long diagonal rain beyond the glass.
    for(let i=0;i<40;i++){let y=(i*39+t*65)%210,x=(i*59-t*9)%390;line(c,x,y,x-4,y+10,'#c4d6d533',.7)}
    // Beads cling, grow and then accelerate down the window at different times.
    for(let i=0;i<19;i++){let period=5+(i%5),a=(t+i*.83)%period/period,fall=a<.6?0:Math.pow((a-.6)/.4,2)*130,x=47+(i*67)%305,y=23+(i*37)%126+fall;let size=1+a*1.1;if(y>193)continue;ellipse(c,x,y,size,size*1.6,'#d0e0da77');path(c,[['moveTo',x-.5,y-1],['lineTo',x-.6,y-3-fall*.15]],null,'#e3ede766',.65)}
    c.restore();
    // Solid window frame catches the warmer indoor light.
    c.fillStyle='#c1b59e';c.fillRect(26,10,348,9);c.fillRect(26,10,9,187);c.fillRect(366,10,9,187);c.fillStyle='#ece4d3';c.fillRect(34,15,5,180);c.fillRect(196,17,8,178);c.fillRect(34,103,332,7);c.fillStyle='#b4a78e';c.fillRect(198,17,2,178);
    c.fillStyle='#c8ad87';c.fillRect(17,195,366,14);c.fillStyle='#e4c9a0';c.fillRect(17,195,366,4);c.fillStyle='#dfcdb0';c.fillRect(17,209,366,41);for(let i=0;i<5;i++)line(c,25,219+i*6,375,217+i*6,'#bba78322',.7);
    // A sleepy cat reacts to a tap with an ear flick and an opening eye.
    const breath=Math.sin(t*1.8)*.7;ellipse(c,107,215,42,5,'#aa907044');
    c.save();c.translate(105,200);c.rotate(Math.sin(t*.5)*.012);ellipse(c,0,0,37,18+breath,'#af9678');
    path(c,[['moveTo',-22,7],['bezierCurveTo',-55,10,-51,-13,-28,-11]],null,'#947b60',11);path(c,[['moveTo',-23,7],['bezierCurveTo',-48,9,-49,-8,-33,-11+Math.sin(t*1.1)*2]],null,'#bdab8f',5);
    ellipse(c,23,2,18,16,'#c4ad8a');const ear=Math.sin(t*.9)>.95?3:0;path(c,[['moveTo',11,-9],['lineTo',12-ear,-23],['lineTo',23,-13]],'#b89d7c');path(c,[['moveTo',27,-12],['lineTo',37,-22],['lineTo',39,-5]],'#b89d7c');path(c,[['moveTo',15,-13],['lineTo',15,-19],['lineTo',20,-13]],'#cfb0a0');
    if(impulse>0&&impulse<2){ellipse(c,20,1,2,3,'#52645b');ellipse(c,32,1,2,3,'#52645b')}else{path(c,[['moveTo',16,1],['quadraticCurveTo',20,4,24,1]],null,'#665a4a',1.3);path(c,[['moveTo',28,1],['quadraticCurveTo',32,4,35,1]],null,'#665a4a',1.3)}
    path(c,[['moveTo',25,6],['lineTo',29,6],['lineTo',27,9]],'#947567');line(c,27,9,27,11,'#806e5b',.7);line(c,14,7,4,5,'#e4d6bd',.65);line(c,15,10,3,11,'#e4d6bd',.65);line(c,36,8,46,7,'#e4d6bd',.65);ellipse(c,19,16,12,4,'#d3bd99');c.restore();
    // Mug: shaded ceramic, coffee surface, tiny highlight and drifting steam.
    ellipse(c,254,222,43,6,'#b49a7633');ellipse(c,255,218,39,6,'#f0e3c9');path(c,[['moveTo',231,178],['lineTo',279,178],['lineTo',277,206],['quadraticCurveTo',274,219,253,219],['quadraticCurveTo',233,216,232,205],['closePath']],'#f9f0dc');path(c,[['moveTo',278,183],['bezierCurveTo',306,173,310,204,278,204]],null,'#ece1c8',7);path(c,[['moveTo',233,182],['quadraticCurveTo',234,210,240,212]],null,'#fffaf0',3);ellipse(c,255,178,24,5,'#d8c7a5');ellipse(c,255,178,21,3.8,'#87694e');ellipse(c,249,177,12,1.2,'#b7996a');
    for(let i=0;i<3;i++){let a=(t*.23+i*.33)%1;c.save();c.globalAlpha=Math.sin(a*Math.PI)*.42;let y=171-a*42,x=243+i*11+Math.sin(a*5+t*.5)*3;path(c,[['moveTo',x,y],['bezierCurveTo',x-8,y-8,x+7,y-15,x+1,y-24]],null,'#fff9eb',2.3-a);c.restore()}
    // A fern shifts gently with indoor air, in the near foreground.
    c.fillStyle='#8d8f6d';path(c,[['moveTo',329,209],['lineTo',348,209],['lineTo',345,228],['lineTo',332,228],['closePath']],'#b29a75');for(let i=0;i<5;i++){const lean=Math.sin(t*.8+i)*1.5;path(c,[['moveTo',339,210],['quadraticCurveTo',327+i*6+lean,185-i*3,318+i*10+lean,175-i*2]],null,'#66846b',1.5);ellipse(c,321+i*9+lean,181-i*2,4,10,'#7d9476',-.8+i*.3)}
  }
  const renderers=[boatScene,birdScene,windowScene];
  window.WeatherOriginalScenes={renderers};
  const articles=[...document.querySelectorAll('.tile.original:not(.cozy)')];
  articles.forEach((tile,i)=>{
    const stage=tile.querySelector('.stage');stage.innerHTML='';const canvas=document.createElement('canvas');canvas.style.cssText='width:100%;height:100%;display:block;cursor:pointer';canvas.width=800;canvas.height=500;canvas.tabIndex=0;canvas.setAttribute('role','button');canvas.setAttribute('aria-label',['Paper boat. Tap to send a gust.','Puddle sparrow. Tap to make it hop.','Rainy window. Tap to wake the cat.'][i]);stage.append(canvas);
    const ctx=canvas.getContext('2d');let time=0,last=0,trigger=-100;
    const action=()=>{trigger=time};canvas.addEventListener('pointerdown',action);canvas.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();action()}});
    tile.querySelector('p').textContent=['A gust fills the paper sail; a dragonfly hovers over the wake. Tap for a little more wind.','A sparrow spots an insect, crouches, hops and splashes. Tap to get its attention.','Droplets gather on the glass. The tea steams; the cat breathes and flicks an ear. Tap to wake it.'][i];
    function draw(stamp){const dt=last?Math.min((stamp-last)/1000,.04):0;last=stamp;const still=tile.classList.contains('offscreen')||tile.classList.contains('paused')||document.hidden; if(!still||time===0){if(!still)time+=dt;ctx.setTransform(2,0,0,2,0,0);renderers[i](ctx,time,time-trigger)}requestAnimationFrame(draw)}requestAnimationFrame(draw);
  });
})();
