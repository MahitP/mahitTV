package io.freemytv;

/**
 * Allocation-free Android port of jeremy-prt/bloub at
 * b4bb3c1b5f93c7b87a2e8d620f667c4093d97749 (MIT).
 * The sampler owns no clock; time is always supplied by the caller.
 */
final class WellyBot {
    static final int PROFILE_SAMPLES = 64;
    static final int COLOR = 0xff2fbfa0;
    static final int NOTIF_BLUE = 0xff2496e8;
    static final double NOTIF_POP = 1.14;
    static final double BLINK_DURATION = .18;
    static final double FORCED_BLINK_DURATION = .2;
    enum State { IDLE, THINKING, WINK, WIDE, ALERT, NOTIFY, EXCLAIM, SLEEP, EGG, HEXAGON, PLAY, ORBIT, BURST, COMET, SWIRL }
    enum Expression { ATTENTIF, CONTENT, HEUREUX, MALICIEUX, SURPRIS, CURIEUX, SCEPTIQUE, ENDORMI, FACHE, TRISTE, FIER, CONCENTRE, CLIN, LOVE, BLASE, PANIQUE }

    static final class Frame {
        final double[] radii = new double[PROFILE_SAMPLES];
        int count = PROFILE_SAMPLES, dotCount, arcCount;
        double centerX, centerY, rotation, bodyScaleX=1, bodyScaleY=1, bodyAlpha=1, eyeAlpha=1;
        double gazeYaw=4, gazePitch=5, gazeRoll=-4, eyeSplit=16;
        double eyeW0=.21, eyeH0=.44, eyeTilt0, eyeOpen0=1, eyeW1=.21, eyeH1=.44, eyeTilt1, eyeOpen1=1, lid=1;
        double notifX, notifY, notifR=.15, notifScale;
        boolean dotsBehind;
        State state = State.IDLE;
        Expression expression = Expression.ATTENTIF;
    }

    private static final double TAU = Math.PI * 2;
    private static final double[] GALET = new double[64];
    private static final double[] EGG = {0.8369,0.8424,0.8497,0.8585,0.8674,0.8775,0.8878,0.8983,0.9089,0.9185,0.9288,0.9374,0.9445,0.9504,0.9543,0.9559,0.9555,0.9519,0.9466,0.9389,0.9302,0.9193,0.9085,0.8969,0.8852,0.8734,0.8625,0.8513,0.8411,0.8325,0.8243,0.8179,0.8137,0.8112,0.8102,0.8128,0.8178,0.8262,0.8374,0.8518,0.8702,0.8922,0.9169,0.9446,0.9741,1.0023,1.0267,1.0433,1.0481,1.0393,1.0216,0.9970,0.9697,0.9418,0.9169,0.8949,0.8760,0.8604,0.8490,0.8394,0.8337,0.8314,0.8305,0.8326};
    private static final double[] HEXAGON = {0.9210,0.9282,0.9441,0.9706,0.9984,1.0059,0.9896,0.9562,0.9290,0.9124,0.9047,0.9058,0.9157,0.9349,0.9642,0.9873,0.9882,0.9665,0.9336,0.9105,0.8968,0.8918,0.8955,0.9080,0.9293,0.9611,0.9820,0.9812,0.9590,0.9282,0.9089,0.8978,0.8964,0.9026,0.9189,0.9439,0.9778,0.9990,0.9964,0.9713,0.9439,0.9274,0.9196,0.9206,0.9308,0.9502,0.9799,1.0121,1.0226,1.0071,0.9752,0.9510,0.9366,0.9316,0.9351,0.9485,0.9711,1.0026,1.0213,1.0155,0.9863,0.9547,0.9347,0.9232};
    private static final double[] TRIANGLE = {0.7819,0.8211,0.8747,0.9440,1.0223,1.0960,1.1401,1.1340,1.0808,1.0047,0.9265,0.8603,0.8104,0.7730,0.7450,0.7273,0.7151,0.7118,0.7148,0.7245,0.7427,0.7680,0.8037,0.8518,0.9148,0.9876,1.0583,1.1073,1.1109,1.0667,0.9940,0.9164,0.8482,0.7948,0.7555,0.7261,0.7056,0.6925,0.6859,0.6869,0.6938,0.7084,0.7305,0.7615,0.8040,0.8595,0.9311,1.0092,1.0791,1.1171,1.1054,1.0501,0.9779,0.9050,0.8450,0.7990,0.7656,0.7413,0.7258,0.7160,0.7146,0.7204,0.7330,0.7528};
    private static final double[] MORPH = {.45,.4,.3,.55,.45,.5,.45,.5,.4,.4,.5,.6,.4,.45,.3};
    private static final double[][] EXPRESSIONS = {
            {4,5,-4,16,.21,.44,0,1,.21,.44,0,1}, {5,9,0,17,.27,.17,14,1,.27,.17,-14,1},
            {4,14,0,18,.34,.13,20,1,.34,.13,-20,1}, {12,6,-6,16,.21,.4,0,1,.22,.15,0,1},
            {3,-3,0,19,.45,.47,0,1,.45,.47,0,1}, {16,-9,-15,16.5,.24,.46,-8,1,.2,.38,-8,1},
            {-14,3,8,16.5,.2,.44,-18,1,.28,.17,14,1}, {6,-9,-3,16,.2,.42,0,.42,.2,.42,0,.42},
            {3,7,0,17,.34,.15,30,1,.34,.15,-30,1}, {3,-13,0,16,.22,.4,-28,1,.22,.4,28,1},
            {5,17,0,17,.3,.15,18,1,.3,.15,-18,1}, {28.49,28.62,-13,15.46,.186,.412,0,1,.186,.412,0,1},
            {-5.37,4.55,6.7,16.25,.236,.464,0,1,.447,.089,0,1}, {6,-14,0,19.5,.4,.56,-10,1,.4,.56,10,1},
            {-22,2,0,16,.3,.12,0,1,.3,.12,0,1}, {2,-20,0,20.5,.4,.6,0,1,.4,.6,0,1}
    };
    private static final double[] BLINKS = new double[320];
    private static int blinkCount;
    static {
        double peak=0;
        for(int i=0;i<64;i++){double a=i/64.0*TAU;GALET[i]=1+.075*Math.cos(2*a+.5)+.035*Math.cos(3*a+2.1);peak=Math.max(peak,GALET[i]);}
        for(int i=0;i<64;i++)GALET[i]*=1.02/peak;
        int seed=0x5eed; double t=1.4;
        while(t<900&&blinkCount<BLINKS.length){BLINKS[blinkCount++]=t;seed+=0x6d2b79f5;int z=(seed^(seed>>>15))*(1|seed);z=(z+(z^(z>>>7))*(61|z))^z;double one=Integer.toUnsignedLong(z^(z>>>14))/4294967296.0;t+=1.9+one*2.7;seed+=0x6d2b79f5;z=(seed^(seed>>>15))*(1|seed);z=(z+(z^(z>>>7))*(61|z))^z;one=Integer.toUnsignedLong(z^(z>>>14))/4294967296.0;if(one<.18&&blinkCount<BLINKS.length){BLINKS[blinkCount++]=t;t+=.24;}}
    }

    private State state=State.IDLE, previous=State.IDLE;
    private double since;
    private final Frame frozen=new Frame(), scratch=new Frame();
    private boolean hasFrozen, reduced;
    private double lookYaw,lookPitch,lookMix,lookSpin,lookAt,lookFromYaw,lookFromPitch,lookFromMix;
    private Expression expression=Expression.ATTENTIF;
    private double expressionAt;
    private final double[] expressionFrom=EXPRESSIONS[0].clone(), expressionScratch=new double[12];

    void setReducedMotion(boolean value){reduced=value;}
    boolean setExpression(Expression next,double now){
        if(next==null||next==expression)return false;
        expressionAt(now,expressionScratch);System.arraycopy(expressionScratch,0,expressionFrom,0,12);expression=next;expressionAt=now;return true;
    }
    private void expressionAt(double now,double[] out){double k=quint((now-expressionAt)/.45);double[] to=EXPRESSIONS[expression.ordinal()];for(int i=0;i<12;i++)out[i]=lerp(expressionFrom[i],to[i],k);}
    boolean setLook(double yaw,double pitch,double mix,double spin,double now){
        if(!Double.isFinite(yaw+pitch+mix+spin+now))return false;
        mix=clamp(mix);if(yaw==lookYaw&&pitch==lookPitch&&mix==lookMix&&spin==lookSpin)return true;
        lookFromYaw=lookYaw;lookFromPitch=lookPitch;lookFromMix=lookMix;lookYaw=yaw;lookPitch=pitch;lookMix=mix;lookSpin=spin;lookAt=now;return true;
    }
    boolean setState(State next,double now){
        if(next==null||next==state)return false;
        sample(now,scratch);copy(scratch,frozen);hasFrozen=true;previous=state;state=next;since=now;return true;
    }
    State state(){return state;}
    static double galet(int sample){return GALET[sample & 63];}

    Frame sample(double now,Frame out){
        double local=now-since;pose(state,local,scratch);double k=reduced?1:quint(local/MORPH[state.ordinal()]);
        if(hasFrozen&&k<1){System.arraycopy(GALET,0,out.radii,0,64);out.centerX=lerp(frozen.centerX,scratch.centerX,k);out.centerY=lerp(frozen.centerY,scratch.centerY,k);out.rotation=lerp(frozen.rotation,scratch.rotation,k);out.bodyScaleX=lerp(frozen.bodyScaleX,scratch.bodyScaleX,k);out.bodyScaleY=lerp(frozen.bodyScaleY,scratch.bodyScaleY,k);out.bodyAlpha=lerp(frozen.bodyAlpha,scratch.bodyAlpha,k);out.eyeAlpha=1;}else copy(scratch,out);
        out.state=state;out.dotCount=scratch.dotCount;out.arcCount=scratch.arcCount;out.dotsBehind=scratch.dotsBehind;out.notifX=scratch.notifX;out.notifY=scratch.notifY;out.notifR=scratch.notifR;out.notifScale=scratch.notifScale;
        double lk=quint((now-lookAt)/.24),mix=lerp(lookFromMix,lookMix,lk),wander=1-mix;
        expressionAt(now,expressionScratch);double[] e=expressionScratch;out.expression=expression;
        out.gazeYaw=lerp(e[0],lookYaw+lookSpin*(1-lk),mix)+loopNoise(now,11.3,.4)*5.5*wander;
        out.gazePitch=lerp(e[1],lookPitch,mix)+loopNoise(now,9.1,1.3)*4.2*wander;
        out.gazeRoll=e[2]+loopNoise(now,13.7,3.2)*2.2*wander;out.eyeSplit=e[3];out.eyeW0=e[4];out.eyeH0=e[5];out.eyeTilt0=e[6];out.eyeOpen0=e[7];out.eyeW1=e[8];out.eyeH1=e[9];out.eyeTilt1=e[10];out.eyeOpen1=e[11];
        out.lid=blink(now);if(local>=0&&local<=.2&&state!=State.IDLE)out.lid=Math.min(out.lid,local<.09?1-local/.09:(local-.09)/.11);
        if(reduced){out.centerX=out.centerY=out.rotation=0;out.bodyScaleX=out.bodyScaleY=1;out.dotCount=out.arcCount=0;out.bodyAlpha=out.eyeAlpha=1;}
        return out;
    }
    private void pose(State s,double t,Frame f){
        double pulse;f.state=s;f.centerX=f.centerY=f.rotation=0;f.bodyScaleX=f.bodyScaleY=1;f.bodyAlpha=f.eyeAlpha=1;f.dotCount=f.arcCount=0;f.dotsBehind=false;f.notifScale=0;
        switch(s){
            case EGG:f.bodyScaleX=.82;f.bodyScaleY=1.08;break;
            case HEXAGON:pulse=1+.045*Math.sin(t*TAU*1.7);f.bodyScaleX=pulse;f.bodyScaleY=2-pulse;f.rotation=reduced?0:.08*Math.sin(t*TAU);f.arcCount=reduced?0:6;break;
            case PLAY:f.bodyScaleX=1+.05*Math.sin(t*TAU*1.2);f.bodyScaleY=1-.035*Math.sin(t*TAU*1.2);f.rotation=reduced?0:.12*Math.sin(t*TAU*.8);f.arcCount=reduced?0:4;break;
            case THINKING:pulse=.94+.04*Math.sin(t*TAU/1.5);f.bodyScaleX=pulse;f.bodyScaleY=2-pulse;f.dotCount=reduced?0:2;break;
            case ALERT:case EXCLAIM:f.bodyScaleX=.76;f.bodyScaleY=1.12;f.centerX=s==State.ALERT?inout(t/1.5)*.18-.09:0;f.rotation=s==State.ALERT?.22:0;f.dotCount=reduced?0:1;break;
            case SLEEP:f.bodyScaleX=.82;f.bodyScaleY=.78;f.centerY=reduced?0:Math.sin(t*(TAU/.6))*.08;break;
            case WIDE:f.bodyScaleX=f.bodyScaleY=1.04;break;
            case WINK:f.bodyScaleX=1.02;f.bodyScaleY=.98;break;
            case NOTIFY:double phase=clamp(t/.45);f.notifScale=phase<1?1+.14*Math.sin(phase*Math.PI)*(1-phase*.35):1;f.notifX=Math.cos(Math.toRadians(-42))*1.003;f.notifY=Math.sin(Math.toRadians(-42))*1.003;break;
            case ORBIT:double ramp=inout(t/.35);f.rotation=reduced?0:-TAU*1.25*t*ramp;f.centerX=reduced?0:-.09*Math.sin(f.rotation);f.centerY=reduced?0:.09*Math.cos(f.rotation);f.arcCount=reduced?0:6;break;
            case SWIRL:f.arcCount=reduced?0:3;f.rotation=reduced?0:.16*Math.sin(t*2.5);break;
            case BURST:double collapse=1-.18*quint(t/.35),regrow=quint((t-.42)/.42);pulse=collapse+(1-collapse)*regrow;f.bodyScaleX=pulse;f.bodyScaleY=2-pulse;f.dotCount=reduced?0:12;f.dotsBehind=true;break;
            case COMET:pulse=.72+.28*quint(t/.55);f.bodyScaleX=pulse;f.bodyScaleY=1.08-.08*quint(t/.55);f.centerY=reduced?0:Math.sin(clamp(t/1.7)*Math.PI)*.035;f.arcCount=reduced?0:4;f.rotation=reduced?0:.1*Math.sin(t*2.1);break;
            default:break;
        }
        System.arraycopy(GALET,0,f.radii,0,64);
    }
    private static void copy(Frame a,Frame b){System.arraycopy(a.radii,0,b.radii,0,64);b.centerX=a.centerX;b.centerY=a.centerY;b.rotation=a.rotation;b.bodyScaleX=a.bodyScaleX;b.bodyScaleY=a.bodyScaleY;b.bodyAlpha=a.bodyAlpha;b.eyeAlpha=a.eyeAlpha;b.gazeYaw=a.gazeYaw;b.gazePitch=a.gazePitch;b.gazeRoll=a.gazeRoll;b.eyeSplit=a.eyeSplit;b.eyeW0=a.eyeW0;b.eyeH0=a.eyeH0;b.eyeTilt0=a.eyeTilt0;b.eyeOpen0=a.eyeOpen0;b.eyeW1=a.eyeW1;b.eyeH1=a.eyeH1;b.eyeTilt1=a.eyeTilt1;b.eyeOpen1=a.eyeOpen1;b.lid=a.lid;b.notifScale=a.notifScale;b.expression=a.expression;}
    private static double blink(double t){for(int i=0;i<blinkCount;i++){if(t<BLINKS[i])break;double k=(t-BLINKS[i])/.18;if(k>=0&&k<=1)return k<.45?1-k/.45:(k-.45)/.55;}return 1;}
    private static double loopNoise(double t,double p,double phase){double q=t/p*TAU;return .55*Math.sin(q+phase)+.3*Math.sin(2*q+phase*1.7+1.1)+.15*Math.sin(3*q+phase*2.3+2.4);}
    private static double clamp(double x){return x<0?0:x>1?1:x;}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double quint(double t){t=clamp(t);return 1-Math.pow(1-t,5);}
    private static double inout(double t){t=clamp(t);return t<.5?4*t*t*t:1-Math.pow(-2*t+2,3)/2;}
}
