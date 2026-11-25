package com.facerecognitionsdk

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.ActivityResult
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.faceplugin.facerecognitionsdk.FaceRecognitionSDK
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

@CapacitorPlugin(name = "FaceRecognitionSdk")
class FaceRecognitionSdkPlugin : Plugin() {

  private val executor = Executors.newSingleThreadExecutor()
  private val analysisExecutor = Executors.newSingleThreadExecutor()
  @Volatile private var lastLiveBitmap: Bitmap? = null
  @Volatile private var nativesLoaded = false
  @Volatile private var analysisBusy = false
  @Volatile private var lastAnalysisMs = 0L

  private var cameraProvider: ProcessCameraProvider? = null
  private var previewView: PreviewView? = null
  private var previewHost: FrameLayout? = null

  override fun load() {
    preloadNatives()
  }

  private fun preloadNatives() {
    if (nativesLoaded) return
    val names = arrayOf(
      "c++_shared",
      "onnxruntime",
      "FaceRecognitionEngine",
      "FaceRecognitionEngine_jni",
      "FaceRecognitionSDK"
    )
    for (name in names) {
      try {
        System.loadLibrary(name)
      } catch (_: UnsatisfiedLinkError) {
      }
    }
    nativesLoaded = true
  }

  @PluginMethod
  fun getMachineCode(call: PluginCall) {
    executor.execute {
      try {
        preloadNatives()
        val mc = FaceRecognitionSDK.machineCode(context.applicationContext) ?: ""
        call.resolve(value(mc))
      } catch (t: Throwable) {
        reject(call, "E_MACHINE_CODE", t)
      }
    }
  }

  @PluginMethod
  fun getLicenseStatus(call: PluginCall) {
    executor.execute {
      try {
        preloadNatives()
        call.resolve(value(FaceSdkCompat.licenseStatusJson()))
      } catch (t: Throwable) {
        reject(call, "E_LICENSE_STATUS", t)
      }
    }
  }

  @PluginMethod
  fun setActivation(call: PluginCall) {
    val license = call.getString("license")
    if (license.isNullOrBlank()) {
      call.reject("license is required", "E_ACTIVATION")
      return
    }
    executor.execute {
      try {
        preloadNatives()
        val code = FaceRecognitionSDK.activate(context.applicationContext, license)
        call.resolve(value(code))
      } catch (t: Throwable) {
        reject(call, "E_ACTIVATION", t)
      }
    }
  }

  @PluginMethod
  fun init(call: PluginCall) {
    executor.execute {
      try {
        preloadNatives()
        val code = FaceRecognitionSDK.init(context.applicationContext)
        call.resolve(value(code))
      } catch (t: Throwable) {
        reject(call, "E_INIT", t)
      }
    }
  }

  @PluginMethod
  fun deinit(call: PluginCall) {
    executor.execute {
      try {
        FaceSdkCompat.clearDetectedFaces()
        FaceRecognitionSDK.close()
        call.resolve()
      } catch (t: Throwable) {
        reject(call, "E_DEINIT", t)
      }
    }
  }

  @PluginMethod
  fun lastLicenseError(call: PluginCall) {
    try {
      call.resolve(value(FaceRecognitionSDK.lastLicenseError() ?: ""))
    } catch (t: Throwable) {
      reject(call, "E_LICENSE_ERROR", t)
    }
  }

  @PluginMethod
  fun setLandmarkMode(call: PluginCall) {
    val mode = call.getInt("mode") ?: 14
    executor.execute {
      try {
        call.resolve(value(0.also { @Suppress("UNUSED_EXPRESSION") mode }))
      } catch (t: Throwable) {
        reject(call, "E_LANDMARK", t)
      }
    }
  }

  @PluginMethod
  fun getLandmarkMode(call: PluginCall) {
    executor.execute {
      try {
        call.resolve(value(0))
      } catch (t: Throwable) {
        reject(call, "E_LANDMARK", t)
      }
    }
  }

  @PluginMethod
  fun detect(call: PluginCall) {
    val imageUri = call.getString("image")
    if (imageUri.isNullOrBlank()) {
      call.reject("image is required", "E_IMAGE")
      return
    }
    val crop = call.getBoolean("crop", false) ?: false
    val flags = call.getInt("flags") ?: -1
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        @Suppress("UNUSED_EXPRESSION") crop
        val json = FaceSdkCompat.detectJsonForFlags(bitmap, flags)
        call.resolve(value(json ?: "{}"))
      } catch (t: Throwable) {
        reject(call, "E_DETECT", t)
      }
    }
  }

  @PluginMethod
  fun faceDetection(call: PluginCall) {
    val imageUri = call.getString("image")
    if (imageUri.isNullOrBlank()) {
      call.reject("image is required", "E_IMAGE")
      return
    }
    val paramJson = call.getString("param")
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        call.resolve(value(FaceSdkCompat.detectJson(bitmap, paramJson)))
      } catch (t: Throwable) {
        reject(call, "E_FACE_DETECTION", t)
      }
    }
  }

  @PluginMethod
  fun templateExtraction(call: PluginCall) {
    val imageUri = call.getString("image")
    val faceBoxJson = call.getString("faceBox")
    if (imageUri.isNullOrBlank() || faceBoxJson.isNullOrBlank()) {
      call.reject("image and faceBox are required", "E_TEMPLATE")
      return
    }
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        val bytes = FaceSdkCompat.templateBase64(bitmap, faceBoxJson)
          .let { if (it.isEmpty()) null else Base64.decode(it, Base64.DEFAULT) }
        if (bytes == null) {
          call.reject("templateExtraction returned null", "E_TEMPLATE")
          return@execute
        }
        call.resolve(value(Base64.encodeToString(bytes, Base64.NO_WRAP)))
      } catch (t: Throwable) {
        reject(call, "E_TEMPLATE", t)
      }
    }
  }

  @PluginMethod
  fun cropFace(call: PluginCall) {
    val imageUri = call.getString("image")
    val faceBoxJson = call.getString("faceBox")
    if (imageUri.isNullOrBlank() || faceBoxJson.isNullOrBlank()) {
      call.reject("image and faceBox are required", "E_CROP")
      return
    }
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        val cropped = FaceSdkCompat.cropFaceBase64(bitmap, faceBoxJson)
          .let { if (it.isEmpty()) null else it } ?: run {
          call.reject("cropFace returned null", "E_CROP")
          return@execute
        }
        call.resolve(value(cropped))
      } catch (t: Throwable) {
        reject(call, "E_CROP", t)
      }
    }
  }

  @PluginMethod
  fun extractFeature(call: PluginCall) {
    val imageUri = call.getString("image")
    if (imageUri.isNullOrBlank()) {
      call.reject("image is required", "E_FEATURE")
      return
    }
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        call.resolve(value(FaceSdkCompat.extractFeatureBase64(bitmap)))
      } catch (t: Throwable) {
        reject(call, "E_FEATURE", t)
      }
    }
  }

  @PluginMethod
  fun similarity(call: PluginCall) {
    val f1b64 = call.getString("feature1")
    val f2b64 = call.getString("feature2")
    if (f1b64.isNullOrBlank() || f2b64.isNullOrBlank()) {
      call.reject("feature1 and feature2 are required", "E_SIMILARITY")
      return
    }
    executor.execute {
      try {
        val f1 = Base64.decode(f1b64, Base64.DEFAULT)
        val f2 = Base64.decode(f2b64, Base64.DEFAULT)
        call.resolve(value(FaceRecognitionSDK.similarity(f1, f2).toDouble()))
      } catch (t: Throwable) {
        reject(call, "E_SIMILARITY", t)
      }
    }
  }

  /**
   * 1:N similarity in one bridge call. A bad or empty candidate scores -1.
   *
   * The gallery search would otherwise be one round trip per enrolled person, which is what
   * makes a 500-face gallery feel slow in a WebView app.
   */
  @PluginMethod
  fun similarityMany(call: PluginCall) {
    val probeB64 = call.getString("probe")
    val candidates = call.getArray("candidates")
    if (probeB64.isNullOrBlank() || candidates == null) {
      call.reject("probe and candidates are required", "E_SIMILARITY")
      return
    }
    executor.execute {
      try {
        val probe = Base64.decode(probeB64, Base64.DEFAULT)
        val out = JSONArray()
        for (i in 0 until candidates.length()) {
          val s = candidates.optString(i, "")
          if (s.isNullOrEmpty()) {
            out.put(-1.0)
            continue
          }
          val score = try {
            FaceRecognitionSDK.similarity(probe, Base64.decode(s, Base64.DEFAULT)).toDouble()
          } catch (_: Throwable) {
            -1.0
          }
          out.put(if (score.isNaN()) -1.0 else score)
        }
        call.resolve(JSObject().put("value", out.toString()))
      } catch (t: Throwable) {
        reject(call, "E_SIMILARITY", t)
      }
    }
  }

  /**
   * Writes a JPEG of the image, or of a face crop, into app-private storage and resolves its
   * `file://` URI. [path] is absolute or relative to the app files dir.
   *
   * Options: box {x1,y1,x2,y2}, mode "full" | "padded" | "square", padding (0.12),
   * scale (1.8), maxSide, quality (92).
   */
  @PluginMethod
  fun writeFaceImage(call: PluginCall) {
    val imageUri = call.getString("image")
    val path = call.getString("path")
    if (imageUri.isNullOrBlank() || path.isNullOrBlank()) {
      call.reject("image and path are required", "E_WRITE")
      return
    }
    val o = parseJson(call.getString("options"))
    executor.execute {
      try {
        val source = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        val bitmap = ImageUtils.scaleDown(source, o.optInt("maxSide", ImageUtils.ENGINE_MAX_SIDE))
        val boxJson = o.optJSONObject("box")
        val box = boxJson?.let {
          android.graphics.Rect(it.optInt("x1"), it.optInt("y1"), it.optInt("x2"), it.optInt("y2"))
        }
        val out: Bitmap = when (o.optString("mode", if (box == null) "full" else "padded")) {
          "square" -> if (box != null) ImageUtils.squareAround(bitmap, box, o.optDouble("scale", 1.8).toFloat()) else bitmap
          "padded" -> if (box != null) ImageUtils.cropPadded(bitmap, box, o.optDouble("padding", 0.12).toFloat()) ?: bitmap else bitmap
          else -> bitmap
        }
        val file = resolveFile(path)
        file.parentFile?.mkdirs()
        file.writeBytes(ImageUtils.jpeg(out, o.optInt("quality", 92)))
        call.resolve(JSObject().put("value", "file://" + file.absolutePath))
      } catch (t: Throwable) {
        reject(call, "E_WRITE", t)
      }
    }
  }

  /** Deletes each path (absolute or relative to the files dir). Resolves the number removed. */
  @PluginMethod
  fun deleteFiles(call: PluginCall) {
    val paths = call.getArray("paths")
    if (paths == null) {
      call.reject("paths is required", "E_DELETE")
      return
    }
    executor.execute {
      try {
        var n = 0
        for (i in 0 until paths.length()) {
          val p = paths.optString(i, "")
          if (p.isNullOrEmpty()) continue
          if (resolveFile(p).delete()) n++
        }
        call.resolve(value(n.toDouble()))
      } catch (t: Throwable) {
        reject(call, "E_DELETE", t)
      }
    }
  }

  /** Deletes every file inside [path] but keeps the directory. Resolves the number removed. */
  @PluginMethod
  fun deleteDirectory(call: PluginCall) {
    val path = call.getString("path")
    if (path.isNullOrBlank()) {
      call.reject("path is required", "E_DELETE")
      return
    }
    executor.execute {
      try {
        var n = 0
        resolveFile(path).listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        call.resolve(value(n.toDouble()))
      } catch (t: Throwable) {
        reject(call, "E_DELETE", t)
      }
    }
  }

  @PluginMethod
  fun quality(call: PluginCall) {
    val imageUri = call.getString("image")
    if (imageUri.isNullOrBlank()) {
      call.reject("image is required", "E_QUALITY")
      return
    }
    val crop = call.getBoolean("crop", false) ?: false
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        call.resolve(value(FaceSdkCompat.qualityJson(bitmap)))
      } catch (t: Throwable) {
        reject(call, "E_QUALITY", t)
      }
    }
  }

  @PluginMethod
  fun startVideoWorker(call: PluginCall) {
    val configJson = call.getString("config")
    executor.execute {
      try {
                val threshold = parseMatchThreshold(configJson)
        val code = 0.also { FaceSdkCompat.startWorker(threshold) }
        call.resolve(value(code))
      } catch (t: Throwable) {
        reject(call, "E_VIDEO_WORKER", t)
      }
    }
  }

  @PluginMethod
  fun stopVideoWorker(call: PluginCall) {
    executor.execute {
      try {
        FaceSdkCompat.stopWorker()
        
        call.resolve()
      } catch (t: Throwable) {
        reject(call, "E_VIDEO_WORKER", t)
      }
    }
  }

  @PluginMethod
  fun syncVideoWorkerDatabase(call: PluginCall) {
    val threshold = call.getDouble("matchThreshold") ?: 0.67
    val features = call.getArray("features") ?: JSArray()
    executor.execute {
      try {
        val list = ArrayList<ByteArray>()
        for (i in 0 until features.length()) {
          val s = features.getString(i) ?: continue
          list.add(Base64.decode(s, Base64.DEFAULT))
        }
        val code = 0.also { FaceSdkCompat.syncWorker(list, threshold.toFloat()) }
        call.resolve(value(code))
      } catch (t: Throwable) {
        reject(call, "E_SYNC_DB", t)
      }
    }
  }

  @PluginMethod
  fun probeLiveImage(call: PluginCall) {
    val imageUri = call.getString("image")
    if (imageUri.isNullOrBlank()) {
      call.reject("image is required", "E_IMAGE")
      return
    }
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        val ret = JSObject()
        ret.put("width", bitmap.width)
        ret.put("height", bitmap.height)
        call.resolve(ret)
      } catch (t: Throwable) {
        reject(call, "E_IMAGE", t)
      }
    }
  }

  @PluginMethod
  fun applyLiveFrame(call: PluginCall) {
    val imageUri = call.getString("image")
    if (imageUri.isNullOrBlank()) {
      call.reject("image is required", "E_FRAME")
      return
    }
    val rotateDegrees = (call.getDouble("rotateDegrees") ?: 0.0).toFloat()
    val maxEdge = (call.getInt("maxEdge") ?: 640).coerceAtLeast(1)
    val feedWorker = call.getBoolean("feedWorker", true) ?: true
    executor.execute {
      try {
        val bitmap = loadBitmap(imageUri) ?: run {
          call.reject("Could not decode image: $imageUri", "E_IMAGE")
          return@execute
        }
        val prepared = applyLiveTransform(bitmap, rotateDegrees, maxEdge)
        lastLiveBitmap = prepared
        if (feedWorker) {
          FaceSdkCompat.workerFrameJson(prepared)?.let { emitWorkerEvent(it) }
          call.resolve(liveFrameMap(prepared, ingested = true, uri = null))
        } else {
          call.resolve(liveFrameMap(prepared, ingested = true, uri = writeLiveJpeg(prepared)))
        }
      } catch (t: Throwable) {
        reject(call, "E_FRAME", t)
      }
    }
  }

  @PluginMethod
  fun exportLastLiveFrame(call: PluginCall) {
    executor.execute {
      try {
        val prepared = lastLiveBitmap ?: run {
          call.reject("No live frame", "E_IMAGE")
          return@execute
        }
        call.resolve(liveFrameMap(prepared, ingested = true, uri = writeLiveJpeg(prepared)))
      } catch (t: Throwable) {
        reject(call, "E_FRAME", t)
      }
    }
  }

  @PluginMethod
  fun writeStatus(call: PluginCall) {
    val json = call.getString("payload") ?: "{}"
    try {
      File(context.filesDir, "facerecognition_status.json").writeText(json)
      call.resolve()
    } catch (t: Throwable) {
      reject(call, "E_STATUS", t)
    }
  }

  @PluginMethod
  fun estimatorStatus(call: PluginCall) {
    executor.execute {
      try {
        call.resolve(value("{}"))
      } catch (t: Throwable) {
        reject(call, "E_ESTIMATOR", t)
      }
    }
  }

  @PluginMethod
  fun startLivePreview(call: PluginCall) {
    val front = call.getBoolean("frontCamera", true) ?: true
    val activity = activity
    if (activity == null) {
      call.reject("No activity", "E_CAMERA")
      return
    }
    activity.runOnUiThread {
      try {
        // A new surface each time. Reusing the host meant the previous visit's last frame sat
        // on screen until the camera bound, which read as the camera being stuck.
        detachPreviewHost()
        attachPreviewHost()
        val previewView = previewView ?: run {
          call.reject("Preview view missing", "E_CAMERA")
          return@runOnUiThread
        }
        // Wait for layout so ViewPort matches the WebView / overlay size.
        previewView.post {
          bindCameraUseCases(front, call)
        }
      } catch (t: Throwable) {
        reject(call, "E_CAMERA", t)
      }
    }
  }

  private fun bindCameraUseCases(front: Boolean, call: PluginCall, attempt: Int = 0) {
    val activity = activity ?: run {
      call.reject("No activity", "E_CAMERA")
      return
    }
    val previewView = previewView ?: run {
      call.reject("Preview view missing", "E_CAMERA")
      return
    }
    // CameraX draws nothing until the view has a display and a size. Binding earlier
    // leaves the black host on screen, which is all the user sees through the WebView.
    if ((previewView.width <= 0 || previewView.height <= 0 || previewView.display == null) && attempt < 30) {
      previewView.post { bindCameraUseCases(front, call, attempt + 1) }
      return
    }
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
      try {
        val provider = future.get()
        cameraProvider?.unbindAll()
        cameraProvider = provider
        syncPreviewHostToWebView()

        val useFront = when {
          front && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> true
          provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> false
          else -> front
        }
        val selector = if (useFront) {
          CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
          CameraSelector.DEFAULT_BACK_CAMERA
        }
        val rotation = previewView.display?.rotation ?: 0
        val size = Size(720, 1280)
        val preview = Preview.Builder()
          .setTargetResolution(size)
          .setTargetRotation(rotation)
          .build()
        val analysis = ImageAnalysis.Builder()
          .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
          .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
          .setTargetResolution(size)
          .setTargetRotation(rotation)
          .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
          ingestAnalysisFrame(image)
        }
        // Same bind as the native app. A UseCaseGroup on an empty viewport stays black.
        provider.bindToLifecycle(activity as LifecycleOwner, selector, preview, analysis)
        preview.setSurfaceProvider(previewView.surfaceProvider)
        setWebViewTransparent(true)
        call.resolve()
      } catch (t: Throwable) {
        reject(call, "E_CAMERA", t)
      }
    }, ContextCompat.getMainExecutor(context))
  }

  @PluginMethod
  fun stopLivePreview(call: PluginCall) {
    val activity = activity
    if (activity == null) {
      cameraProvider = null
      call.resolve()
      return
    }
    activity.runOnUiThread {
      try {
        cameraProvider?.unbindAll()
        setWebViewTransparent(false)
        detachPreviewHost()
        call.resolve()
      } catch (t: Throwable) {
        reject(call, "E_CAMERA", t)
      }
    }
  }

  @PluginMethod
  fun pickFolderImages(call: PluginCall) {
    startActivityForResult(call, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), "onFolderPicked")
  }

  @ActivityCallback
  fun onFolderPicked(call: PluginCall, result: ActivityResult) {
    if (result.resultCode != Activity.RESULT_OK || result.data?.data == null) {
      val ret = JSObject()
      ret.put("items", JSArray())
      call.resolve(ret)
      return
    }
    val tree = result.data!!.data!!
    try {
      activity.contentResolver.takePersistableUriPermission(
        tree,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    } catch (_: Exception) {
    }
    val arr = JSArray()
    FolderImages.list(activity, tree).forEach { item ->
      val o = JSObject()
      o.put("uri", item.uri)
      o.put("fileName", item.fileName)
      arr.put(o)
    }
    val ret = JSObject()
    ret.put("items", arr)
    call.resolve(ret)
  }

  @PluginMethod
  fun takeLiveSnapshot(call: PluginCall) {
    executor.execute {
      try {
        val prepared = lastLiveBitmap ?: run {
          call.reject("Live preview is not running", "E_CAMERA")
          return@execute
        }
        val uri = writeLiveJpeg(prepared)
        val ret = JSObject()
        ret.put("uri", uri)
        ret.put("path", uri.removePrefix("file://"))
        call.resolve(ret)
      } catch (t: Throwable) {
        reject(call, "E_CAMERA", t)
      }
    }
  }

  private fun ingestAnalysisFrame(image: androidx.camera.core.ImageProxy) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastAnalysisMs < 120L || analysisBusy) {
      image.close()
      return
    }
    analysisBusy = true
    lastAnalysisMs = now
    try {
      val bitmap = ImageUtils.bitmapFromImageProxy(image) ?: return
      val prepared = applyLiveTransform(bitmap, 0f, 640)
      if (prepared !== bitmap && !bitmap.isRecycled) {
        bitmap.recycle()
      }
      lastLiveBitmap = prepared
      try {
        FaceSdkCompat.workerFrameJson(prepared)?.let { emitWorkerEvent(it) }
      } catch (_: Throwable) {
        // Worker may not be running yet.
      }
    } catch (_: Throwable) {
      // Drop a bad preview frame.
    } finally {
      image.close()
      analysisBusy = false
    }
  }

  private fun attachPreviewHost() {
    val activity = activity ?: return
    val webView = bridge.webView ?: return
    val parent = webView.parent as? ViewGroup ?: return
    if (previewHost != null) {
      syncPreviewHostToWebView()
      return
    }
    val host = FrameLayout(activity)
    host.setBackgroundColor(Color.BLACK)
    val view = PreviewView(activity)
    view.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    view.scaleType = PreviewView.ScaleType.FILL_CENTER
    host.addView(
      view,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    )
    // Match WebView frame so FILL_CENTER crop matches the HTML overlay.
    val lp = ViewGroup.MarginLayoutParams(
      if (webView.width > 0) webView.width else ViewGroup.LayoutParams.MATCH_PARENT,
      if (webView.height > 0) webView.height else ViewGroup.LayoutParams.MATCH_PARENT
    )
    lp.leftMargin = webView.left
    lp.topMargin = webView.top
    parent.addView(host, 0, lp)
    host.isClickable = false
    host.isFocusable = false
    view.isClickable = false
    webView.bringToFront()
    previewHost = host
    previewView = view
    webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      syncPreviewHostToWebView()
    }
  }

  private fun syncPreviewHostToWebView() {
    val webView = bridge.webView ?: return
    val host = previewHost ?: return
    val lp = host.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    if (webView.width <= 0 || webView.height <= 0) return
    if (
      lp.width == webView.width &&
      lp.height == webView.height &&
      lp.leftMargin == webView.left &&
      lp.topMargin == webView.top
    ) {
      return
    }
    lp.width = webView.width
    lp.height = webView.height
    lp.leftMargin = webView.left
    lp.topMargin = webView.top
    host.layoutParams = lp
  }

  private fun detachPreviewHost() {
    val host = previewHost ?: return
    (host.parent as? ViewGroup)?.removeView(host)
    previewHost = null
    previewView = null
  }

  private fun setWebViewTransparent(transparent: Boolean) {
    val webView = bridge.webView ?: return
    // Opaque means the app's own background, not black. Transparency is only for a live
    // preview. A hardware layer is an opaque surface: it hides the camera behind the WebView
    // and the page comes out black. A software layer keeps per-pixel alpha, so the preview
    // shows through and the controls stay drawn.
    val color = if (transparent) Color.TRANSPARENT else APP_BACKGROUND
    webView.setBackgroundColor(color)
    webView.setLayerType(
      if (transparent) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE,
      null
    )
    webView.bringToFront()
  }

  /** One tracking event, on the channel the host already listens to. */
  private fun emitWorkerEvent(json: String) {
    notifyListeners(EVENT_VIDEO_WORKER, JSObject().put("json", json))
  }

  private fun value(v: Any): JSObject {
    val ret = JSObject()
    ret.put("value", v)
    return ret
  }

  private fun reject(call: PluginCall, code: String, t: Throwable) {
    val msg = t.message ?: code
    val ex = if (t is Exception) t else Exception(t)
    call.reject(msg, code, ex)
  }

  private fun resolveFile(path: String): File {
    val p = if (path.startsWith("file://")) path.removePrefix("file://") else path
    val f = File(p)
    return if (f.isAbsolute) f else File(context.applicationContext.filesDir, p)
  }

  private fun parseJson(json: String?): JSONObject = try {
    if (json.isNullOrBlank()) JSONObject() else JSONObject(json)
  } catch (_: Exception) {
    JSONObject()
  }

  private fun loadBitmap(uriOrBase64: String): Bitmap? {
    return if (uriOrBase64.startsWith("data:") || looksLikeBase64(uriOrBase64)) {
      ImageUtils.bitmapFromBase64(uriOrBase64)
    } else {
      ImageUtils.bitmapFromUri(context.applicationContext, uriOrBase64)
    }
  }

  private fun looksLikeBase64(s: String): Boolean {
    if (s.startsWith("content:") || s.startsWith("file:") || s.startsWith("/")) return false
    return s.length > 256 && !s.contains("://")
  }





  private fun parseMatchThreshold(configJson: String?): Float {
    if (configJson.isNullOrBlank()) return 0.67f
    return try {
      JSONObject(configJson).optDouble("matchThreshold", 0.67).toFloat()
    } catch (_: Exception) {
      0.67f
    }
  }

  private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  private fun liveFrameMap(prepared: Bitmap, ingested: Boolean, uri: String?): JSObject {
    val map = JSObject()
    map.put("ingested", ingested)
    map.put("width", prepared.width)
    map.put("height", prepared.height)
    if (uri != null) map.put("uri", uri) else map.put("uri", JSONObject.NULL)
    return map
  }

  private fun writeLiveJpeg(prepared: Bitmap): String {
    val file = File(context.cacheDir, "frs_live_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { out ->
      prepared.compress(Bitmap.CompressFormat.JPEG, 85, out)
    }
    return "file://${file.absolutePath}"
  }

  private fun applyLiveTransform(src: Bitmap, rotateDegrees: Float, maxEdge: Int): Bitmap {
    var frame = src
    val deg = rotateDegrees % 360f
    if (kotlin.math.abs(deg) > 0.01f) {
      val matrix = android.graphics.Matrix().apply { postRotate(deg) }
      val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
      if (rotated !== frame && frame !== src) frame.recycle()
      frame = rotated
    }
    return scaleMax(frame, maxEdge)
  }

  private fun scaleMax(src: Bitmap, maxEdge: Int): Bitmap {
    val w = src.width
    val h = src.height
    val edge = maxOf(w, h)
    if (edge <= maxEdge) return src
    val scale = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
  }

  companion object {
    /** The app's own background, so an opaque WebView is not a black rectangle. */
    private const val APP_BACKGROUND = 0xFF1C1B1F.toInt()

    const val EVENT_VIDEO_WORKER = "FaceRecognitionVideoWorkerEvent"
  }
}
