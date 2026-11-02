# Android Studio 真机运行等待排查

2026-11-02，时区 Australia/Sydney。

本次等待发生在安装阶段。Android Studio 完成构建后，增量部署出现 `CANNOT_GENERATE_DELTA`，随后改为通过无线 ADB 传输整个 528.30 MB 的安装包。

已从 Android Studio 日志和手机安装记录交叉确认：

- 20:36:31 开始完整安装。
- 20:40:23 安装完成，共 232 秒，其中传输约 231 秒。
- 20:40:24 Android Studio 发出启动 MainActivity 的指令，新进程 PID 为 22587；后续只读状态中也能看到该 Activity。
- 本次读取的崩溃日志没有发现 App 的崩溃记录。

安装包中同时包含手机的 arm64-v8a 库和模拟器的 x86_64 库，后者约占 104.1 MB。另外，DewarpNet 模型约占 188.9 MB，OCR 及 UVDoc 模型合计约占 165.0 MB。

已修改 [app/build.gradle.kts](../android-bridge/app/build.gradle.kts)，按手机与模拟器的架构分别生成 APK。Android Studio 可依据设备选择对应包；这是 Android 官方支持的 [按 ABI 分包方式](https://developer.android.com/build/configure-apk-splits)。

电脑端构建已通过，并核查 APK 内容与输出清单：

| 安装包 | 大小（十进制 MB） | 原生库架构 |
| --- | --- | --- |
| 原通用包 | 528.30 | arm64-v8a、x86_64 |
| 新手机包 | 424.17 | arm64-v8a |
| 新模拟器包 | 464.83 | x86_64 |

手机包减少约 104 MB，约 20%；识别及矫正模型均保留。新包依然较大，无线全量安装仍会需要时间，实际耗时取决于连接速度。

下次在 Android Studio 同步项目后正常运行即可。开发时使用 USB 数据连接可以避免当前无线传输带来的长时间等待；安装过程中反复取消重试会重复传包。

手机安装包位于 `android-bridge/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。本轮只在电脑构建并读取手机状态，没有主动安装、启动或点击手机；20:40 的安装及启动来自原先已在运行的 Android Studio 部署任务。新分包尚未进行手机安装验证。
