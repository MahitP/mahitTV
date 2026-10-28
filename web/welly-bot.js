/*
 * WellyBot — ES5 port of jeremy-prt/bloub at
 * b4bb3c1b5f93c7b87a2e8d620f667c4093d97749 (MIT).
 * Pure sampler: callers own the clock and the reusable Frame.
 */
(function (window) {
  'use strict';
  var N = 64, TAU = Math.PI * 2;
  var STATES = ['idle','thinking','wink','wide','alert','notify','exclaim','sleep','egg','hexagon','play','orbit','burst','comet','swirl'];
  var EXPRESSIONS = ['attentif','content','heureux','malicieux','surpris','curieux','sceptique','endormi','fache','triste','fier','concentre','clin','love','blase','panique'];
  /* yaw,pitch,roll,split, then w,h,tilt,open for each eye. Measured upstream faces, exposed as Welly aliases. */
  var EXPR = [
    [4,5,-4,16,.21,.44,0,1,.21,.44,0,1],
    [5,9,0,17,.27,.17,14,1,.27,.17,-14,1],
    [4,14,0,18,.34,.13,20,1,.34,.13,-20,1],
    [12,6,-6,16,.21,.4,0,1,.22,.15,0,1],
    [3,-3,0,19,.45,.47,0,1,.45,.47,0,1],
    [16,-9,-15,16.5,.24,.46,-8,1,.2,.38,-8,1],
    [-14,3,8,16.5,.2,.44,-18,1,.28,.17,14,1],
    [6,-9,-3,16,.2,.42,0,.42,.2,.42,0,.42],
    [3,7,0,17,.34,.15,30,1,.34,.15,-30,1],
    [3,-13,0,16,.22,.4,-28,1,.22,.4,28,1],
    [5,17,0,17,.3,.15,18,1,.3,.15,-18,1],
    [28.49,28.62,-13,15.46,.186,.412,0,1,.186,.412,0,1],
    [-5.37,4.55,6.7,16.25,.236,.464,0,1,.447,.089,0,1],
    [6,-14,0,19.5,.4,.56,-10,1,.4,.56,10,1],
    [-22,2,0,16,.3,.12,0,1,.3,.12,0,1],
    [2,-20,0,20.5,.4,.6,0,1,.4,.6,0,1]
  ];
  var POSES = {idle:1,thinking:1.1,wink:.8,wide:.8,alert:.75,notify:.9,exclaim:.8,sleep:.45,egg:.8,hexagon:.8,play:.9,orbit:1.2,swirl:.5,burst:.45,comet:1.15};
  var MORPH = {idle:.45,thinking:.4,wink:.3,wide:.55,alert:.45,notify:.5,exclaim:.45,sleep:.5,egg:.4,hexagon:.4,play:.5,orbit:.6,burst:.4,comet:.45,swirl:.3};
  var BASE = {idle:1,wink:1,wide:1,notify:1,swirl:1};
  var egg = [0.8369,0.8424,0.8497,0.8585,0.8674,0.8775,0.8878,0.8983,0.9089,0.9185,0.9288,0.9374,0.9445,0.9504,0.9543,0.9559,0.9555,0.9519,0.9466,0.9389,0.9302,0.9193,0.9085,0.8969,0.8852,0.8734,0.8625,0.8513,0.8411,0.8325,0.8243,0.8179,0.8137,0.8112,0.8102,0.8128,0.8178,0.8262,0.8374,0.8518,0.8702,0.8922,0.9169,0.9446,0.9741,1.0023,1.0267,1.0433,1.0481,1.0393,1.0216,0.9970,0.9697,0.9418,0.9169,0.8949,0.8760,0.8604,0.8490,0.8394,0.8337,0.8314,0.8305,0.8326];
  var hexagon = [0.9210,0.9282,0.9441,0.9706,0.9984,1.0059,0.9896,0.9562,0.9290,0.9124,0.9047,0.9058,0.9157,0.9349,0.9642,0.9873,0.9882,0.9665,0.9336,0.9105,0.8968,0.8918,0.8955,0.9080,0.9293,0.9611,0.9820,0.9812,0.9590,0.9282,0.9089,0.8978,0.8964,0.9026,0.9189,0.9439,0.9778,0.9990,0.9964,0.9713,0.9439,0.9274,0.9196,0.9206,0.9308,0.9502,0.9799,1.0121,1.0226,1.0071,0.9752,0.9510,0.9366,0.9316,0.9351,0.9485,0.9711,1.0026,1.0213,1.0155,0.9863,0.9547,0.9347,0.9232];
  var triangle = [0.7819,0.8211,0.8747,0.9440,1.0223,1.0960,1.1401,1.1340,1.0808,1.0047,0.9265,0.8603,0.8104,0.7730,0.7450,0.7273,0.7151,0.7118,0.7148,0.7245,0.7427,0.7680,0.8037,0.8518,0.9148,0.9876,1.0583,1.1073,1.1109,1.0667,0.9940,0.9164,0.8482,0.7948,0.7555,0.7261,0.7056,0.6925,0.6859,0.6869,0.6938,0.7084,0.7305,0.7615,0.8040,0.8595,0.9311,1.0092,1.0791,1.1171,1.1054,1.0501,0.9779,0.9050,0.8450,0.7990,0.7656,0.7413,0.7258,0.7160,0.7146,0.7204,0.7330,0.7528];
  var galet = new Array(N), peak = 0, i;
  for (i = 0; i < N; i++) { var a = i / N * TAU; galet[i] = 1 + .075 * Math.cos(2*a+.5) + .035 * Math.cos(3*a+2.1); if (galet[i] > peak) peak = galet[i]; }
  for (i = 0; i < N; i++) galet[i] *= 1.02 / peak;
  function clamp(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
  function lerp(a,b,t) { return a + (b-a)*t; }
  function quint(t) { t=clamp(t); return 1-Math.pow(1-t,5); }
  function inout(t){t=clamp(t);return t<.5?4*t*t*t:1-Math.pow(-2*t+2,3)/2;}
  function finite(x) { return typeof x === 'number' && isFinite(x); }
  function Frame() {
    this.radii = new Float64Array(N); this.fromRadii = new Float64Array(N); this.count=N;
    this.centerX=0; this.centerY=0; this.rotation=0; this.bodyScaleX=1;this.bodyScaleY=1;this.bodyAlpha=1; this.eyeAlpha=1;
    this.gazeYaw=4; this.gazePitch=5; this.gazeRoll=-4; this.eyeSplit=16;
    this.eyeW0=.21; this.eyeH0=.44;this.eyeTilt0=0;this.eyeOpen0=1;this.eyeW1=.21; this.eyeH1=.44;this.eyeTilt1=0;this.eyeOpen1=1;this.lid=1;this.expression='attentif';
    this.notifX=0; this.notifY=0; this.notifR=.15; this.notifScale=0;
    this.dotCount=0; this.arcCount=0; this.dotsBehind=false; this.state='idle';
  }
  function copyFrame(a,b) { var j; for(j=0;j<N;j++) b.radii[j]=a.radii[j]; b.centerX=a.centerX;b.centerY=a.centerY;b.rotation=a.rotation;b.bodyScaleX=a.bodyScaleX;b.bodyScaleY=a.bodyScaleY;b.bodyAlpha=a.bodyAlpha;b.eyeAlpha=a.eyeAlpha;b.gazeYaw=a.gazeYaw;b.gazePitch=a.gazePitch;b.gazeRoll=a.gazeRoll;b.eyeSplit=a.eyeSplit;b.eyeW0=a.eyeW0;b.eyeH0=a.eyeH0;b.eyeTilt0=a.eyeTilt0;b.eyeOpen0=a.eyeOpen0;b.eyeW1=a.eyeW1;b.eyeH1=a.eyeH1;b.eyeTilt1=a.eyeTilt1;b.eyeOpen1=a.eyeOpen1;b.lid=a.lid;b.notifScale=a.notifScale;b.expression=a.expression; }
  function imul(a,b){var ah=(a>>>16)&65535,al=a&65535,bh=(b>>>16)&65535,bl=b&65535;return (al*bl+((ah*bl+al*bh)<<16))|0;}
  function rng(seed) { return function(){var t;seed=(seed+0x6d2b79f5)>>>0;t=imul(seed^(seed>>>15),1|seed);t=(t+imul(t^(t>>>7),61|t))^t;return ((t^(t>>>14))>>>0)/4294967296;}; }
  var blinkTimes=[], random=rng(0x5eed), bt=1.4;
  while(bt<900){ blinkTimes.push(bt); bt+=1.9+random()*2.7; if(random()<.18){blinkTimes.push(bt);bt+=.24;} }
  function blink(t) { var j,k; for(j=0;j<blinkTimes.length;j++){ if(t<blinkTimes[j]) break; k=(t-blinkTimes[j])/.18; if(k>=0&&k<=1) return k<.45?1-k/.45:(k-.45)/.55; } return 1; }
  function loopNoise(t,p,phase){var q=t/p*TAU;return .55*Math.sin(q+phase)+.3*Math.sin(2*q+phase*1.7+1.1)+.15*Math.sin(3*q+phase*2.3+2.4);}
  function Engine() {
    this.state='idle'; this.since=0; this.previous='idle'; this.frozen=new Frame(); this.scratch=new Frame(); this.hasFrozen=false;
    this.lookYaw=0; this.lookPitch=0; this.lookMix=0; this.lookSpin=0; this.lookWander=1; this.lookAt=0;
    this.lookFromYaw=0; this.lookFromPitch=0; this.lookFromMix=0; this.reduced=false;
    this.expr=0;this.exprAt=0;this.exprFrom=new Float64Array(12);this.exprScratch=new Float64Array(12);for(var i=0;i<12;i++)this.exprFrom[i]=EXPR[0][i];
  }
  Engine.prototype.setReducedMotion=function(v){this.reduced=!!v;};
  Engine.prototype.expressionAt=function(now,out){var k=quint((now-this.exprAt)/.45),to=EXPR[this.expr],i;for(i=0;i<12;i++)out[i]=lerp(this.exprFrom[i],to[i],k);return out;};
  Engine.prototype.setExpression=function(name,now){var next=EXPRESSIONS.indexOf(name),i;if(next<0||next===this.expr)return false;this.expressionAt(now,this.exprScratch);for(i=0;i<12;i++)this.exprFrom[i]=this.exprScratch[i];this.expr=next;this.exprAt=now;return true;};
  Engine.prototype.setLook=function(yaw,pitch,mix,spin,now){ if(!finite(yaw+pitch+mix+spin+now))return false;mix=clamp(mix);if(yaw===this.lookYaw&&pitch===this.lookPitch&&mix===this.lookMix&&spin===this.lookSpin)return true;this.lookFromYaw=this.lookYaw;this.lookFromPitch=this.lookPitch;this.lookFromMix=this.lookMix;this.lookYaw=yaw;this.lookPitch=pitch;this.lookMix=mix;this.lookSpin=spin;this.lookAt=now;return true;};
  Engine.prototype.setState=function(state,now){ if(STATES.indexOf(state)<0||state===this.state)return false; this.sample(now,this.scratch); copyFrame(this.scratch,this.frozen); this.hasFrozen=true;this.previous=this.state;this.state=state;this.since=now;return true;};
  Engine.prototype.pose=function(state,t,f){
    var j,phase,pulse;f.state=state;f.centerX=0;f.centerY=0;f.rotation=0;f.bodyScaleX=1;f.bodyScaleY=1;f.bodyAlpha=1;f.eyeAlpha=1;f.dotCount=0;f.arcCount=0;f.dotsBehind=false;f.notifScale=0;
    if(state==='egg'){f.bodyScaleX=.82;f.bodyScaleY=1.08;}
    else if(state==='hexagon'){pulse=1+.045*Math.sin(t*TAU*1.7);f.bodyScaleX=pulse;f.bodyScaleY=2-pulse;f.rotation=this.reduced?0:.08*Math.sin(t*TAU);f.arcCount=this.reduced?0:6;}
    else if(state==='play'){f.bodyScaleX=1+.05*Math.sin(t*TAU*1.2);f.bodyScaleY=1-.035*Math.sin(t*TAU*1.2);f.rotation=this.reduced?0:.12*Math.sin(t*TAU*.8);f.arcCount=this.reduced?0:4;}
    else if(state==='thinking'){pulse=.94+.04*Math.sin(t*TAU/1.5);f.bodyScaleX=pulse;f.bodyScaleY=2-pulse;f.dotCount=this.reduced?0:2;}
    else if(state==='alert'||state==='exclaim'){f.bodyScaleX=.76;f.bodyScaleY=1.12;f.centerX=state==='alert'?(inout(t/1.5)*.18-.09):0;f.rotation=state==='alert'?.22:0;f.dotCount=this.reduced?0:1;}
    else if(state==='sleep'){f.bodyScaleX=.82;f.bodyScaleY=.78;f.centerY=this.reduced?0:Math.sin(t*(TAU/.6))*.08;}
    else if(state==='wide'){f.bodyScaleX=1.04;f.bodyScaleY=1.04;}
    else if(state==='wink'){f.bodyScaleX=1.02;f.bodyScaleY=.98;}
    else if(state==='notify'){phase=clamp(t/.45);f.notifScale=phase<1?1+.14*Math.sin(phase*Math.PI)*(1-phase*.35):1;f.notifX=Math.cos(-42*Math.PI/180)*1.003;f.notifY=Math.sin(-42*Math.PI/180)*1.003;}
    else if(state==='orbit'){var ramp=inout(t/.35);f.rotation=this.reduced?0:-TAU*1.25*t*ramp;f.centerX=this.reduced?0:-.09*Math.sin(f.rotation);f.centerY=this.reduced?0:.09*Math.cos(f.rotation);f.arcCount=this.reduced?0:6;}
    else if(state==='swirl'){f.arcCount=this.reduced?0:3;f.rotation=this.reduced?0:.16*Math.sin(t*2.5);}
    else if(state==='burst'){var collapse=1-.18*quint(t/.35),regrow=quint((t-.42)/.42);pulse=collapse+(1-collapse)*regrow;f.bodyScaleX=pulse;f.bodyScaleY=2-pulse;f.dotCount=this.reduced?0:12;f.dotsBehind=true;}
    else if(state==='comet'){pulse=.72+.28*quint(t/.55);f.bodyScaleX=pulse;f.bodyScaleY=1.08-.08*quint(t/.55);f.centerY=this.reduced?0:Math.sin(clamp(t/1.7)*Math.PI)*.035;f.arcCount=this.reduced?0:4;f.rotation=this.reduced?0:.1*Math.sin(t*2.1);}
    for(j=0;j<N;j++)f.radii[j]=galet[j];
  };
  Engine.prototype.sample=function(now,out){
    var local=now-this.since,j,k,dur=MORPH[this.state]||.35,target=this.scratch;
    this.pose(this.state,local,target);
    k=this.reduced?1:quint(local/dur);
    if(this.hasFrozen&&k<1){for(j=0;j<N;j++)out.radii[j]=galet[j];out.centerX=lerp(this.frozen.centerX,target.centerX,k);out.centerY=lerp(this.frozen.centerY,target.centerY,k);out.rotation=lerp(this.frozen.rotation,target.rotation,k);out.bodyScaleX=lerp(this.frozen.bodyScaleX,target.bodyScaleX,k);out.bodyScaleY=lerp(this.frozen.bodyScaleY,target.bodyScaleY,k);out.bodyAlpha=lerp(this.frozen.bodyAlpha,target.bodyAlpha,k);out.eyeAlpha=1;}
    else copyFrame(target,out);
    out.state=this.state;out.dotCount=target.dotCount;out.arcCount=target.arcCount;out.dotsBehind=target.dotsBehind;out.notifX=target.notifX;out.notifY=target.notifY;out.notifR=target.notifR;out.notifScale=target.notifScale;
    var lk=quint((now-this.lookAt)/.24),mix=lerp(this.lookFromMix,this.lookMix,lk),wander=1-mix;
    this.expressionAt(now,this.exprScratch);var e=this.exprScratch;out.expression=EXPRESSIONS[this.expr];out.gazeYaw=lerp(e[0],this.lookYaw+this.lookSpin*(1-lk),mix)+loopNoise(now,11.3,.4)*5.5*wander;out.gazePitch=lerp(e[1],this.lookPitch,mix)+loopNoise(now,9.1,1.3)*4.2*wander;out.gazeRoll=e[2]+loopNoise(now,13.7,3.2)*2.2*wander;out.eyeSplit=e[3];out.eyeW0=e[4];out.eyeH0=e[5];out.eyeTilt0=e[6];out.eyeOpen0=e[7];out.eyeW1=e[8];out.eyeH1=e[9];out.eyeTilt1=e[10];out.eyeOpen1=e[11];
    out.lid=blink(now);if(local>=0&&local<=.2&&this.state!=='idle')out.lid=Math.min(out.lid,local<.09?1-local/.09:(local-.09)/.11);
    if(this.reduced){out.centerX=0;out.centerY=0;out.rotation=0;out.bodyScaleX=out.bodyScaleY=1;out.dotCount=0;out.arcCount=0;out.bodyAlpha=1;out.eyeAlpha=1;}
    return out;
  };
  window.WellyBot={Engine:Engine,Frame:Frame,PROFILE_SAMPLES:N,COLOR:'#2fbfa0',NOTIF_BLUE:'#2496e8',NOTIF_POP:1.14,BLINK_DURATION:.18,FORCED_BLINK_DURATION:.2,SHAPE:'galet',EXPRESSION:'attentif',STATES:STATES,EXPRESSIONS:EXPRESSIONS,POSES:POSES,profiles:{galet:galet,egg:egg,hexagon:hexagon,triangle:triangle}};
}(window));
