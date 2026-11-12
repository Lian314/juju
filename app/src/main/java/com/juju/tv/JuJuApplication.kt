package com.juju.tv

import android.app.Application
import com.juju.tv.memory.ByteBufferPool
import com.juju.tv.memory.MemoryGovernor
import com.juju.tv.model.MemoryWatermark
import com.juju.tv.provider.ProviderRegistry
import com.juju.tv.remote.LanRemoteServer
import com.juju.tv.remote.NsdHelper
import com.juju.tv.storage.JuJuRepository
import com.juju.tv.stream.EpisodePreloadManager
import com.juju.tv.stream.LocalStreamGateway
import com.juju.tv.ui.ImageLoader
import java.io.File

/**
 * 聚聚 (JuJu TV) 全局 Application 单例容器
 *
 * 在启动时完成：
 * 1. 注册 2GB 内存四级水位守护器 [MemoryGovernor] 到系统 `ComponentCallbacks2`；
 * 2. 启动本机 `127.0.0.1` 零拷贝流媒体解密网关 [LocalStreamGateway]；
 * 3. 启动局域网 HTTP 扫码遥控服务端 [LanRemoteServer] 并广播 `_juju-remote._tcp` mDNS。
 */
class JuJuApplication : Application() {

    companion object {
        @Volatile
        lateinit var instance: JuJuApplication
            private set
    }

    lateinit var memoryGovernor: MemoryGovernor
        private set
    lateinit var repository: JuJuRepository
        private set
    lateinit var preloadManager: EpisodePreloadManager
        private set
    lateinit var streamGateway: LocalStreamGateway
        private set
    lateinit var providerRegistry: ProviderRegistry
        private set
    lateinit var lanRemoteServer: LanRemoteServer
        private set
    lateinit var imageLoader: ImageLoader
        private set
    private var nsdHelper: NsdHelper? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        memoryGovernor = MemoryGovernor(ByteBufferPool.shared)
        repository = JuJuRepository(File(filesDir, "juju_store"))
        preloadManager = EpisodePreloadManager(File(cacheDir, "juju_preload"))
        imageLoader = ImageLoader(memoryGovernor)

        memoryGovernor.posterCacheUsedBytesProvider = { imageLoader.currentCacheSizeBytes() }
        memoryGovernor.preloadStatsProvider = { preloadManager.stats() }
        memoryGovernor.onEvictCachesCallback = { watermark ->
            imageLoader.adjustCacheForWatermark(watermark)
            if (watermark == MemoryWatermark.LOW || watermark == MemoryWatermark.CRITICAL) {
                preloadManager.sweepOrphans(maxAgeMs = 30 * 60 * 1000L)
            }
        }
        registerComponentCallbacks(memoryGovernor)

        streamGateway = LocalStreamGateway(port = 0, preloadManager = preloadManager)
        streamGateway.startGateway()

        providerRegistry = ProviderRegistry(repository = repository)
        // 挂载已保存的自定义自救源
        for (src in repository.getCustomSources()) {
            Thread { providerRegistry.customSourceEngine.refreshCustomSource(src) }.start()
        }

        lanRemoteServer = LanRemoteServer(
            port = LanRemoteServer.DEFAULT_PORT,
            repository = repository,
            providerRegistry = providerRegistry,
            memoryGovernor = memoryGovernor
        )
        val boundPort = lanRemoteServer.startServer()

        nsdHelper = NsdHelper(this).also {
            it.registerRemoteService(boundPort, "JuJuTV-N1Air")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        nsdHelper?.unregisterRemoteService()
        lanRemoteServer.stop()
        streamGateway.stop()
    }
}
