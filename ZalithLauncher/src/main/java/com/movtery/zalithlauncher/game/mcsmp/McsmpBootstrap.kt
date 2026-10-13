package com.movtery.zalithlauncher.game.mcsmp

import android.content.Context
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricAPIVersions
import com.movtery.zalithlauncher.game.addons.modloader.fabriclike.fabric.FabricVersions
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredModrinthSource
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredPlatformSearcher
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.models.getPrimary
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.ui.screens.content.download.assets.elements.initAllGeneric
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.downloadFileFromSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MCSMP TR: ilk açılışta Minecraft 1.21.11 + Fabric + Fabric API + Simple Voice Chat kurar,
 * sonra oyunu doğrudan mcsmp.tr sunucusuna sokar.
 */
object McsmpBootstrap {
    const val SERVER_ADDRESS = "mcsmp.tr"
    const val GAME_VERSION = "1.21.11"
    const val VERSION_NAME = "MCSMP-TR"

    private const val VOICECHAT_PROJECT = "simple-voice-chat"
    private const val TAG = "McsmpBootstrap"

    sealed interface State {
        data object Idle : State
        data class Working(val message: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Bu açılışta kurulum yapıldıysa, kurulum bitince oyun bir kez otomatik başlatılır */
    @Volatile var justInstalled = false
        private set

    fun findVersion(): Version? =
        VersionsManager.versions.value.find { it.getVersionName() == VERSION_NAME && it.isValid() }

    private fun modsDir(): File {
        val dir = findVersion()?.getGameDir() ?: File(getVersionsHome(), VERSION_NAME)
        return File(dir, "mods")
    }

    private fun versionInstalled() = VersionsManager.isVersionExists(VERSION_NAME, checkJson = true)

    private fun voiceChatInstalled() =
        modsDir().listFiles()?.any { it.name.contains("voicechat", ignoreCase = true) } == true

    private fun work(msg: String) { _state.value = State.Working(msg) }

    fun start(context: Context) {
        if (_state.value is State.Working) return
        val appContext = context.applicationContext
        work("Hazırlanıyor...")
        scope.launch {
            try {
                if (!versionInstalled()) {
                    justInstalled = true
                    installGame(appContext)
                    VersionsManager.refresh("McsmpBootstrap", VERSION_NAME)
                }
                waitForVersion()
                if (!voiceChatInstalled()) {
                    justInstalled = true
                    installVoiceChat()
                }
                _state.value = State.Ready
            } catch (th: Throwable) {
                Logger.error(TAG, "Setup failed", th)
                _state.value = State.Failed(th.message ?: th.javaClass.simpleName)
            }
        }
    }

    private suspend fun waitForVersion() {
        work("Sürüm doğrulanıyor...")
        withTimeout(60_000) {
            VersionsManager.versions.first { list ->
                list.any { it.getVersionName() == VERSION_NAME && it.isValid() }
            }
        }
    }

    private suspend fun installGame(context: Context) {
        work("Fabric sürümleri alınıyor...")
        val loaders = FabricVersions.fetchFabricLoaderList(GAME_VERSION)
        val fabric = loaders?.firstOrNull { it.stable } ?: loaders?.firstOrNull()
            ?: error("Fabric loader bulunamadı ($GAME_VERSION)")
        val api = FabricAPIVersions.fetchVersionList(GAME_VERSION)?.firstOrNull()
            ?: error("Fabric API bulunamadı ($GAME_VERSION)")

        val info = GameDownloadInfo(
            gameVersion = GAME_VERSION,
            customVersionName = VERSION_NAME,
            fabric = fabric,
            fabricAPI = api
        )

        work("Minecraft $GAME_VERSION + Fabric kuruluyor... (birkaç dakika sürebilir)")
        suspendCancellableCoroutine { cont ->
            val installer = GameInstaller(context, info, scope)
            installer.installGame(
                isRunning = { if (cont.isActive) cont.resumeWithException(IllegalStateException("Kurulum zaten sürüyor")) },
                onInstalled = { if (cont.isActive) cont.resume(Unit) },
                onError = { if (cont.isActive) cont.resumeWithException(it) },
                onGameAlreadyInstalled = { if (cont.isActive) cont.resume(Unit) }
            )
            cont.invokeOnCancellation { installer.cancelInstall() }
        }
    }

    private suspend fun installVoiceChat() {
        work("Simple Voice Chat indiriliyor...")
        val versions = mirroredPlatformSearcher(searchers = mirroredModrinthSource()) { searcher ->
            searcher.getVersions(projectID = VOICECHAT_PROJECT)
                .initAllGeneric(currentProjectId = VOICECHAT_PROJECT)
        }
        val target = versions
            .filter { it.gameVersions.contains(GAME_VERSION) && it.loaders.contains("fabric") }
            .maxByOrNull { it.datePublished }
            ?: error("Simple Voice Chat, $GAME_VERSION Fabric için bulunamadı")
        val file = target.files.getPrimary() ?: error("Voice Chat dosyası bulunamadı")

        val dir = modsDir().apply { mkdirs() }
        downloadFileFromSources(listOf(file.url), File(dir, file.fileName), file.hashes.sha1)
    }
}
