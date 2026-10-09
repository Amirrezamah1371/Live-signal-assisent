package com.example.livesignalassistant

import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppStorageLock

class MemoryStore(private val ctx: Context) {
    private val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val dir = File(ctx.filesDir, "sessions/$sessionId").apply { mkdirs() }
    private val frames = File(dir,"frames").apply{mkdirs()}
    private val log = File(dir,"timeline.jsonl")
    fun event(type:String, data:Map<String,Any?> = emptyMap()) = synchronized(AppStorageLock) {
        val o=JSONObject(); o.put("ts_ms",System.currentTimeMillis());o.put("mono_ms",SystemClock.elapsedRealtime());o.put("type",type)
        data.forEach{(k,v)->o.put(k, JSONObject.wrap(v))}
        // Labels are measurement tags. The decision engine does not read them.
        ExperimentTag.sessionFields(BuildConfig.CHART_TIMEFRAME, AccountMode.current(ctx)).forEach { (k, v) -> o.put(k, v) }
        log.appendText(o.toString()+"\n")
    }
    @Synchronized fun frame(b:Bitmap, tag:String="observe"):String {
        // Live analysis already used the original full-resolution bitmap.
        // Only persistent black-box evidence is compacted here.
        val top=(b.height*0.10).toInt().coerceAtLeast(0)
        val bottom=(b.height*0.90).toInt().coerceAtMost(b.height)
        val cropped=Bitmap.createBitmap(b,0,top,b.width,(bottom-top).coerceAtLeast(1))
        val targetW=if(tag=="observe")360 else 540
        val targetH=(cropped.height*(targetW.toDouble()/cropped.width)).toInt().coerceAtLeast(1)
        val scaled=Bitmap.createScaledBitmap(cropped,targetW,targetH,true)
        cropped.recycle()
        val name="${System.currentTimeMillis()}_${tag}.webp"; val f=File(frames,name)
        val quality=if(tag=="observe")28 else 48
        FileOutputStream(f).use{scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY,quality,it)}
        val bytes=f.length(); scaled.recycle()
        event("SCREEN_EVIDENCE",mapOf("file" to "frames/$name","tag" to tag,"stored_bytes" to bytes,
            "storage_codec" to "WEBP_LOSSY","storage_width" to targetW,"analysis_resolution" to "FULL"))
        return name
    }
    fun exportZip():String {
        event("EXPORT_REQUESTED")
        val timeline = synchronized(AppStorageLock) { if (log.exists()) log.readBytes() else ByteArray(0) }
        val tmp=File(ctx.cacheDir,"LiveSignalMemory_$sessionId.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { z ->
            fun add(f:File, base:String){
                if(f.isDirectory) f.listFiles()?.forEach{add(it,if(base.isEmpty())it.name else "$base/${it.name}")}
                else if (f == log) { z.putNextEntry(ZipEntry(base)); z.write(timeline); z.closeEntry() }
                else {z.putNextEntry(ZipEntry(base));f.inputStream().use{it.copyTo(z)};z.closeEntry()}
            }
            dir.listFiles()?.forEach{add(it,it.name)}
            z.putNextEntry(ZipEntry("README.txt")); z.write(ExperimentTag.readme(BuildConfig.CHART_TIMEFRAME, AccountMode.current(ctx)).toByteArray()); z.closeEntry()
        }
        val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,tmp.name);put(MediaStore.MediaColumns.MIME_TYPE,"application/zip");if(Build.VERSION.SDK_INT>=29)put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)}
        val uri=ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:return tmp.absolutePath
        ctx.contentResolver.openOutputStream(uri)?.use{out->tmp.inputStream().use{it.copyTo(out)}}
        return "Downloads/${tmp.name}"
    }
}
