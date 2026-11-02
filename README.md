# 小票 OCR

安卓分账 App 的离线小票识别，代码全部在 `android-bridge/`，语言是 Kotlin。

## 运行

用 Android Studio 打开 `android-bridge/`，选好设备后点 Run。App 独立运行，不需要电脑或网络。

命令行需要先把 `JAVA_HOME` 指向 JDK 21：

```powershell
cd android-bridge
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

## 流程

1. 拍照或从相册选择。
2. u2netp 分割出小票并求四角，失败时退回 OpenCV 找边；可手动微调或重新找边，都没有找到时保留整张照片。
3. 梯形矫正，再用所选的 DewarpNet 或 UVDoc 做曲面矫正。预览页可切换模型、关闭曲面矫正或返回调整四角。
4. 确认后运行 PP-OCRv6。行弯曲或倾斜明显时校平图片并重读一次。
5. 解析：先按金额把菜品区切成块，再细分每块内的菜名、规格和金额。

流程细节见 [docs/photo-review.md](docs/photo-review.md)。

## 代码

| 文件 | 作用 |
| --- | --- |
| `PaperBoundary.kt` | u2netp 分割和四角求解 |
| `DocumentBoundaryDetector.kt` | OpenCV 找边，作为备选 |
| `Perspective.kt` | 梯形矫正 |
| `Dewarper.kt`、`DewarpNet.kt`、`BackwardMap.kt` | 曲面矫正 |
| `PpOcr.kt` | ONNX Runtime 上的 PP-OCRv6 检测和识别 |
| `Language.kt` | 自动 / 英文 / 中文；自动模式在中日韩字符占比低于 15% 时按英文处理 |
| `TextLevel.kt` | 用 OCR 框拟合行斜率场并校平 |
| `ReceiptParser.kt` | 解析规则，读取菜品、合计、店名和日期 |
| `RunLog.kt` | 保存每次识别的照片、OCR 结果、四角和校平信息 |

## 模型

模型文件被 Git 忽略，放在 `android-bridge/app/src/main/assets/`：

| 目录 | 文件 | 来源 |
| --- | --- | --- |
| `u2netp/` | `u2netp.onnx` | 见该目录 `README.txt` |
| `dewarpnet/` | `dewarp.tflite` | 见该目录 `README.txt` |
| `ppocr/` | PP-OCRv6 和 UVDoc 的 ONNX | 仓库里没有下载说明，换机器时要从现有设备或项目副本复制 |

下载后核对各目录 `README.txt` 里的 SHA256。

## 测试

```powershell
cd android-bridge
./gradlew.bat :app:testDebugUnitTest
```

`ReceiptParserTest` 用 `app/src/test/resources/receipts.json` 防止解析退步。该文件含样本 OCR 结果，被 Git 忽略，只存在本机；
它是移除 Python 前生成的，解析规则有意改变时需要手动更新。

手机全流程测试会比较两种找边，并跑完矫正、OCR 和解析：

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PsmokeRunner=local.ocrbridge.BoundarySmoke
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w local.ocrbridge.test/local.ocrbridge.BoundarySmoke
```

结果在 App 外部文件目录的 `boundary/`。拉回识别记录：

```bash
adb -s <serial> pull /sdcard/Android/data/local.ocrbridge/files/runs samples/runs
```

## 历史

桌面 Python 比较台已移除，需要时可从提交 `442abde` 恢复。`bench_*.csv` 保留了当时的跑分。
选型结论：PP-OCRv6 Small 的准确率和体积最均衡，Medium 准确率略高但占用内存多，ML Kit 拉丁文识别器读不了中日韩小票。

## 样本

- `samples/` 含私人小票和 CC BY-NC 授权的样本，被 Git 忽略，不要随 App 分发。
- 来源和授权见 `samples/sources.json`。
