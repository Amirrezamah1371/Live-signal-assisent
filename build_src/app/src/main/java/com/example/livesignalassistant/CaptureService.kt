package com.example.livesignalassistant

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import kotlin.math.*

class CaptureService:Service(){
 companion object{const val EXTRA_RESULT_CODE="result_code";const val EXTRA_RESULT_DATA="result_data";const val ACTION_EXPORT="lsa.export";private const val CHANNEL="lsa_v70";private const val NID=1700}
 private var projection:MediaProjection?=null;private var reader:ImageReader?=null;private var display:VirtualDisplay?=null
 private var wm:WindowManager?=null;private var brain:TextView?=null;private val bubbles=mutableListOf<View>();private val main=Handler(Looper.getMainLooper());private val worker=Executors.newSingleThreadExecutor();private val evidenceWorker=Executors.newSingleThreadExecutor();private val busy=AtomicBoolean(false)
 private lateinit var memory:MemoryStore;private lateinit var experience:ExperienceStore;private var lastFrameAt=0L;private var lastEvidenceAt=0L;private var cycleStart=SystemClock.elapsedRealtime();private var nextDecisionAt=cycleStart+90000L;private var nextObservationAt=cycleStart+1000L;private val observations=mutableListOf<Obs>();private var cycle=1;private val change=ChangeEngine();private var lastObsAt=0L
 override fun onCreate(){super.onCreate();memory=MemoryStore(this);experience=ExperienceStore(this);channel();startForeground(NID,NotificationCompat.Builder(this,CHANNEL).setContentTitle("72.0.1 · Diag Logging").setContentText("80s deep observation · 10s final decision · 1m expiry").setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build());memory.event("SESSION_START")}
 override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{
  if(i?.action==ACTION_EXPORT){val p=memory.exportZip();Toast.makeText(this,"Memory exported: $p",Toast.LENGTH_LONG).show();return START_NOT_STICKY}
  if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY};showBrain();val code=i?.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED;val data:Intent?=if(Build.VERSION.SDK_INT>=33)i?.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java) else @Suppress("DEPRECATION") i?.getParcelableExtra(EXTRA_RESULT_DATA)
  if(code!=Activity.RESULT_OK||data==null){stopSelf();return START_NOT_STICKY};if(projection!=null)return START_NOT_STICKY;val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager;projection=mgr.getMediaProjection(code,data);projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},main);startCapture();return START_NOT_STICKY
 }
 private fun glass(stroke:Int=0x55FFFFFF)=GradientDrawable().apply{setColor(0xB8FFFFFF.toInt());cornerRadius=42f;setStroke(2,stroke)}
 private fun showBrain(){main.post{if(brain!=null)return@post;wm=getSystemService(WINDOW_SERVICE) as WindowManager;val v=TextView(this).apply{text="72.0.1 · DIAG\ncycle 1";textSize=13f;setTextColor(Color.BLACK);gravity=Gravity.CENTER;setPadding(18,12,18,12);background=glass();elevation=16f};val lp=params(Gravity.TOP or Gravity.END,18,190);wm!!.addView(v,lp);brain=v}}
 private fun params(g:Int,x:Int,y:Int)=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=g;this.x=x;this.y=y}
 private fun startCapture(){val m=resources.displayMetrics;reader=ImageReader.newInstance(m.widthPixels,m.heightPixels,PixelFormat.RGBA_8888,2);display=projection?.createVirtualDisplay("LSA70",m.widthPixels,m.heightPixels,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,main);reader?.setOnImageAvailableListener({r->val now=SystemClock.elapsedRealtime();val im=r.acquireLatestImage()?:return@setOnImageAvailableListener;if(now-lastFrameAt<250||busy.get()){im.close();return@setOnImageAvailableListener};lastFrameAt=now;busy.set(true);try{val p=im.planes[0];val pad=p.rowStride-p.pixelStride*im.width;val tmp=Bitmap.createBitmap(im.width+pad/p.pixelStride,im.height,Bitmap.Config.ARGB_8888);tmp.copyPixelsFromBuffer(p.buffer);val b=Bitmap.createBitmap(tmp,0,0,im.width,im.height);tmp.recycle();worker.execute{try{processFrame(b,now)}finally{b.recycle();busy.set(false)}}}catch(_:Throwable){busy.set(false)}finally{im.close()}},main)}
 private fun recordFrameAsync(b:Bitmap,tag:String){
  val copy=b.copy(Bitmap.Config.ARGB_8888,false)
  evidenceWorker.execute{try{memory.frame(copy,tag)}catch(t:Throwable){memory.event("RECORDER_ERROR",mapOf("message" to (t.message?:t.javaClass.simpleName)))}finally{copy.recycle()}}
 }
 private fun processFrame(b:Bitmap,now:Long){
  // Black-box screenshots are sampled every 3s; analysis still runs at its normal cadence.
  // This cuts session storage sharply without weakening the live analyzer.
  if(now-lastEvidenceAt>=3000L){recordFrameAsync(b,"observe");lastEvidenceAt=now}
  val elapsed=now-cycleStart
  // 1M path clock: one analyzed observation each second. Capture itself may run faster;
  // decision timing is monotonic and independent from frame-arrival jitter.
  if(now>=nextObservationAt && now<nextDecisionAt){
   val tr=TraceExtractor.extract(b)
   val tSec=now/1000.0
   var regInfo:Map<String,Any?> = emptyMap();var vel=0.0;var acc=0.0;var kinValid=false
   if(tr!=null){
    val path=DoubleArray(tr.ys.size){-tr.ys[it]}
    regInfo=change.update(tSec,path)
    val k=change.kinematicsPx(tSec)
    if(k!=null){vel=k.first;acc=k.second;kinValid=true}
   }
   val r=SignalAnalyzer.analyze(b,tr,vel,acc);synchronized(observations){observations+=Obs(elapsed,r)};lastObsAt=now
   memory.event("BOT_OBSERVATION",mapOf("cycle" to cycle,"cycle_elapsed_ms" to elapsed,"phase" to if(elapsed<80000L)"DEEP_OBSERVATION" else "FINAL_WINDOW","direction" to r.direction,"expiry_s" to r.expirySeconds,"raw_confidence" to r.confidence,"entry" to r.entryQuality,"conflict" to r.conflict,"reason" to r.reason,"up" to r.upScore,"down" to r.downScore,"side" to r.side,"kin_valid" to kinValid,"diagnostics" to (r.diagnostics+regInfo+traceLog(tr,b))))
   do{nextObservationAt+=1000L}while(nextObservationAt<=now)
  }
  val left=max(0,((nextDecisionAt-now+999)/1000).toInt())
   val phase=if(elapsed<80000L)"DEEP OBSERVATION" else "FINAL DECISION"
   main.post{brain?.text="72.0.1 · 90S · $phase ${left}s\ncycle $cycle"}
  if(now>=nextDecisionAt){
   finishCycle(b,nextDecisionAt)
   // Anchor cycles to the monotonic schedule, never to a late frame; this prevents cumulative drift.
   val late=now-nextDecisionAt
    // If Android was paused long enough to miss a boundary, skip stale cycles instead of emitting catch-up signals.
    cycleStart=nextDecisionAt
    nextDecisionAt+=90000L
    if(late>=90000L){
     val skipped=late/90000L
     cycleStart+=skipped*90000L
     nextDecisionAt=cycleStart+90000L
     memory.event("STALE_CYCLES_SKIPPED",mapOf("count" to skipped,"late_ms" to late))
    }
    nextObservationAt=cycleStart+1000L;cycle++
   synchronized(observations){observations.clear()}
  }
 }
 private fun traceLog(tr:TraceResult?,b:Bitmap):Map<String,Any?> =
  if(tr==null)mapOf("trace_b64" to "","bmp_w" to b.width,"bmp_h" to b.height)
  else mapOf("trace_b64" to TraceCodec.encode(tr),"bmp_w" to b.width,"bmp_h" to b.height,"trace_step_px" to tr.stepPx,"trace_roi_top" to tr.roiTop)

 private fun finishCycle(b:Bitmap,boundaryMono:Long){
   val list=synchronized(observations){observations.toList()}
   val nowMono=SystemClock.elapsedRealtime()
   val staleMs=if(lastObsAt==0L)Long.MAX_VALUE else nowMono-lastObsAt
   val result=CycleDecider.decide(list,90000L,change,nowMono/1000.0,experience,staleMs)
   val usableCount=list.count{it.r.direction=="UP"||it.r.direction=="DOWN"}
   val d=result.diagnostics
   recordFrameAsync(b,"decision")
   val actual=SystemClock.elapsedRealtime();memory.event("NINETY_SECOND_DECISION",mapOf(
    "cycle" to cycle,"cycle_mode" to "90S_80_PLUS_10","timeframe" to "1m","fixed_expiry_ms" to 60000,
    "scheduled_boundary_mono_ms" to boundaryMono,"actual_decision_mono_ms" to actual,
    "decision_latency_ms" to (actual-boundaryMono).coerceAtLeast(0),"direction" to result.direction,
    "expiry_s" to 60,"observations" to list.size,"usable" to usableCount,"up_weight" to d["up_weight"],
    "down_weight" to d["down_weight"],"consistency" to d["path_consistency"],"recent_flips" to d["recent_direction_flips"],
    "base_side" to d["base_side"],"final_corrected" to d["final_corrected"],"final_recent_side" to d["final_recent_side"],
    "slope_3s" to d["slope_3s"],"slope_6s" to d["slope_6s"],"slope_10s" to d["slope_10s"],"slope_20s" to d["slope_20s"],
    "trajectory_jerk" to d["trajectory_jerk"],"counter_magnitude" to d["counter_magnitude"],
    "persistence_decay" to d["persistence_decay"],"strength" to result.signalQuality,"entry_quality" to result.entryQuality,
    "reason" to result.reason,"diagnostics" to result.diagnostics))
   if(d["experience_action"]!=null&&d["experience_action"]!="NONE")memory.event("EXPERIENCE_EFFECT",mapOf("cycle" to cycle,"action" to d["experience_action"],"delta" to d["experience_delta"],"n_eff" to d["experience_samples"],"posterior" to d["experience_posterior"],"key" to d["experience_key"],"final_direction" to result.direction))
   main.post{brain?.text=if(result.direction=="WAIT")"WAIT · 90S FINAL\\ncycle $cycle" else "${if(result.direction=="UP")"↑" else "↓"} ${result.direction} · 1m\\nFINAL";if(result.direction!="WAIT")spawn(result.copy(expirySeconds=60))}
  }
 private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
 private fun spawn(r:SignalResult){
  val manager=wm?:return;val arrow=if(r.direction=="UP")"↑" else "↓";val signalId=UUID.randomUUID().toString()
  val percentText=if(r.signalQuality>0)"STR ${r.signalQuality}" else "STR --"
  val v=TextView(this).apply{text="$arrow ${r.direction} · ${r.expirySeconds/60}m · $percentText\nOPEN";textSize=11f;setTextColor(Color.BLACK);gravity=Gravity.CENTER;setPadding(dp(8),dp(5),dp(8),dp(5));background=glass();elevation=18f}
  val lp=params(Gravity.TOP or Gravity.START,dp(4),dp(120)+bubbles.size*dp(52));manager.addView(v,lp);bubbles+=v;restack()
  memory.event("SIGNAL_PUBLISHED",mapOf("signal_id" to signalId,"direction" to r.direction,"expiry_s" to r.expirySeconds,"confidence_state" to "STRENGTH_NOT_PROBABILITY","model_score" to r.signalQuality,"auto_expire_s" to 15,"publish_mono_ms" to SystemClock.elapsedRealtime(),"diagnostics" to r.diagnostics));val publishedMono=SystemClock.elapsedRealtime();var locked=false
  val expire=Runnable{if(!locked){memory.event("SIGNAL_NOT_EXECUTED_TIMEOUT",mapOf("signal_id" to signalId,"direction" to r.direction,"expiry_s" to r.expirySeconds,"model_score" to r.signalQuality,"visible_for_s" to 15));try{manager.removeView(v)}catch(_:Throwable){};bubbles.remove(v);restack()}}
  main.postDelayed(expire,15000L)
  v.setOnClickListener{if(locked)return@setOnClickListener;locked=true;main.removeCallbacks(expire);val startedNs=SystemClock.elapsedRealtimeNanos();val targetNs=startedNs+60_000_000_000L;memory.event("TRADE_USER_OPENED",mapOf("signal_id" to signalId,"direction" to r.direction,"expiry_s" to 60,"entry_mono_ns" to startedNs,"target_mono_ns" to targetNs,"model_score" to r.signalQuality,"signal_age_ms" to (SystemClock.elapsedRealtime()-publishedMono)));fun tick(){val nowNs=SystemClock.elapsedRealtimeNanos();val remainNs=(targetNs-nowNs).coerceAtLeast(0L);if(remainNs>0){val cs=(remainNs/10_000_000L);val sec=cs/100;val hundredths=cs%100;v.text="$arrow ${r.direction} · $percentText\nLOCKED · ${sec}.${hundredths.toString().padStart(2,'0')}s";main.postDelayed({tick()},10)}else{memory.event("EXPIRY_CLOCK",mapOf("signal_id" to signalId,"target_mono_ns" to targetNs,"actual_mono_ns" to nowNs,"timer_error_ns" to (nowNs-targetNs).coerceAtLeast(0L)));showResultButtons(v,r.copy(expirySeconds=60),signalId)}};tick()}
 }
 private fun showResultButtons(old:TextView,r:SignalResult,signalId:String){val manager=wm?:return;val lp=old.layoutParams as WindowManager.LayoutParams;try{manager.removeView(old)}catch(_:Throwable){};bubbles.remove(old);val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;background=glass();setPadding(8,6,8,6)};fun btn(t:String){row.addView(Button(this).apply{text=t;textSize=10f;setOnClickListener{
   memory.event("TRADE_RESULT",mapOf("signal_id" to signalId,"result" to t,"direction" to r.direction,"expiry_s" to r.expirySeconds,"model_score" to r.signalQuality,"diagnostics" to r.diagnostics))
   if(t=="WIN"||t=="LOSS"){
    val ctxState=(r.diagnostics["ctx_state"] as? String)?:"NO_RECENT"
    val ctxRegime=(r.diagnostics["ctx_regime"] as? String)?:"MIXED"
    val ctxBand=(r.diagnostics["ctx_band"] as? String)?:"E_MID"
    val st=experience.learn(r.direction,ctxState,ctxRegime,ctxBand,t)
    memory.event("EXPERIENCE_LEARNED",mapOf("signal_id" to signalId,"result" to t,"lifetime_results" to st.first,"profiles" to st.second,"cells" to st.second,"ctx" to "$ctxState|$ctxRegime|$ctxBand","drift" to experience.driftSummary()))
   }
   try{manager.removeView(row)}catch(_:Throwable){};bubbles.remove(row);restack()
  }})};btn("WIN");btn("LOSS");btn("VOID");manager.addView(row,lp);bubbles+=row;restack();memory.event("RESULT_REQUESTED",mapOf("signal_id" to signalId,"direction" to r.direction,"expiry_s" to r.expirySeconds,"model_score" to r.signalQuality))}
 private fun restack(){bubbles.forEachIndexed{i,v->try{val lp=v.layoutParams as WindowManager.LayoutParams;lp.y=dp(120)+i*dp(52);wm?.updateViewLayout(v,lp)}catch(_:Throwable){}}}
 private fun channel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Live Signal 7.1 Memory",NotificationManager.IMPORTANCE_LOW))}
 override fun onDestroy(){memory.event("SESSION_STOP");reader?.setOnImageAvailableListener(null,null);display?.release();reader?.close();try{projection?.stop()}catch(_:Throwable){};worker.shutdownNow();evidenceWorker.shutdown();main.post{try{brain?.let{wm?.removeView(it)}}catch(_:Throwable){};bubbles.toList().forEach{try{wm?.removeView(it)}catch(_:Throwable){}}};super.onDestroy()}
 override fun onBind(i:Intent?):IBinder?=null
}
