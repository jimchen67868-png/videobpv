package com.example.aivideo

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Default Space (change it in the app). Any public Gradio text-to-video Space should work.
private const val DEFAULT_SPACE = "zerogpu-aoti/wan2-2-fp8da-aoti"

class VideoViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("prefs", 0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES) // queues + generation can be slow
        .build()

    var space by mutableStateOf(prefs.getString("space", DEFAULT_SPACE) ?: DEFAULT_SPACE)
    var hfToken by mutableStateOf(prefs.getString("hf", "") ?: "")
    var prompt by mutableStateOf("")
    var status by mutableStateOf("")
    var videoUrl by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    fun generate() {
        if (prompt.isBlank() || space.isBlank()) { status = "Enter a Space and a prompt."; return }
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

    private fun runJob(): String {
        val base = baseUrl()

        // 1. Discover the Space's API and pick the endpoint that returns a video.
        status = "Reading Space API…"
        val info = client.newCall(req("$base/gradio_api/info").build()).execute().use {
            if (!it.isSuccessful) error("Space unreachable (HTTP ${it.code}). It may be asleep, private or removed.")
            JSONObject(it.body!!.string())
        }
        val eps = info.getJSONObject("named_endpoints")
        var chosen: String? = null
        for (k in eps.keys()) {
            val rets = eps.getJSONObject(k).optJSONArray("returns") ?: continue
            for (i in 0 until rets.length())
                if (rets.getJSONObject(i).optString("component") == "Video") chosen = chosen ?: k
        }
        val name = chosen ?: eps.keys().asSequence().firstOrNull() ?: error("Space has no API endpoints")

        // 2. Fill parameters: prompt into the first textbox, defaults for the rest.
        val params = eps.getJSONObject(name).getJSONArray("parameters")
        val data = JSONArray()
        var promptUsed = false
        for (i in 0 until params.length()) {
            val p = params.getJSONObject(i)
            when {
                !promptUsed && p.optString("component") == "Textbox" -> { data.put(prompt); promptUsed = true }
                p.optBoolean("parameter_has_default") -> data.put(p.opt("parameter_default") ?: JSONObject.NULL)
                else -> data.put(JSONObject.NULL)
            }
        }

        // 3. Submit the job.
        status = "Submitting…"
        val callUrl = "$base/gradio_api/call/${name.trimStart('/')}"
        val body = JSONObject().put("data", data).toString().toRequestBody("application/json".toMediaType())
        val eventId = client.newCall(req(callUrl).post(body).build()).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("HTTP ${it.code}: ${text.take(200)}")
            JSONObject(text).getString("event_id")
        }

        // 4. Read the event stream until the result arrives.
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
        Button(vm::generate, Modifier.fillMaxWidth(), enabled = !vm.loading) {
            Text(if (vm.loading) "Generating…" else "Generate video")
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
