# DewarpNet 安卓实验

分支：`codex/dewarpnet-trial`。在现有未提交的 UVDoc 改动上增加 DewarpNet，不替换 UVDoc。

打开 App，勾选“曲面矫正”，选择 DewarpNet 或 UVDoc，然后拍照或从相册选择原图。
照片先进入四角调整页，之后执行梯形矫正和曲面矫正，并显示可放大的预览。
预览页可切换模型、关闭曲面矫正、返回调整或重拍重选。用户确认后才进入 OCR，
主界面直接读取已经确认的图片，不会再次矫正。默认模型为 DewarpNet。
完整流程见 [照片预览与确认](photo-review.md)。

## 实现与模型

- DewarpNet 使用 LiteRT 1.4.0 的 Interpreter，CPU/XNNPACK，4 线程，不需要 Google Play services。
- 离线模型约 189 MB，随 APK 打包。相比原方案增加模型与 LiteRT 运行库的体积。
- 使用社区转换版，来源、固定版本、SHA256 和 MIT 许可随 App 放在 `assets/dewarpnet/`。
- 输入是 256×256 BGR；输出为 128×128 XY 反向映射。先做 3×3 均值平滑，再放大映射并双线性采样原图。
- 矫正保持原图尺寸；OCR 在矫正后的图片上运行。每次释放矫正模型后再加载 OCR，避免同时保留两个大模型。
- `runs/` 保存 `original.jpg`、`photo.jpg`、`ocr.json`，后者含 `flatten_backend` 和 `flatten_ms`。
- 模型文件被 Git 忽略，重新获取：`python tools/fetch_dewarpnet.py`。下载固定版本并验证 SHA256。

模型来源：https://huggingface.co/litert-community/DewarpNet-LiteRT

原始项目：https://github.com/cvlab-stonybrook/DewarpNet

## 实测（2026-11-02，悉尼时间）

同一张本地折皱小票 `samples/run_20261102-022905.jpg`，最长边缩放至 1600，
Small OCR、自动语言。每种方案各运行一次，不是完整准确率或稳定性能评测。
矫正耗时含模型初始化和图片映射；OCR 耗时不含 OCR 模型加载。测试顺序固定，存在缓存和温度影响。

| 设备 | 方案 | 矫正耗时 | OCR 耗时 | 总额 | 菜品数 | 求和对得上 |
| --- | --- | --- | --- | --- | --- | --- |
| 小米真机 25098PN5AC | 不矫正 | 0 ms | 2344 ms | 108.50 | 5 | 是 |
| 小米真机 25098PN5AC | UVDoc | 635 ms | 1348 ms | 108.50 | 5 | 是 |
| 小米真机 25098PN5AC | DewarpNet | 1141 ms | 1872 ms | 108.50 | 5 | 是 |
| x86_64 模拟器 | 不矫正 | 0 ms | 1522 ms | 108.50 | 5 | 是 |
| x86_64 模拟器 | UVDoc | 474 ms | 1074 ms | 108.50 | 5 | 是 |
| x86_64 模拟器 | DewarpNet | 1098 ms | 932 ms | 108.50 | 5 | 是 |

本张样本没有显示 DewarpNet 的识别收益，真机 CPU 矫正比 UVDoc 慢。
已查看模拟器输出图：DewarpNet 对左侧边缘有裁切，部分菜品名称首字缺失，不能据此推荐替换 UVDoc。
折痕导致的信息缺失和拍摄模糊也不会因几何矫正而恢复。

原始测试报告和 PNG 位于本机被忽略的 `logs/dewarpnet-emulator.*`、`logs/uvdoc-emulator.png`，
以及 `logs/dewarpnet-phone.*`；含私人小票，不作为发布资源。

## 验证与复跑

已完成 Debug APK、设备测试 APK 构建，17 项 JVM 单元测试通过（4 项映射测试、13 项既有解析测试）。
模拟器及小米真机都运行了真实模型和 OCR；模拟器界面已查看，默认 DewarpNet 控件显示正常。

```powershell
cd android-bridge
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest -PsmokeRunner=local.ocrbridge.DewarpSmoke
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> push <receipt.jpg> /sdcard/Android/data/local.ocrbridge/files/smoke.jpg
adb -s <serial> shell am instrument -w local.ocrbridge.test/local.ocrbridge.DewarpSmoke
```

测试固定比较三种方案，最长边 1600。App 正常导入最长边为 2400，实际速度会有差异。
设备端输出为 App 外部文件目录下的 `smoke-report.json` 和 `smoke-{none,uvdoc,dewarpnet}.png`。
测试失败时检查输出中的 `error`；自定义测试 runner 成功返回 `INSTRUMENTATION_CODE: -1`。
