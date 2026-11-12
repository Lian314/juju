# 聚聚 (JuJu TV) —— 坚果 N1 Air 第四代 (Android 13 · 2GB RAM) 专享极速版

> 完整的系统架构设计与核心算法白皮书请参阅：[DOCS_TECHNICAL_ARCHITECTURE.md](file:///D:/ccode/duanjuapp/juju/DOCS_TECHNICAL_ARCHITECTURE.md)

## 一、 核心设计亮点
1. **专为 2GB 内存 Android 13 投影/电视打造的极简架构**：
   - 摒弃原版「Flutter Engine + Go C-Shared + Android JVM + libmpv」三层运行时叠加架构（原版未压缩 `.so` 达 70.15 MB，运行内存高达 320~420 MB）；
   - 采用**单进程纯原生 Kotlin + 硬件加速 `RecyclerView` + 原生 `SurfaceView` Hardware Overlay 零拷贝直出**，冷启动内存仅 **~52 MB**，1080P 硬解播放态内存仅 **~88~110 MB**，安装包体积仅 **~3 MB**。
2. **核心黑科技 100% 复刻与增强**：
   - **多站源聚合与协议签名**：内置红果短剧（国密 SM3 + AESv3 + Gorgon/Helios/Argus/Ladon 签名 + Protobuf 设备注册重置）、黄剧、黄豆引擎与 AliDNS DoH 防污染解析；
   - **MP4 CENC AES-CTR 零拷贝原地实时解密 (`CencMp4Decrypter`)**：递归解析 `moov/tenc/moof/senc/trun/mdat`，支持任意 HTTP `206 Partial Content` Range 随机拖动与 64KB 环形 `ByteBufferPool` 零 GC 原地解密；
   - **下一集无缝预加载缓存 (`EpisodePreloadManager`)**：播放至 65% 自动预缓存下一集前 1.5MB 到本地磁盘，严格维持 `keepWindow(2)` 滑动窗口与 `sweepOrphans()` 孤儿清理；
   - **内置局域网手机扫码遥控与配网服务 (`LanRemoteServer`)**：暴露 42 个 `/api/*` 接口，内嵌《聚聚·手机遥控》与《聚聚·WebDAV配网》两套完整 Web 应用，完整包含 `_pauseAware()` 锁屏防 `CLOSE_WAIT` 耗尽、轨道严格串行拉取与明文 HTTP 剪贴板降级；
   - **去中心化自救引擎 (`CustomSourceEngine`)**：彻底去除原版 4 位更新口令门禁与第三方 Worker 依赖，支持通过手机扫码直接注入自定义 JSON、苹果 MacCMS V10 VOD 接口或 M3U8 剧单，永不失联！

## 二、 编译、测试与安装命令

### 1. 运行全量单元测试套件
```powershell
cd D:\ccode\duanjuapp\juju
gradle testDebugUnitTest
```

### 2. 编译可安装 APK
```powershell
cd D:\ccode\duanjuapp\juju
gradle assembleDebug
```
生成的 APK 路径：`D:\ccode\duanjuapp\juju\app\build\outputs\apk\debug\app-debug.apk`

### 3. 安装至坚果 N1 Air 第四代
```powershell
adb connect <投影仪IP>:5555
adb install -r D:\ccode\duanjuapp\juju\app\build\outputs\apk\debug\app-debug.apk
```
