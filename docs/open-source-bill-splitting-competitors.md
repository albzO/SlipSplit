# 本地小票识别与分账工具：开源竞品调研

调研日期：2026-11-02。查看了本项目 README、安卓解析模型和结果界面，并核对下列项目的 README、许可证信息及部分关键源码。未安装或实测竞品，文档中的功能描述不代表识别准确率已验证。本次只新增调研文档，没有修改 App。

**有相近竞品。最值得看的是 SplitBill 的按商品分账、Spliit 的多人账本，以及 receipt-scanner 的识别后校对。你的项目已经有离线 OCR 和结构化商品解析基础，适合继续做成“拍小票 → 核对金额 → 点选商品归谁 → 分享结算单”的安卓工具。**

| 项目 | 与你的目标的关系 | 本地识别情况 | 许可证与参考价值 |
| --- | --- | --- | --- |
| [SplitBill](https://github.com/aloysiuspattath/splitbill) | 最接近按商品分账：商品认领、多人分摊、税费折扣、付款人与跨账单结算；React 网页/PWA | Tesseract.js 在本地识字，但当前源码优先请求云端解析文字，失败后才回退本地规则 | GPL-3.0；重点看商品分配交互、分币计算、结算。不能按 README 的“完全本地”直接判断实际数据流 |
| [Spliit](https://github.com/spliit-app/spliit) | 多人账本方向：群组、不等额分摊、余额和还款；网页应用 | 扫描小票功能使用服务端模型接口，不属于手机纯离线方案 | MIT；适合研究成员、支出、余额、结算的数据关系 |
| [receipt-scanner](https://github.com/mirconegri/receipt-scanner) | 离线小票管理：商品明细、税费、总额、校对、历史记录；React Native | ML Kit 本地识别，SQLite 本地存储 | MIT；重点看识别后的编辑流程、明细与总额不符提示。README 明确说明真实小票全流程仍未确认 |
| [android-receipt-tracker](https://github.com/nicholashutter/android-receipt-tracker) | 安卓本地小票记账与总额候选选择 | ML Kit 本地识别，Room 存储 | MIT；可参考总额候选分类及人工纠错。README 中两阶段分类器主要用于诊断流程，不能理解为正式界面已完整采用 |
| [SplitLens](https://github.com/growithanand/SplitLens) | 安卓 Flutter，小票总额识别、确认后均分、历史记录；当前主要是整单均分 | ML Kit 拉丁文字识别，英语/德语规则解析、本地保存 | 查阅时 GitHub 未识别到许可证，仓库文件树也未见 LICENSE/COPYING；先作为公开源码案例观察，不列为已确认授权复用的开源项目 |

SplitBill 和 SplitLens 的现有规模较小；上表适合作为实现参考，不是“成熟产品已经解决了复杂小票”的证据，也没有可与本项目直接比较的统一准确率结果。

**SplitBill 有一个需要单独指出的地方。** 它的 README 宣称全部本地，但所查版本的 `ocrService.ts` 在 Tesseract 完成后，会先请求 `/api/parseReceipt`，发送 `smartRawText`；对应接口可调用 Groq 或 Gemini。失败时才进入本地 `parseReceiptText`。这里发送的是识别文字，并非该段代码上传原图；不过小票文字依然可能离开设备。另外，OCR 资源加载还有 CDN 回退，完全断网使用需要资源事先可用。依据：[OCR 调用代码](https://github.com/aloysiuspattath/splitbill/blob/6d746eea2b2791477a1e80cd20f9ab8501808e60/src/features/receipt/ocrService.ts)、[云端解析接口](https://github.com/aloysiuspattath/splitbill/blob/6d746eea2b2791477a1e80cd20f9ab8501808e60/functions/api/parseReceipt.ts)。

它的计算部分仍很值得研究：按最小货币单位保存金额，按权重分配，并用最大余数方法分配尾差，使各人的金额加起来等于目标总额。参考：[商品及税费计算](https://github.com/aloysiuspattath/splitbill/blob/6d746eea2b2791477a1e80cd20f9ab8501808e60/src/features/calculation/engine.ts)、[尾差分配](https://github.com/aloysiuspattath/splitbill/blob/6d746eea2b2791477a1e80cd20f9ab8501808e60/src/features/calculation/rounding.ts)、[跨账单结算](https://github.com/aloysiuspattath/splitbill/blob/6d746eea2b2791477a1e80cd20f9ab8501808e60/src/features/calculation/settleUp.ts)。GPL 与 MIT 的复用条件不同；如果以后准备直接引入代码，应按对应 LICENSE 处理。这里推荐优先研究功能和规则。

**你的项目目前处于什么位置。**

现有 `ReceiptParser.kt` 已经解析商品名称、数量、金额、规格、店名、日期，以及小计、税、服务费、折扣、总额；商品还能关联原始 OCR 文本框。`MainActivity.kt` 目前主要展示识别明细和金额，还没有完整的成员、商品认领、分摊及结算界面。本地 PP-OCR、拍照矫正和商品解析可以继续沿用。

有两个现成的实现细节会影响后续分账：

- 现在 `ReceiptItem.price` 是 `Double`，现有校验直接对每行 `price` 求和，实际按行金额使用。以后应明确区分“单价”和“该行总价”，不能拿现有行金额再乘一次数量。
- 分账层应使用最小货币单位的整数，例如 AUD/CNY 的分；货币及其精度单独保存。100.00 三人均分可分成 33.34、33.33、33.33，保证结果严格合计 100.00。

本项目 README 现有基准也分别衡量了“总额正确”和“商品求和对得上”，说明只读对总额还不足以自动分配每一项。分账产品需要把识别后校对做成正常流程。

**建议的首版范围。**

1. **识别后核对。** 商品名称、数量、行金额可改，可补漏项、删误识别项；显示“明细与票面差额”，让用户确认差额来源。
2. **添加成员与付款人。** 第一版先由一部手机完成操作，整单一个付款人；允许“我替大家付了”。
3. **逐项认领。** 点商品选择谁消费；支持一个人承担、几个人平分，以及按份数分。始终能看到尚未分配的商品。
4. **处理附加金额。** 明确税费是已含还是另加，折扣是单品还是整单；服务费、小费可按消费金额比例或均分。每项规则都让用户能修改。
5. **展示并分享结算。** 输出每人消费明细、附加费用和应付额，并说明谁转给谁；先做文字或图片分享，对方无需安装 App。
6. **本地账单保存与重开。** 将用户确认过的数据和识别原始结果分开保存，支持继续修改。多人实时同步、账号和旅行多账单债务合并可放到后续版本。

我更建议把首版定位为：**无需账号、离线识别中英文小票、每件商品点选归属、自动算清税费与尾差、可分享结算单。** 这与现有项目最贴近；眼下最值得补的是“核对到分账”的完整操作流程。

其余核对来源：[Spliit README（扫描依赖与部署说明）](https://github.com/spliit-app/spliit#readme)、[Spliit 当前扫描实现](https://github.com/spliit-app/spliit/blob/73478ed2f67a59e83866948caeece6bf2ecb985e/src/app/groups/%5BgroupId%5D/expenses/create-from-receipt-button-actions.ts)、[receipt-scanner README 与验证限制](https://github.com/mirconegri/receipt-scanner/blob/8fc0db25fbe6e6a29aa7e938d15a9efb62bee0c0/README.md)、[android-receipt-tracker README](https://github.com/nicholashutter/android-receipt-tracker/blob/7d23108e12cde9ebebfa4a9f689c744ed30df3de/README.md)、[SplitLens README](https://github.com/growithanand/SplitLens/blob/e047bc01d3f0f8daa1095c57fd079540553344a5/README.md)。
