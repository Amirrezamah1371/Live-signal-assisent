package com.example.livesignalassistant

import android.graphics.Bitmap
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class SignalResult(
    val direction:String, val expirySeconds:Int, val confidence:Int,
    val entryQuality:Int=0, val conflict:Int=100, val reason:String="",
    val upScore:Int=0, val downScore:Int=0, val signalQuality:Int=0,
    val diagnostics:Map<String,Any?> = emptyMap(),
    val side:Int=0, val traceQuality:Double=1.0
)

/** 72.0 direction brain. Trace extraction moved to TraceExtractor; cross-frame motion comes from ChangeEngine (auto-scale safe). */
object SignalAnalyzer {
    private data class View(val p:List<Double>, val floor:Double)
    private data class C(val sign:Int,val expiry:Int,val score:Double,val entry:Double,val conflict:Double,val reason:String, val diagnostics:Map<String,Any?> = emptyMap())
    private val votes=mutableMapOf<Int,ArrayDeque<Int>>()
    private val directionVotes=ArrayDeque<Int>()
    private var lastSignalAt=0L; private var lastSignalSign=0; private var lastSignalExpiry=0
    private var lastSignalScore=0.0; private var lastSignalEntry=0.0
    private var lastSetupKey=""

    @Synchronized fun reset(){votes.clear();directionVotes.clear();lastSignalAt=0;lastSignalSign=0;lastSignalExpiry=0;lastSignalScore=0.0;lastSignalEntry=0.0;lastSetupKey=""}

    @Synchronized fun analyze(b:Bitmap,tr:TraceResult?,velocityPx:Double,accelPx:Double):SignalResult =
        analyze(b.width, b.height, tr, velocityPx, accelPx)

    /** Width and height are the captured frame. Replay uses this directly so the same gates run without a bitmap. */
    @Synchronized fun analyze(w:Int,h:Int,tr:TraceResult?,velocityPx:Double,accelPx:Double):SignalResult{
        if(w<240||h<360||tr==null) return wait("LOW_VISIBILITY")
        if(!tr.tipConnected) return wait("TIP_AMBIGUOUS")
        if(tr.tipGapFrac>0.25) return wait("NO_CURRENT_TIP")
        val tq=tr.quality
        // Crop at the last real column. The fixed-width trace pads past the tip, and that flat
        // padding used to become the micro window (its slope and chop were exactly zero).
        val tip=tr.real.indexOfLast{it}
        if(tip<71) return wait("NO_CURRENT_TIP")
        val raw=ArrayList<Double>(tip+1); for(i in 0..tip) raw+=-tr.ys[i]
        val coverage=tr.realFrac
        if(raw.size<72 || coverage<.30) return wait("LOW_VISIBILITY")
        val all=smooth(raw,2);val n=all.size
        val wide=mk(all,h);val structure=mk(all.subList((n*.28).toInt(),n),h);val entry=mk(all.subList((n*.62).toInt(),n),h);val micro=mk(all.subList((n*.82).toInt(),n),h)
        val right=median(micro.p.takeLast(max(3,micro.p.size/7)));val now=SystemClock.elapsedRealtime()
        val velocity=velocityPx;val accel=accelPx

        // Only the 60-second endpoint exists. UP and DOWN are evaluated symmetrically.
        val upC=candidate(1,60,wide,structure,entry,micro,velocity,accel)
        val dnC=candidate(-1,60,wide,structure,entry,micro,velocity,accel)
        val delta=upC.score-dnC.score
        val best=if(delta>=0)upC else dnC; val sign=best.sign; val margin=abs(delta)
        val up=upC.score.toInt(); val down=dnC.score.toInt()
        val diag=mapOf<String,Any?>(
            "mode" to "1M_ONLY","expiry_ms" to 60000,"vision_coverage" to coverage,"trace_points" to raw.size,
            "sampled_columns" to raw.size,"one_minute_delta" to delta,"velocity" to velocity,"acceleration" to accel,
            "trace_q" to tq,"trace_real_frac" to tr.realFrac,"trace_ambiguity" to tr.ambiguity,"trace_max_jump" to tr.maxJumpFrac,
            "trace_bar" to tr.barFound,"trace_roi_bottom" to tr.roiBottom,"trace_tip_gap" to tr.tipGapFrac,
            "trace_tip_connected" to tr.tipConnected
        ) + best.diagnostics

        // Do not chase a mature impulse. Sudden motion is evidence to re-evaluate, not a command to enter.
        val exhaustion=(best.diagnostics["exhaustion"] as? Double)?:0.0
        val continuation=(best.diagnostics["continuation"] as? Double)?:0.0
        val volExpansion=(best.diagnostics["vol_expansion"] as? Double)?:1.0
        val structural=best.reason=="TURN" || best.reason=="REV" || best.reason=="BRK" || best.reason=="FAIL_BRK"
        // A structural label can still be direction evidence, but it does not waive entry safety.
        // High exhaustion or an unresolved shock becomes a refusal even when the reason is BRK/TURN/REV.
        if(best.reason=="EXH" || (exhaustion>=.62 && continuation>=.34))
            return entryRefused("LATE_ENTRY_RISK", sign, best, up, down, diag, tq)
        if(volExpansion>=2.20 && best.conflict>35)
            return entryRefused("SHOCK_UNRESOLVED", sign, best, up, down, diag, tq)

        // Balanced gates: selective without turning the robot into permanent WAIT.
        // These are model scores, never advertised as win probabilities.
        if(best.score<64.0 || best.entry<47.0 || best.conflict>57.0 || margin<7.0)
            return SignalResult("WAIT",60,best.score.toInt(),best.entry.toInt(),best.conflict.toInt(),"FILTER",up,down,0,diag,side=sign,traceQuality=tq)

        directionVotes.add(sign);while(directionVotes.size>5)directionVotes.removeFirst()
        val persistence=directionVotes.count{it==sign}
        if(directionVotes.size>=3 && persistence<2 && !structural)
            return SignalResult("WAIT",60,best.score.toInt(),best.entry.toInt(),best.conflict.toInt(),"DIRECTION_UNSTABLE",up,down,0,diag,side=sign,traceQuality=tq)

        val q=votes.getOrPut(60){ArrayDeque()};q.add(sign);while(q.size>4)q.removeFirst()
        val same=q.count{it==sign}
        if(best.score<76.0 && q.size>=3 && same<2 && !structural)
            return SignalResult("WAIT",60,best.score.toInt(),best.entry.toInt(),best.conflict.toInt(),"CONFIRM",up,down,0,diag,side=sign,traceQuality=tq)

        if(lastSignalSign!=0 && sign==-lastSignalSign){
            val reversalStrength=(best.score-lastSignalScore)+(best.entry-lastSignalEntry)*.40+margin*.60
            if(!structural && reversalStrength<11.0)
                return SignalResult("WAIT",60,best.score.toInt(),best.entry.toInt(),best.conflict.toInt(),"ANTI_FLIP",up,down,0,diag,side=sign,traceQuality=tq)
        }

        val hist=structure.p.dropLast(max(2,structure.p.size/12));val lo=percentile(hist,.10);val hi=percentile(hist,.90);val span=(hi-lo).coerceAtLeast(structure.floor*4)
        val pos=((right-lo)/span).coerceIn(-.5,1.5);val posBucket=(pos*12).toInt()
        val velBucket=when{velocity>micro.floor*.30->1;velocity< -micro.floor*.30->-1;else->0}
        val setupKey="$sign|${best.reason}|$posBucket|$velBucket|${(best.entry/8).toInt()}"
        if(setupKey==lastSetupKey && best.score<82.0)
            return SignalResult("WAIT",60,best.score.toInt(),best.entry.toInt(),best.conflict.toInt(),"SAME_SETUP",up,down,0,diag,side=sign,traceQuality=tq)

        lastSetupKey=setupKey;lastSignalAt=now;lastSignalSign=sign;lastSignalExpiry=60;lastSignalScore=best.score;lastSignalEntry=best.entry
        val measured=if(sign>0)"UP" else "DOWN"
        return SignalResult(measured,60,best.score.toInt(),best.entry.toInt(),best.conflict.toInt(),best.reason,up,down,best.score.toInt(),diag+mapOf("margin" to margin,"measured_direction" to measured,"entry_permission" to "OPEN"),side=sign,traceQuality=tq)
    }

    /** Direction stays on the result. Entry permission is what refuses the trade. */
    private fun entryRefused(reason:String, sign:Int, best:C, up:Int, down:Int, diag:Map<String,Any?>, tq:Double):SignalResult{
        val measured=if(sign>0)"UP" else "DOWN"
        return SignalResult("WAIT",60,best.score.toInt(),best.entry.toInt(),best.conflict.toInt(),reason,up,down,0,diag+mapOf("measured_direction" to measured,"entry_permission" to "REFUSED","measured_state" to best.reason),side=sign,traceQuality=tq)
    }

    private fun mk(p:List<Double>,h:Int):View{val d=p.zipWithNext{a,c->abs(c-a)};return View(p,max(h*.0017,median(d).coerceAtLeast(1.0)*.90))}
    private fun candidate(sign:Int,e:Int,w:View,s:View,en:View,m:View,velocity:Double,accel:Double):C{
        fun mv(v:View,a:Double,b:Double):Double{val n=v.p.size;val i=(n*a).toInt().coerceIn(0,n-2);val j=(n*b).toInt().coerceIn(i+2,n);val q=max(2,(j-i)/4);return median(v.p.subList(j-q,j))-median(v.p.subList(i,i+q))}
        fun align(v:Double,f:Double,k:Double)=((sign*v)/(f*k)).coerceIn(-1.0,1.0)
        val wa=align(mv(w,.05,.95),w.floor,3.4);val sa=align(mv(s,.20,.98),s.floor,2.6);val ea=align(mv(en,.35,1.0),en.floor,1.7);val ma=align(mv(m,.42,1.0),m.floor,1.0)
        val va=align(velocity,m.floor,1.15);val aa=align(accel,m.floor,2.2)
        val hist=s.p.dropLast(max(2,s.p.size/12));val sup=percentile(hist,.10);val res=percentile(hist,.90);val r=median(m.p.takeLast(max(3,m.p.size/6)));val span=(res-sup).coerceAtLeast(s.floor*4);val pos=((r-sup)/span).coerceIn(-.5,1.5)
        // Symmetric location evidence: UP gets support-side reversal evidence; DOWN gets resistance-side reversal evidence.
        val level=if(sign>0)((.50-pos)/.50).coerceIn(0.0,1.0) else ((pos-.50)/.50).coerceIn(0.0,1.0)
        val breakout=if(sign>0)((r-res)/(s.floor*1.4)).coerceIn(0.0,1.0) else ((sup-r)/(s.floor*1.4)).coerceIn(0.0,1.0)
        val ds=m.p.zipWithNext{a,c->c-a};var flips=0;var last=0;for(d in ds){val z=if(d>m.floor*.1)1 else if(d< -m.floor*.1)-1 else 0;if(z!=0){if(last!=0&&z!=last)flips++;last=z}}
        val chop=flips.toDouble()/max(1,ds.size-1)
        // Horizon-specific evidence: short expiries require fresh micro confirmation; long expiries
        // require structural agreement. This prevents one fixed direction engine from being reused blindly.
        val t=((e-60)/240.0).coerceIn(0.0,1.0)
        val weights=doubleArrayOf(
            .10+.18*t, .18+.20*t, .27-.02*t, .27-.15*t, .12-.07*t, .06-.04*t
        )
        val aligned=listOf(wa,sa,ea,ma,va,aa);var directional=0.0;for(i in aligned.indices)directional+=max(0.0,aligned[i])*weights[i]
        val opposite=max(0.0,-ma)*.31+max(0.0,-ea)*.29+max(0.0,-sa)*.25+max(0.0,-wa)*.10+max(0.0,-va)*.05
        val reversal=(level*max(0.0,-sa)*(max(0.0,ma)+max(0.0,ea))/2).coerceIn(0.0,1.0)
        val continuation=(max(0.0,wa)*max(0.0,sa)*max(0.0,ea)).coerceIn(0.0,1.0)
        val timing=(.50*max(0.0,ma)+.28*max(0.0,ea)+.15*max(0.0,va)+.07*max(0.0,aa)).coerceIn(0.0,1.0)
        // A mature continuation should not chase an already stretched move whose micro momentum is fading.
        val edgeDistance=if(sign>0) ((r-res)/(s.floor*2.2)).coerceIn(0.0,1.4) else ((sup-r)/(s.floor*2.2)).coerceIn(0.0,1.4)
        val fading=(max(0.0,sa)*max(0.0,-ma) + max(0.0,ea)*max(0.0,-va)).coerceIn(0.0,1.0)
        val exhaustion=(edgeDistance*.55 + fading*.70 + max(0.0,-aa)*.18).coerceIn(0.0,1.0)
        val entryQ=(40+34*timing+18*max(0.0,ea)+10*level+8*breakout-21*chop-10*max(0.0,-va)-14*exhaustion).coerceIn(0.0,100.0)
        val conflict=(100*(opposite*.68+chop*.25+exhaustion*.07)).coerceIn(0.0,100.0)
        val horizonContext=(wa*(.18+.30*t)+sa*(.25+.20*t)+ea*(.37-.18*t)+ma*(.20-.12*t))
        val horizonBonus=(horizonContext*10).coerceIn(-10.0,10.0)
        val lateEntryPenalty=exhaustion*(3.2-1.5*t)

        // Match expiry to the observed move speed and regime instead of forcing a timeframe.
        val speed=(abs(ma)*.45+abs(va)*.35+abs(aa)*.20).coerceIn(0.0,1.0)
        val shortNeed=(speed*.55 + (1.0-chop)*.20 + max(0.0,ma)*.25).coerceIn(0.0,1.0)
        val longNeed=(abs(wa)*.40+abs(sa)*.40+max(0.0,ea)*.20).coerceIn(0.0,1.0)
        val horizonFit=((1.0-t)*shortNeed + t*longNeed)
        val horizonPenalty=abs(t-(longNeed/(shortNeed+longNeed+.001)))*9.0

        // 5m multi-scale turn logic. The analyzer already has four nested chart histories:
        // wide ~= zoomed-out context, structure ~= prior swing context, entry ~= recent leg, micro ~= current turn.
        // We do NOT blindly invert at an extreme: a lower extreme needs an actual lift; an upper extreme needs an actual roll-over.
        val wideHist=w.p.dropLast(max(3,w.p.size/12))
        val wideSup=percentile(wideHist,.10); val wideRes=percentile(wideHist,.90)
        val wideSpan=(wideRes-wideSup).coerceAtLeast(w.floor*5)
        val widePos=((r-wideSup)/wideSpan).coerceIn(-.5,1.5)
        val localLower=pos<=.30; val localUpper=pos>=.70
        val wideLower=widePos<=.34; val wideUpper=widePos>=.66
        val lowerZone=localLower && wideLower
        val upperZone=localUpper && wideUpper
        val lifting=(ma>.10 || (ea>.08 && va>-.10)) && aa>-.45
        val rolling=(ma<-.10 || (ea<-.08 && va<.10)) && aa<.45
        val priorDown=sa<-.06 || wa<-.05
        val priorUp=sa>.06 || wa>.05
        val lowerTurn=lowerZone && lifting && priorDown
        val upperTurn=upperZone && rolling && priorUp
        val zoneAdjustment=when {
            lowerTurn && sign<0 -> -58.0
            lowerTurn && sign>0 -> 25.0
            upperTurn && sign>0 -> -58.0
            upperTurn && sign<0 -> 25.0
            lowerZone && sign<0 && lifting -> -34.0
            upperZone && sign>0 && rolling -> -34.0
            else -> 0.0
        }

        // STRUCTURE FUSION: classify regime before trusting a pattern.
        val trendStrength=(abs(wa)*.42+abs(sa)*.38+abs(ea)*.20).coerceIn(0.0,1.0)
        val trendAgreement=if(wa*sa>0 && sa*ea>0) 1.0 else if(wa*sa>0 || sa*ea>0) .45 else 0.0
        val rangeRegime=(1.0-(trendStrength*.70+trendAgreement*.30)).coerceIn(0.0,1.0)
        val trendRegime=(trendStrength*.65+trendAgreement*.35).coerceIn(0.0,1.0)

        // Pivot geometry: higher-low/higher-high or lower-high/lower-low evidence.
        val piv=pivots(s.p,s.floor*.55)
        val lows=piv.filter{it.second<0}.takeLast(2).map{it.third}
        val highs=piv.filter{it.second>0}.takeLast(2).map{it.third}
        val higherLow=lows.size==2 && lows[1]>lows[0]+s.floor*.20
        val lowerHigh=highs.size==2 && highs[1]<highs[0]-s.floor*.20
        val higherHigh=highs.size==2 && highs[1]>highs[0]+s.floor*.20
        val lowerLow=lows.size==2 && lows[1]<lows[0]-s.floor*.20
        val swingBias=when(sign){
            1 -> (if(higherLow) .55 else 0.0)+(if(higherHigh) .25 else 0.0)-(if(lowerLow) .45 else 0.0)
            else -> (if(lowerHigh) .55 else 0.0)+(if(lowerLow) .25 else 0.0)-(if(higherHigh) .45 else 0.0)
        }

        // Expansion/contraction: breakouts need expansion; reversals benefit from rejection after exhaustion.
        val recentMoves=m.p.zipWithNext{a,c->abs(c-a)}
        val structMoves=s.p.zipWithNext{a,c->abs(c-a)}
        val recentVol=median(recentMoves.takeLast(max(3,recentMoves.size/2))).coerceAtLeast(.01)
        val baseVol=median(structMoves).coerceAtLeast(.01)
        val volExpansion=(recentVol/baseVol).coerceIn(.35,2.5)
        val expansionEvidence=((volExpansion-1.0)/1.0).coerceIn(0.0,1.0)
        val compressionEvidence=((1.0-volExpansion)/.65).coerceIn(0.0,1.0)

        // Failed-break / rejection: price probes beyond a structural edge then returns inside.
        val recentMax=m.p.maxOrNull()?:r; val recentMin=m.p.minOrNull()?:r
        val failedUp=(recentMax>res+s.floor*.18 && r<res-s.floor*.05)
        val failedDown=(recentMin<sup-s.floor*.18 && r>sup+s.floor*.05)
        val rejection=when { sign<0 && failedUp -> 1.0; sign>0 && failedDown -> 1.0; else -> 0.0 }

        // Shape memory: compare the newest normalized path with the preceding path and its mirror.
        val shape=shapeSimilarity(s.p)
        val mirrorTurn=if(sign>0) shape.second else shape.first

        // Context-specific evidence: trend continuation and range reversal are different setups.
        val trendContext=trendRegime*max(0.0,wa)*max(0.0,sa)*(.55+.45*expansionEvidence)
        val rangeContext=rangeRegime*level*max(0.0,ma)*(.60+.40*compressionEvidence)
        val structuralBonus=12*trendContext + 13*rangeContext + 9*swingBias + 8*rejection + 4*mirrorTurn

        // Strong counter-context penalty prevents a tiny micro wiggle from overruling the larger chart.
        val macroAgainst=max(0.0,-wa)*.48+max(0.0,-sa)*.38+max(0.0,-ea)*.14
        val contextPenalty=20*macroAgainst*(.65+.35*trendRegime)

        // REGIME-FIRST GATE: the zoomed-out structure gets veto power.
        // In a strong trend, a tiny counter-trend micro move is not enough to reverse direction.
        // At a genuine structural edge, a confirmed turn / failed break can override the trend.
        val macroAligned=max(0.0,wa)*.52+max(0.0,sa)*.34+max(0.0,ea)*.14
        val microAligned=max(0.0,ma)*.55+max(0.0,va)*.30+max(0.0,aa)*.15
        val confirmedEdgeTurn=(lowerTurn && sign>0) || (upperTurn && sign<0) || rejection>.5
        val counterTrendAttempt=trendRegime>.58 && macroAgainst>.34 && !confirmedEdgeTurn
        val trendContinuation=trendRegime>.52 && macroAligned>.34 && microAligned>.08
        val rangeEdgeTurn=rangeRegime>.45 && level>.45 && microAligned>.10
        val regimeAdjustment=when {
            counterTrendAttempt -> -32.0
            confirmedEdgeTurn -> 17.0
            trendContinuation -> 9.0
            rangeEdgeTurn -> 8.0
            else -> 0.0
        }

        val score=(38+directional*49+reversal*8+continuation*7+breakout*5+timing*6+entryQ*.09-conflict*.17-exhaustion*7+horizonBonus-lateEntryPenalty+zoneAdjustment+structuralBonus-contextPenalty+regimeAdjustment+horizonFit*7-horizonPenalty).coerceIn(0.0,100.0)
        val reason=when{rejection>.5->"FAIL_BRK";lowerTurn||upperTurn->"TURN";breakout>.45&&expansionEvidence>.18->"BRK";reversal>.45->"REV";exhaustion>.58->"EXH";trendContext>.32->"TREND";rangeContext>.28->"RANGE";else->"MOM"}
        return C(sign,e,score,entryQ,conflict,reason,mapOf(
            "wa" to wa,"sa" to sa,"ea" to ea,"ma" to ma,"va" to va,"aa" to aa,
            "support" to sup,"resistance" to res,"range_position" to pos,"wide_position" to widePos,
            "chop" to chop,"reversal" to reversal,"continuation" to continuation,"breakout" to breakout,
            "exhaustion" to exhaustion,"trend_regime" to trendRegime,"range_regime" to rangeRegime,
            "swing_bias" to swingBias,"vol_expansion" to volExpansion,"rejection" to rejection,
            "horizon_fit" to horizonFit,"entry_quality" to entryQ,"conflict_value" to conflict
        ))
    }
    private fun pivots(p:List<Double>,threshold:Double):List<Triple<Int,Int,Double>>{
        if(p.size<5)return emptyList();val out=ArrayList<Triple<Int,Int,Double>>()
        for(i in 2 until p.size-2){val v=p[i];val left=p.subList(i-2,i);val right=p.subList(i+1,i+3)
            if(v>=left.maxOrNull()!!&&v>=right.maxOrNull()!!&&(v-min(left.minOrNull()!!,right.minOrNull()!!))>=threshold)out+=Triple(i,1,v)
            else if(v<=left.minOrNull()!!&&v<=right.minOrNull()!!&&(max(left.maxOrNull()!!,right.maxOrNull()!!)-v)>=threshold)out+=Triple(i,-1,v)}
        return out
    }
    private fun shapeSimilarity(p:List<Double>):Pair<Double,Double>{
        if(p.size<24)return 0.0 to 0.0;val k=min(18,p.size/3);val a=p.subList(p.size-2*k,p.size-k);val b=p.takeLast(k)
        fun norm(x:List<Double>):List<Double>{val lo=x.minOrNull()!!;val hi=x.maxOrNull()!!;val sp=(hi-lo).coerceAtLeast(.001);return x.map{(it-lo)/sp}}
        val x=norm(a);val y=norm(b);var same=0.0;var mirror=0.0;for(i in 0 until k){same+=abs(x[i]-y[i]);mirror+=abs((1.0-x[i])-y[i])}
        return (1.0-same/k).coerceIn(0.0,1.0) to (1.0-mirror/k).coerceIn(0.0,1.0)
    }
    private fun smooth(v:List<Double>,r:Int)=v.indices.map{i->median(v.subList(max(0,i-r),min(v.size,i+r+1)))}
    private fun median(v:List<Double>):Double{if(v.isEmpty())return 0.0;val s=v.sorted();return if(s.size%2==1)s[s.size/2] else (s[s.size/2-1]+s[s.size/2])/2}
    private fun percentile(v:List<Double>,q:Double):Double{val s=v.sorted();return s[((s.size-1)*q).toInt().coerceIn(0,s.size-1)]}
    private fun wait(r:String)=SignalResult("WAIT",0,0,0,100,r,0,0,0,traceQuality=0.0)
}
