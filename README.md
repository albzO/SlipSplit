# 小票 OCR 对比

为安卓分账 App 选 OCR 方案：同一批小票、同一套解析规则，横向比较 6 个模型。

## 启动

```bash
.venv/Scripts/python -m uvicorn main:app --app-dir app --port 8700
```

打开 http://localhost:8700 。支持上传自己的小票；模型下载到 `models/`，不占用户目录。

批量跑分（服务需已启动）：

```bash
python benchmark.py ppocr_tiny,ppocr_small 8
```

## 结构

| 路径 | 作用 |
| --- | --- |
| `app/main.py` | FastAPI 接口 |
| `app/engines.py` | 模型注册表和 worker 进程管理 |
| `app/receipt.py` | 统一小票结构、规则解析、评分 |
| `workers/` | 模型推理，Paddle 和 torch 分属两个 venv |
| `static/index.html` | 前端 |
| `samples/` | 样本、`ground_truth.json` 标注、`sources.json` 来源和授权 |
| `android-bridge/` | 安卓 App：拍照或选图，在端上识别并显示结果 |

## 安卓 App

用 Android Studio 打开 `android-bridge/`，选好设备后点 Run。App 独立运行，不需要电脑或网络。

拍照或从相册选择后，u2netp 分割出小票并求四角，失败时退回 OpenCV 找边；可手动微调或重新找边。
都没有找到时保留整张照片。模型在 `assets/u2netp/`，被 Git 忽略，来源和 SHA256 见同目录 `README.txt`。确认四角后做梯形矫正，再运行所选的曲面矫正模型。
拖动四角时显示避开手指的放大镜。预览先显示梯形矫正图，再执行曲面矫正。
预览页可双指放大，切换 DewarpNet / UVDoc、关闭曲面矫正或返回调整四角；不满意可以重选、重拍。
确认“使用这张并识别”后才回主界面运行 OCR，不会再次矫正图片。相册仍通过系统选择器授权单张照片，
不需要读取整个相册。该流程不依赖 Google 文档扫描器。
确认时直接在内存中交接已矫正图片，不额外保存临时 PNG 再读取；识别结果显示后才后台保存诊断记录。

安卓端不再嵌入 Python，也不需要 Python 3.10 打包。自动找边使用 `org.opencv:opencv:4.12.0`，
离线运行，当前支持 arm64-v8a / x86_64。流程说明见 [docs/photo-review.md](docs/photo-review.md)。

- `PpOcr.kt`：ONNX Runtime 上的 PP-OCRv6 检测和识别。
- `Language.kt`：自动 / 英文 / 中文。英文模式在解码时只允许英文字符；自动模式在中日韩字符占比低于 15% 时按英文处理。
- `ReceiptParser.kt`：解析规则的主版本，先按金额切成菜品块，再细分每块内的菜名、规格和金额。
  新规则先写这里。`app/src/test/resources/receipts.json` 是样本上的历史 Python 输出，`ReceiptParserTest` 用它防止退步。
- `TextLevel.kt`：用 OCR 框拟合行斜率场。行弯曲或倾斜明显时校平图片并重读一次。

## 测试后处理

OCR 结果存盘后，改规则不用再跑模型：

```bash
python tools/dump_ocr.py ppocr_small
python tools/evaluate.py ppocr_small
```

`evaluate.py` 逐张列出漏掉和多出的菜品；`--save` 记录基线，下次运行时显示哪些小票变了；
`--fixture` 刷新 Kotlin 对照测试。改 `app/receipt.py` 后记得同步 `ReceiptParser.kt`，再用 `--fixture` 更新。

App 每次识别都会保存照片和 OCR 原始结果。用 `python tools/pull_runs.py <adb 序列号>` 拉回电脑，
它们会进入 `samples/run_*.jpg` 和 `ocr_cache/app/`，用 `python tools/evaluate.py app` 评估。

规则还会读取店名（优先带 `LIMITED`、`SDN BHD` 等后缀的行，否则取抬头最大的字）和小票上印的日期时间。

## 指标

- **总额正确率**：解析出的总额与标注相差不超过 0.5%。
- **菜品 F1**：名称相似度 ≥ 0.6 且金额一致才算匹配。
- **求和对得上**：不需要标注，菜品之和能对上小计或总额。

## 对比结果（2026-11-01，23 张小票，Ryzen 7 7800X3D，CPU 8 线程）

| 模型 | 平均耗时 | 权重体积 | 总额正确 | 菜品 F1（15 张） | 求和对得上 |
| --- | --- | --- | --- | --- | --- |
| PP-OCRv6 Small | 4.4 s | 32 MB | 20/23 | 0.58 | 8/23 |
| PP-OCRv6 Tiny | 1.6 s | 7 MB | 14/23 | 0.49 | 6/23 |
| Donut-CORD | 7.0 s | 806 MB | 13/23 | 0.60 | 8/23 |
| PP-OCR + LayoutLMv3 | 4.6 s | 504 MB + OCR | 11/23 | 0.42 | 8/23 |
| PaddleOCR-VL 1.6 | 约 13 分钟 | 1917 MB | 只测了 1 张 | 14/14 菜品 | — |
| Google ML Kit v2（拉丁文） | 0.13 s（手机） | — | 10/23 | 0.40 | 4/23 |

Paddle 关闭了 oneDNN（3.3 版有兼容问题），电脑耗时偏保守。PP-OCRv6 默认模型读不出韩文。

安卓端 PP-OCRv6（ONNX Runtime，4 线程，x86_64 模拟器，耗时不代表真机）：

| 模型 | 平均耗时 | 中位耗时 | 总额正确 | 菜品 F1（15 张） | 求和对得上 |
| --- | --- | --- | --- | --- | --- |
| Small（安卓） | 2.7 s | 1.6 s | 20/23 | 0.72 | 8/23 |
| Tiny（安卓） | 1.5 s | 0.6 s | 14/23 | 0.52 | 7/23 |

ML Kit 只测了拉丁文识别器，读不了样本里的 8 张中日韩小票。只看其余 15 张拉丁文小票：
PP-OCRv6 Small 总额 15/15，ML Kit 10/15。ML Kit 耗时来自 25098PN5AC 真机，其他模型是电脑 CPU，不能直接比较。
App 已不再包含 ML Kit；这组历史文字识别结果保留在 `bench_mlkit.csv` 和 `bench_android.csv` 作记录。

上面的分数早于金额列切分等规则改动。最新规则下 PP-OCRv6 三档在 24 张样本上的结果（电脑 CPU，见 `bench_tiers.csv`）：

| 模型 | 平均耗时 | 权重体积 | 总额正确 | 菜品 F1（16 张） | 求和对得上 |
| --- | --- | --- | --- | --- | --- |
| Tiny | 1.7 s | 7 MB | 15/24 | 0.54 | 9/24 |
| Small | 4.5 s | 32 MB | 21/24 | 0.66 | 12/24 |
| Medium | 16.7 s | 139 MB | 21/24 | 0.70 | 13/24 |

Medium 在 x86_64 模拟器的 App 里占用约 765 MB 内存（PSS）。

## 样本

- `cord_*`：印尼，CORD 数据集自带标注。
- `my_ jp_ kr_ cn_ au_`：其他地区，人工看图标注总额，部分标注菜品。
- 部分样本授权为 CC BY-NC，仅用于本地测试，不要随 App 分发。
