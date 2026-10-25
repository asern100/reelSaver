package com.asern.reelsaver

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

/**
 * Removes the music from a video or audio file and keeps the voices, fully on the phone.
 * Uses the UVR MDX-Net "Voc_FT" AI model (downloaded once, ~67 MB) with ONNX Runtime,
 * and the ffmpeg that ships with the download engine to decode and re-mux.
 */
object MusicRemover {

    private const val MODEL_URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx"
    private const val MODEL_SIZE = 66_762_490L
    private const val GAIN = 1.021f            // UVR's level compensation for this model
    private const val MAX_SECONDS = 60 * 60

    /** One job at a time: the model is heavy on CPU and memory */
    private val lock = Mutex()

    fun modelFile(c: Context) = File(c.filesDir, "models/voc_ft.onnx")
    fun isModelReady(c: Context) = modelFile(c).length() == MODEL_SIZE

    /**
     * Writes a copy of [input] without music to [output] (same kind: video → .mp4, audio → .mp3/.m4a).
     * [onEvent] reports status text and percent from a background thread.
     */
    suspend fun process(
        c: Context, input: File, output: File, isAudio: Boolean,
        onEvent: (Downloader.Event) -> Unit,
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            Log.i("ReelSaver", "music removal: waiting for engine")
            App.ready.await()
            Log.i("ReelSaver", "music removal: model ready=${isModelReady(c)}")
            ensureModel(c, onEvent)
            val work = File(c.cacheDir, "nomusic").apply { deleteRecursively(); mkdirs() }
            try {
                // 1) Decode the sound to raw 44.1 kHz stereo floats
                onEvent(Downloader.Event.Status(c.getString(R.string.nm_reading)))
                val mixFile = File(work, "mix.f32")
                ffmpeg(c, "-i", input.absolutePath, "-vn", "-ac", "2", "-ar", "${MdxCore.SAMPLE_RATE}",
                    "-t", "$MAX_SECONDS", "-f", "f32le", mixFile.absolutePath)
                val n = (mixFile.length() / 8).toInt()
                if (n < MdxCore.SAMPLE_RATE / 10) throw Exception(c.getString(R.string.nm_no_audio))

                // 2) Separate the voices with the AI model
                val voxFile = File(work, "vox.f32")
                separate(c, mixFile, voxFile, n, onEvent)

                // 3) Put the voices back with the original picture
                onEvent(Downloader.Event.Status(c.getString(R.string.nm_saving)))
                output.delete()
                val pcm = arrayOf("-f", "f32le", "-ar", "${MdxCore.SAMPLE_RATE}", "-ac", "2", "-i", voxFile.absolutePath)
                if (isAudio) {
                    val codec = if (output.extension == "mp3") arrayOf("-c:a", "libmp3lame", "-q:a", "2")
                    else arrayOf("-c:a", "aac", "-b:a", "192k")
                    ffmpeg(c, *pcm, *codec, output.absolutePath)
                } else {
                    ffmpeg(c, "-i", input.absolutePath, *pcm, "-map", "0:v:0", "-map", "1:a:0",
                        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
                        "-movflags", "+faststart", output.absolutePath)
                }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    private suspend fun separate(c: Context, mixFile: File, voxFile: File, n: Int, onEvent: (Downloader.Event) -> Unit) {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        onEvent(Downloader.Event.Status(c.getString(R.string.nm_loading_model)))
        val session = env.createSession(modelFile(c).absolutePath, opts)
        val core = MdxCore()
        val chunks = MdxCore.chunkCount(n)
        val spec = FloatArray(MdxCore.SPEC_SIZE)
        val shape = longArrayOf(1, 4, MdxCore.DIM_F.toLong(), MdxCore.DIM_T.toLong())
        RandomAccessFile(mixFile, "r").use { inRaf ->
            RandomAccessFile(voxFile, "rw").use { outRaf ->
                outRaf.setLength(n * 8L)
                val mix = inRaf.channel.map(FileChannel.MapMode.READ_ONLY, 0, n * 8L)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val vox = outRaf.channel.map(FileChannel.MapMode.READ_WRITE, 0, n * 8L)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                try {
                    onEvent(Downloader.Event.Progress(0, -1, c.getString(R.string.nm_progress, 0)))
                    val start = System.currentTimeMillis()
                    for (k in 0 until chunks) {
                        coroutineContext.ensureActive()
                        core.stft(mix, n, k, spec)
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), shape).use { t ->
                            session.run(mapOf("input" to t)).use { r ->
                                (r[0] as OnnxTensor).floatBuffer.get(spec)
                            }
                        }
                        core.istft(spec, n, k, GAIN, vox)
                        val done = k + 1
                        val eta = (System.currentTimeMillis() - start) / done * (chunks - done) / 1000
                        val pct = done * 100 / chunks
                        Log.i("ReelSaver", "music removal chunk $done/$chunks")
                        onEvent(Downloader.Event.Progress(pct, eta, c.getString(R.string.nm_progress, pct)))
                    }
                } finally {
                    session.close(); opts.close()
                }
            }
        }
    }

    /** Downloads the AI model the first time (~67 MB) */
    private fun ensureModel(c: Context, onEvent: (Downloader.Event) -> Unit) {
        val f = modelFile(c)
        if (f.length() == MODEL_SIZE) return
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        onEvent(Downloader.Event.Status(c.getString(R.string.nm_model_download)))
        var url = URL(MODEL_URL)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {   // follow GitHub's redirect to its file server
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            Log.i("ReelSaver", "model download: HTTP $code from ${url.host}")
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
            }
            if (code != 200) throw Exception(c.getString(R.string.nm_model_failed) + " (HTTP $code)")
            break
        }
        try {
            conn.inputStream.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L; var lastPct = -1
                    while (true) {
                        val r = ins.read(buf); if (r < 0) break
                        out.write(buf, 0, r); total += r
                        val pct = (total * 100 / MODEL_SIZE).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            if (pct % 10 == 0) Log.i("ReelSaver", "model download $pct%")
                            onEvent(Downloader.Event.Progress(pct, -1, c.getString(R.string.nm_model_progress, pct)))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw Exception(c.getString(R.string.nm_model_failed), e)
        } finally {
            conn.disconnect()
        }
        if (part.length() != MODEL_SIZE) { part.delete(); throw Exception(c.getString(R.string.nm_model_failed)) }
        part.renameTo(f)
    }

    /** Runs the ffmpeg bundled with the download engine */
    private fun ffmpeg(c: Context, vararg args: String) {
        val bin = File(c.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pkgs = File(c.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.absolutePath, "-hide_banner", "-nostdin", "-y", "-v", "error") + args)
            .redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = "$pkgs/python/usr/lib:$pkgs/ffmpeg/usr/lib"
        val p = pb.start()
        val log = p.inputStream.bufferedReader().readText()
        Log.i("ReelSaver", "ffmpeg ${args.take(3)} -> ${log.takeLast(200)}")
        if (p.waitFor() != 0) throw Exception("ffmpeg: " + log.trim().takeLast(300))
    }

    /** Copies a shared/content file into the cache so ffmpeg can read it */
    fun copyToCache(c: Context, uri: Uri, name: String): File {
        val f = File(c.cacheDir, "nm-in/$name").apply { parentFile?.deleteRecursively(); parentFile?.mkdirs() }
        (c.contentResolver.openInputStream(uri) ?: throw Exception(c.getString(R.string.no_file)))
            .use { ins -> f.outputStream().use { ins.copyTo(it) } }
        return f
    }
}
