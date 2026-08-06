# AGENTS.md — LabelScanner 项目约定

## 项目定位
机器标签拍照 → 扫码/OCR → 自动填字段 → 导出库存导入模板 Excel。
用户是工厂仓储/计划岗，App 给自己和同事用，**无云 AI 额度，全离线**。

## 硬性约束
- **全离线**：ML Kit 本地模型，禁止任何云端 OCR/识别 API。
- **中文界面**：所有 UI 文案中文。
- **不要乱改模板列规则**：D=物料编码 E=箱号SN F=数量 I=生产日期(yyyymmdd)
  O=SN码(同E)；第 1/2 行列名与表头不可改（导入系统硬性要求）。
- 物料编码：10 位补 01；12 位原样；**69 开头系统不认但用户要用**——App
  存完整数据，导出时生成两份（系统版剔除 69、自有版全保留）。

## 架构
- View 体系（非 Compose）：MainActivity + CameraX PreviewView +
  ImageAnalysis → BarcodeAnalyzer（ML Kit 双引擎：条码多码 + 中文 OCR）
- 解析纯规则：`LabelParser.classify()` 按值特征分类，不依赖标签排版
- 结果流：analyze 帧 → 合并解析 → LabelResult → UI 人工确认 → 列表

## 已知注意
- ML Kit barcode 17.3.0 / text-recognition 16.0.1 / cameraX 1.6.1 /
  Gradle 9.4.1 / AGP 8.11.1 / compileSdk 36 / minSdk 26
- 本机无 Android SDK，构建靠 GitHub Actions（assembleDebug → artifact）
- 别升级 OkHttp 之类的传递依赖导致 R8 问题（参照 Han1meViewer 教训）
