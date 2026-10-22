package com.example.aivideo

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// Default Space (change it in the app). Any public Gradio video Space should work.
private const val DEFAULT_SPACE = "zerogpu-aoti/wan2-2-fp8da-aoti"

class VideoViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("prefs", 0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES) // uploads, queues and generation can be slow
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    var space by mutableStateOf(prefs.getString("space", DEFAULT_SPACE) ?: DEFAULT_SPACE)
    var hfToken by mutableStateOf(prefs.getString("hf", "") ?: "")
    var prompt by mutableStateOf("")
    var photoUri by mutableStateOf<Uri?>(null)    // uploaded photo (image input)
    var inVideoUri by mutableStateOf<Uri?>(null)  // uploaded video (video input)
    var status by mutableStateOf("")
    var videoUrl by mutableStateOf<String?>(null) // generated result
    var loading by mutableStateOf(false)

    fun generate() {
        if (space.isBlank() || (prompt.isBlank() && photoUri == null && inVideoUri == null)) {
            status = "Enter a Space and a prompt (or add a photo/video)."; return
        }
        prefs.edit().putString("space", space).putString("hf", hfToken).apply()
        loading = true; videoUrl = null; status = "Connecting…"
        viewModelScope.launch {
            try {
                videoUrl = withContext(Dispatchers.IO) { runJob() }
                status = "Done"
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
            loading = false
        }
    }

    fun download() {
        val url = videoUrl ?: return
        val name = "videobpv_${System.currentTimeMillis()}.mp4"
        val r = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
        if (hfToken.isNotBlank()) r.addRequestHeader("Authorization", "Bearer ${hfToken.trim()}")
        val dm = getApplication<Application>().getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        dm.enqueue(r)
        status = "Saving to Downloads/$name"
    }

    // "owner/space-name" or a full https URL -> https://owner-space-name.hf.space
    private fun baseUrl(): String {
        val s = space.trim().trimEnd('/')
        if (s.startsWith("http")) return s
        return "https://" + s.lowercase().replace("/", "-").replace("_", "-").replace(".", "-") + ".hf.space"
    }

    private fun req(url: String) = Request.Builder().url(url).apply {
        if (hfToken.isNotBlank()) header("Authorization", "Bearer ${hfToken.trim()}")
    }

    // Copy a picked photo/video into app cache, then upload it to the Space.
    private fun uploadUri(base: String, uri: Uri, defaultMime: String): JSONObject {
        val ctx = getApplication<Application>()
        val mime = ctx.contentResolver.getType(uri) ?: defaultMime
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: defaultMime.substringAfter("/")
        val f = File(ctx.cacheDir, "up_${System.nanoTime()}.$ext")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("files", f.name, f.asRequestBody(mime.toMediaType())).build()
            val paths = client.newCall(req("$base/gradio_api/upload").post(form).build()).execute().use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) error("Upload failed (HTTP ${it.code}): ${text.take(150)}")
                JSONArray(text)
            }
            return JSONObject().put("path", paths.getString(0))
                .put("meta", JSONObject().put("_type", "gradio.FileData"))
        } finally { f.delete() }
    }

    private fun runJob(): String {
        val base = baseUrl()
        val wantImage = photoUri != null
        val wantVideo = inVideoUri != null

        // 1. Discover the Space's API and pick a matching endpoint.
        status = "Reading Space API…"
        val info = client.newCall(req("$base/gradio_api/info").build()).execute().use {
            if (!it.isSuccessful) error("Space unreachable (HTTP ${it.code}). It may be asleep, private or removed.")
            JSONObject(it.body!!.string())
        }
        val eps = info.getJSONObject("named_endpoints")
        val keys = eps.keys().asSequence().toList()
        fun inputs(k: String): List<String> {
            val p = eps.getJSONObject(k).getJSONArray("parameters")
            return (0 until p.length()).map { p.getJSONObject(it).optString("component") }
        }
        fun returnsVideo(k: String): Boolean {
            val r = eps.getJSONObject(k).optJSONArray("returns") ?: return false
            return (0 until r.length()).any { r.getJSONObject(it).optString("component") == "Video" }
        }
        val vids = keys.filter { returnsVideo(it) }.ifEmpty { keys }
        val name = if (wantImage || wantVideo) {
            vids.firstOrNull { k ->
                val c = inputs(k)
                (!wantImage || "Image" in c) && (!wantVideo || "Video" in c)
            } ?: error("This Space has no input for your ${if (wantImage) "photo" else "video"}. Pick an image-to-video / video-to-video Space.")
        } else {
            vids.firstOrNull { k -> inputs(k).none { it == "Image" || it == "Video" } }
                ?: vids.firstOrNull() ?: error("Space has no API endpoints")
        }

        // 2. Upload attached photo / video.
        var photoData: JSONObject? = null
        var videoData: JSONObject? = null
        if (wantImage) { status = "Uploading photo…"; photoData = uploadUri(base, photoUri!!, "image/jpeg") }
        if (wantVideo) { status = "Uploading video…"; videoData = uploadUri(base, inVideoUri!!, "video/mp4") }

        // 3. Fill parameters: prompt, photo, video, defaults for the rest.
        val params = eps.getJSONObject(name).getJSONArray("parameters")
        val data = JSONArray()
        var promptUsed = false; var photoUsed = false; var videoUsed = false
        for (i in 0 until params.length()) {
            val p = params.getJSONObject(i)
            val comp = p.optString("component")
            when {
                !promptUsed && comp == "Textbox" -> { data.put(prompt); promptUsed = true }
                !photoUsed && comp == "Image" && photoData != null -> { data.put(photoData); photoUsed = true }
                !videoUsed && comp == "Video" && videoData != null -> { data.put(videoData); videoUsed = true }
                p.optBoolean("parameter_has_default") -> data.put(p.opt("parameter_default") ?: JSONObject.NULL)
                else -> data.put(JSONObject.NULL)
            }
        }

        // 4. Submit the job.
        status = "Submitting…"
        val callUrl = "$base/gradio_api/call/${name.trimStart('/')}"
        val body = JSONObject().put("data", data).toString().toRequestBody("application/json".toMediaType())
        val eventId = client.newCall(req(callUrl).post(body).build()).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("HTTP ${it.code}: ${text.take(200)}")
            JSONObject(text).getString("event_id")
        }

        // 5. Read the event stream until the result arrives.
        client.newCall(req("$callUrl/$eventId").build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val src = r.body!!.source()
            var event = ""
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (line.startsWith("event:")) {
                    event = line.removePrefix("event:").trim()
                    if (event == "generating" || event == "heartbeat") status = "Queued / generating… (can take minutes)"
                } else if (line.startsWith("data:")) {
                    val d = line.removePrefix("data:").trim()
                    if (event == "complete") return findUrl(JSONArray(d), base) ?: error("No video in result")
                    if (event == "error") error("Space error: ${d.take(200)} (quota or queue limit? try an HF token)")
                }
            }
        }
        error("Stream ended without a result")
    }

    private fun findUrl(o: Any?, base: String): String? = when (o) {
        is JSONArray -> (0 until o.length()).firstNotNullOfOrNull { findUrl(o.get(it), base) }
        is JSONObject -> {
            val u = o.optString("url")
            val p = o.optString("path")
            when {
                u.isNotEmpty() && u != "null" -> u
                p.isNotEmpty() && p != "null" -> "$base/gradio_api/file=$p"
                else -> o.keys().asSequence().firstNotNullOfOrNull { findUrl(o.get(it), base) }
            }
        }
        else -> null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { Screen() } } }
    }
}

@Composable
fun Screen(vm: VideoViewModel = viewModel()) {
    val photoPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) vm.photoUri = it }
    val videoPick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) vm.inVideoUri = it }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("videobpv", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            vm.space, { vm.space = it }, Modifier.fillMaxWidth(),
            label = { Text("Hugging Face Space (owner/name)") }, singleLine = true
        )
        OutlinedTextField(
            vm.hfToken, { vm.hfToken = it }, Modifier.fillMaxWidth(),
            label = { Text("HF token (optional, more free quota)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true
        )
        OutlinedTextField(
            vm.prompt, { vm.prompt = it }, Modifier.fillMaxWidth(),
            label = { Text("Describe your video") }, minLines = 3
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                { photoPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Modifier.weight(1f)
            ) { Text(if (vm.photoUri == null) "Add photo" else "Photo ✓") }
            OutlinedButton(
                { videoPick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                Modifier.weight(1f)
            ) { Text(if (vm.inVideoUri == null) "Add video" else "Video ✓") }
        }
        if (vm.photoUri != null || vm.inVideoUri != null) {
            TextButton({ vm.photoUri = null; vm.inVideoUri = null }) { Text("Remove attached files") }
        }
        Button(vm::generate, Modifier.fillMaxWidth(), enabled = !vm.loading) {
            Text(if (vm.loading) "Working…" else "Generate video")
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.status.isNotEmpty()) Text(vm.status)
        vm.videoUrl?.let {
            VideoPlayer(it)
            OutlinedButton(vm::download, Modifier.fillMaxWidth()) { Text("Download to Downloads") }
        }
    }
}

@Composable
fun VideoPlayer(url: String) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare(); player.playWhenReady = true
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    )
}
