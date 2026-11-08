# SlipSplit

[English](README.md) | **中文**

版本 1.2 · [更新日志](CHANGELOG.zh.md) · [下载](https://github.com/albzO/SlipSplit/releases/latest)

SlipSplit 是一款按人分账的安卓 App。拍一张小票，App 在手机上读出每一项商品，你点一下就把商品记到某个人名下。照片不上传：找边、拉平、文字识别和解析全部离线完成。

首先面向澳洲小票，包括亚洲超市的中英双语小票。

## 功能

- 拍照或从相册选图，自动找小票四角，可手动微调。
- 识别前拉平纸张（UVDoc）并校平文字行。
- 实时显示识别进度：文字框一检测到就出现，每读完一行对应的框就变成实色。
- 不论小票怎么排版，都能把每项商品分成名称、明细和金额。
- 用小票总额纠正读错的数字，并让用户核实。
- 分账：点商品记到当前选中的人名下；多人选同一项就平分；没人选的商品归最后一个人。
- 折扣、附加费、小费和取整按同一比例分摊。
- 汇总可以复制成文字，或分享成图片。
- 分账历史：在首页上滑查看，点一条可以继续编辑，长按删除。保存第一条记录后，首页才会出现箭头和提示。
- 中英文界面，默认跟随系统语言，也可以在设置里切换。

## 安装

从 [Releases](https://github.com/albzO/SlipSplit/releases/latest) 下载 `SlipSplit-<版本>-arm64-v8a.apk`，在手机上打开安装。需要 Android 7.0 及以上，约 73MB；x86_64 版用于模拟器。Release 包目前用调试密钥签名，安装时系统可能会提示风险。

## 构建和运行

用 Android Studio 打开 `SlipSplit/`，点 Run。App 独立运行，不需要电脑或网络。

命令行需要先把 `JAVA_HOME` 指向 JDK 21：

```powershell
cd SlipSplit
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Debug APK 按架构拆分，手机安装 `app-arm64-v8a-debug.apk`。

## 技术栈

| 层 | 选择 |
| --- | --- |
| 语言 | Kotlin |
| 界面 | Jetpack Compose、Material 3、Navigation Compose，单 Activity |
| 状态 | 一个 `AndroidViewModel`；OCR 在单个工作线程上运行，模型只加载一次 |
| 推理 | ONNX Runtime for Android，只含模型所用算子的精简构建（`SlipSplit/ort/`） |
| 图像处理 | Android `Bitmap` 和 `Canvas`，加上一小段 Kotlin 几何代码求小票轮廓 |
| 模型 | U²-Netp（小票分割）、UVDoc（拉平）、PP-OCR 检测和识别（Tiny / Small 两档） |

## 识别架构

```mermaid
flowchart TD
    A[照片] --> B[找小票<br/>U²-Netp 分割]
    B --> C[用户调整四角]
    C --> D[梯形矫正<br/>短边外扩 4%]
    D --> E[UVDoc 拉平]
    E --> F[PP-OCR 检测<br/>一次找出全部文字框]
    F --> G[PP-OCR 识别<br/>逐行读取并报告进度]
    G --> H{行歪斜？}
    H -- 是 --> I[拟合行斜率场校平<br/>再读一遍]
    H -- 否 --> J[解析小票]
    I --> J
    J --> K{商品合计<br/>等于总额？}
    K -- 否 --> L[用第二选择<br/>重试不确定的数字]
    K -- 是 --> M[分账页]
    L --> N[用户在放大的照片上<br/>核实纠正]
    N --> M
```

### 步骤

1. **找小票。** U²-Netp 把小票当作画面主体分割出来，App 按分割结果求四个角。App 取分割结果里最大的一块，把它的凸轮廓化简成几个候选四边形，选和分割区域重合度最高的那个；求不出四边形时保留整张照片。用户可以拖动四角。
2. **矫正。** 按四角做梯形矫正，四周略微外扩以免切掉纸边，再用 UVDoc 去掉纸张弯曲。输出尺寸不变，后续文字框可以直接对上。
3. **检测。** PP-OCR 检测在拉平后的照片上找出所有文字框，检测一结束界面就把它们全部画出来。
4. **识别。** 每个框被裁出、摆正后识别。识别器每读完一行就报告一次，所以进度条数的是真实行数。对每个数字，它还会记下可能性第二高的数字和概率。
5. **语言。** *自动* 先按全部字符读取，中日韩字符少于字母的 15% 时改用只含英文的字符集。*中文优先* 保留全部字符，中英混合的小票需要选这个。
6. **校平。** 如果文字框显示行是弯的或斜的，就按这些框拟合一个平滑的斜率场，把照片校平后再读一遍。默认采用第二遍的结果，除非它丢掉了第一遍已经对上的总额。

## 数据处理

`ReceiptParser.kt` 把 OCR 行变成商品和合计。规则全部基于结构，不针对任何一张小票。

1. **行。** 文字框换算到行坐标。照片里同一张小票的打印行交于同一个消失点，所以一次拟合就能同时修正倾斜和透视。同一打印行上的框从左到右拼接。
2. **金额列。** 靠右对齐的金额组成价格列。行中间的金额，比如 `Normal Price: $3.48` 或单价，是说明，不是价格。如果有 `Description  Qty  Amount` 这样的表头，就按表头确定哪一列是金额。
3. **字段。** 按标签识别合计类字段：小计、总额、税和已含 GST、服务费和附加费、折扣、取整和付款。`Item Count` 这类件数行表示商品列表结束。
4. **商品块。** 第一个有金额的行决定整张小票的排版：
   - *价格前置*：商品名和金额在同一行，每个金额开始一项商品，它下面到下一个金额之前的行都是这项商品的明细。
   - *价格后置*：商品名印在上面，每个金额结束它上面的那些行。
   - 无论哪种排版，`1.234 kg NET @ $3.99/kg` 这类重量或数量行都给上面那个商品名定价。
5. **单品折扣。** 紧跟在商品下面的负金额是这项商品的折扣。用小票总额判断商品金额是否已经扣过折扣，保证折扣不会算两次。
6. **数字纠正。** 商品合计和小计或总额差了哪怕一分钱时，解析会尝试识别器给出的第二选择数字：先改最少的位数，最多三位。只有合计精确对上，并且这种改法的可能性至少是同样位数其他改法的两倍，才会采用。App 随后把每处纠正标在照片上让用户核实。
7. **对账。** 如果商品合计仍然超过总额，就找出最少的几行，去掉后合计正好对上，把它们当作误读移到一边，比如被读成商品的小计。
8. **表头。** 店名优先取印出来的公司全称，否则取表头里字最大的那段文字。日期和时间取第一个合理的日期。

### 分账计算

`BillSplit.kt` 全部以分为单位计算：

- 多人选同一项商品时平分，零头给第一个选的人。
- 没人选的商品归名单里最后一个人。
- 整单费用按商品的比例计算：同时读到小计和总额时取两者之差，否则取服务费减折扣。每个人按同一比例增减。
- 按比例换算后剩下的零头分给小数部分最大的人，所以各人金额加起来始终等于小票总额。

## 代码结构

App 代码全部在 `SlipSplit/app/src/main/java/local/ocrbridge/`。

| 文件 | 作用 |
| --- | --- |
| `MainActivity.kt` | Activity、页面导航和切换动画 |
| `AppViewModel.kt` | 一张小票从拍照到分账的状态，以及识别流程 |
| `AppSettings.kt` | 模型、小票语言、界面语言和识别记录开关 |
| `PaperBoundary.kt`、`Outline.kt` | 用 U²-Netp 分割结果求小票四角，纯 Kotlin 实现 |
| `Perspective.kt`、`Dewarper.kt` | 梯形矫正和 UVDoc 拉平 |
| `PpOcr.kt` | ONNX Runtime 上的 PP-OCR 检测和识别，带进度和数字第二选择 |
| `TextLevel.kt` | 行斜率场、校平和第二遍识别 |
| `Language.kt` | 小票语言选择和英文判断 |
| `ReceiptParser.kt` | 从 OCR 行读出商品、合计、店名和日期 |
| `BillSplit.kt` | 以分为单位的分账计算 |
| `History.kt` | 已完成的分账，连同小票照片和 OCR 文字，存在 App 私有目录 |
| `RunLog.kt`、`RunExport.kt` | 手机上的识别记录及其 zip 导出 |
| `ui/` | Compose 界面、小票画布、分享图片和多语言 |

App 图标是 `res/drawable/ic_launcher_foreground.xml` 里的自适应图标：深色底上一张撕成蓝、橙两半的小票。

## 模型

模型文件被 Git 忽略，放在 `SlipSplit/app/src/main/assets/`：

| 目录 | 文件 | 来源 |
| --- | --- | --- |
| `u2netp/` | `u2netp.onnx`、`u2netp.ort` | 见该目录 `README.txt` |
| `ppocr/` | PP-OCR Tiny、Small 和 UVDoc：`det`/`rec` 的 `.onnx` 和 `.ort` | 从现有设备或项目副本复制 |

App 只加载 `.ort`，`.onnx` 是它们的转换来源，不会打进 APK。下载后核对各目录 `README.txt` 里的 SHA256，再运行 `SlipSplit/ort/convert_models.ps1` 生成 App 实际加载的 ORT 格式副本，详见 `SlipSplit/ort/README.md`。停用的 DewarpNet 模型保存在 App 之外的 `models/dewarpnet/`。

## 测试

```powershell
cd SlipSplit
./gradlew.bat :app:testDebugUnitTest
```

- `ReceiptParserTest` 用样本小票的 OCR 结果回放，和记录的结果比对（`app/src/test/resources/receipts.json`，含私人小票，只存在本机），另外还有按规则写的小用例。
- `BillSplitTest` 覆盖平分、最后一个人兜底、整单比例和总额精确相等。
- `RunReplay` 解析从手机拉回的识别记录：把 `REPLAY_RUNS` 设为记录所在目录，它会在那里写出 `replay.txt`。

只有在设置里打开 *保存识别记录* 时才会保存记录，默认关闭。从手机拉回识别记录：

```bash
adb pull /sdcard/Android/data/local.ocrbridge/files/runs logs/phone-runs
```

## 隐私和样本

- 识别和解析都在手机上完成，App 也没有联网权限。识别记录默认关闭；打开后记录只留在手机上，且只能通过设置里的导出带出手机。
- 分账历史（包括小票照片）存在 App 私有目录里，不会被导出。
- `samples/` 含私人小票和 CC BY-NC 授权的样本，被 Git 忽略，不要随 App 分发；来源和授权见 `samples/sources.json`。

## 许可证

SlipSplit 以 [Apache License 2.0](LICENSE) 授权。第三方组件沿用各自的许可证：ONNX Runtime 为 MIT，U²-Netp 和 PP-OCR 为 Apache-2.0，UVDoc 以其项目的许可证为准。模型来源见各 assets 目录的 `README.txt`。

## 历史

桌面 Python 比较台和跑分表已移除，需要时可从提交 `442abde` 恢复。PP-OCR Small 在准确率和体积之间最均衡；Medium 准确率略高，但占用内存多得多，而且同样会读错印淡的数字，所以 App 已不再包含它，模型文件保存在 App 之外的 `models/ppocr-medium/`。ML Kit 的拉丁文识别器读不了中日韩小票。
