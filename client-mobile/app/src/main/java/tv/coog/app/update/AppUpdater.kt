package tv.coog.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.coog.app.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
)

data class UpdateUiState(
    val checking: Boolean = false,
    val installing: Boolean = false,
    val progress: Float? = null,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val currentCode: Int = BuildConfig.VERSION_CODE,
    val available: AvailableUpdate? = null,
    val message: String = "",
    val error: String? = null,
)

sealed interface InstallEvent {
    data object Success : InstallEvent
    data class Failed(val message: String) : InstallEvent
}

class AppUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val downloadHttp = http.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    @Volatile
    private var resumeInstallAfterPermission = false

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()

    /** Call when Settings / the app becomes visible again after the permission screen. */
    suspend fun onAppResumed() {
        if (!resumeInstallAfterPermission) return
        if (!canInstallPackages()) {
            _state.update {
                it.copy(
                    installing = false,
                    message = "Still blocked. In Android settings, allow Coog to install unknown apps, then press Update again.",
                )
            }
            return
        }
        resumeInstallAfterPermission = false
        installLatest()
    }

    suspend fun check() {
        _state.update {
            it.copy(checking = true, error = null, message = "Checking GitHub for a newer APK…")
        }
        try {
            val latest = fetchLatest()
            val newer = latest != null && latest.versionCode > BuildConfig.VERSION_CODE
            _state.update {
                it.copy(
                    checking = false,
                    available = if (newer) latest else null,
                    message = when {
                        latest == null -> "No GitHub release APK found yet."
                        newer -> "Version ${latest.versionName} is ready to install."
                        else -> "You're on the latest GitHub release."
                    },
                    error = null,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            _state.update {
                it.copy(
                    checking = false,
                    message = "",
                    error = e.message ?: "Could not reach GitHub Releases",
                )
            }
        }
    }

    suspend fun installLatest() {
        val update = _state.value.available ?: run {
            check()
            _state.value.available
        } ?: return
        if (_state.value.installing) return
        _state.update {
            it.copy(installing = true, error = null, progress = 0f, message = "Downloading ${update.apkName}…")
        }
        try {
            if (!canInstallPackages()) {
                resumeInstallAfterPermission = true
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${appContext.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                _state.update {
                    it.copy(
                        installing = false,
                        progress = null,
                        error = null,
                        message = "Allow Coog to install unknown apps, then return here — the update continues automatically.",
                    )
                }
                return
            }
            val apk = download(update)
            _state.update {
                it.copy(
                    progress = 1f,
                    message = "Installing… If Android shows a confirm screen, press Install / OK once.",
                )
            }
            val event = coroutineScope {
                val pending = async { installEvents.first() }
                ApkInstaller.install(appContext, apk)
                withTimeout(5 * 60 * 1000L) { pending.await() }
            }
            when (event) {
                InstallEvent.Success -> _state.update {
                    it.copy(installing = false, progress = null, message = "Updated. Coog will restart.")
                }
                is InstallEvent.Failed -> throw IllegalStateException(event.message)
            }
        } catch (e: TimeoutCancellationException) {
            _state.update {
                it.copy(
                    installing = false,
                    progress = null,
                    message = "",
                    error = "Install timed out. Confirm the Android prompt (or allow unknown apps for Coog), then try Update again.",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            _state.update {
                it.copy(
                    installing = false,
                    progress = null,
                    message = "",
                    error = e.message ?: "Update failed",
                )
            }
        }
    }

    private suspend fun fetchLatest(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val body = getText(
            "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest",
            accept = "application/vnd.github+json",
            allowMissing = true,
        ) ?: return@withContext null
        val release = json.decodeFromString(GithubRelease.serializer(), body)
        val manifestAsset = release.assets.find { it.name.equals("app-update.json", ignoreCase = true) }
        val manifest = if (manifestAsset != null) {
            val manifestBody = getText(manifestAsset.browserDownloadUrl, accept = "application/json")
                ?: return@withContext null
            json.decodeFromString(AppUpdateManifest.serializer(), manifestBody)
        } else {
            null
        }
        val apkAsset = when {
            manifest != null && manifest.apk.isNotBlank() ->
                release.assets.find { it.name == manifest.apk }
            else -> null
        } ?: release.assets.find { apkFileRegex.matches(it.name) }
            ?: release.assets.find { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        val fromName = apkFileRegex.matchEntire(apkAsset.name)
        val versionCode = manifest?.versionCode?.takeIf { it > 0 }
            ?: fromName?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0
        val versionName = manifest?.versionName?.takeIf { it.isNotBlank() }
            ?: fromName?.groupValues?.getOrNull(2)
            ?: release.tagName.trimStart('v')
        if (versionCode <= 0 && !tagNewer(release.tagName, BuildConfig.VERSION_NAME)) {
            return@withContext null
        }
        val resolvedCode = if (versionCode > 0) versionCode else BuildConfig.VERSION_CODE + 1
        AvailableUpdate(
            versionCode = resolvedCode,
            versionName = versionName,
            tag = release.tagName,
            apkName = apkAsset.name,
            apkUrl = apkAsset.browserDownloadUrl,
            apkSize = apkAsset.size,
        )
    }

    private suspend fun download(update: AvailableUpdate): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "coog-tv.apk")
        if (dest.exists()) dest.delete()
        val req = request(update.apkUrl, accept = "application/octet-stream").build()
        downloadHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Download failed (${resp.code})")
            }
            val body = resp.body ?: throw IllegalStateException("Empty APK response")
            val total = body.contentLength().takeIf { it > 0 } ?: update.apkSize
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val p = (copied.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
                            _state.update { it.copy(progress = p, message = "Downloading ${pct(p)}…") }
                        }
                    }
                    output.flush()
                }
            }
        }
        if (dest.length() < 1024L) {
            throw IllegalStateException("Downloaded APK is too small")
        }
        dest
    }

    private fun getText(url: String, accept: String, allowMissing: Boolean = false): String? {
        val req = request(url, accept).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (allowMissing && resp.code == 404) return null
            if (!resp.isSuccessful) {
                throw IllegalStateException("GitHub returned ${resp.code}")
            }
            return text
        }
    }

    private fun request(url: String, accept: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "CoogTV/${BuildConfig.VERSION_NAME} (+https://github.com/${BuildConfig.GITHUB_REPO})")
            .header("X-GitHub-Api-Version", "2022-11-28")

    companion object {
        private const val TAG = "CoogUpdate"
        val installEvents = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
        private val apkFileRegex = Regex("""coog-tv-vc(\d+)-(.+)\.apk""", RegexOption.IGNORE_CASE)

        fun tagNewer(tag: String, currentName: String): Boolean {
            val a = tag.trim().trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
            val b = currentName.split('.').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val av = a.getOrElse(i) { 0 }
                val bv = b.getOrElse(i) { 0 }
                if (av != bv) return av > bv
            }
            return false
        }

        private fun pct(progress: Float): String = "${(progress * 100).toInt()}%"
    }
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

@Serializable
private data class AppUpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apk: String = "",
)
