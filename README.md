# LabelScanner 标签录入

机器标签拍照 → 扫码/OCR → 自动填充 → 库存导入模板。

## 功能（获取阶段 v0.1）

- **扫码**：ML Kit Barcode Scanning，一帧同时检测多个条码（全格式：
  EAN13/Code128/QR 等），扫到什么返回什么
- **OCR**：ML Kit Text Recognition（中文模型），条码缺失时兜底识别文字
- **自动解析**：按值特征判断字段（8 位日期 / 69 开头 EAN / 10·12 位物料码 /
  字母数字混合 SN），10 位物料码自动补 01
- **人工确认**：识别结果自动填入字段，可编辑修正后保存

## 自动放大拍标签

点「文档扫描」后选择「自动放大拍标签（推荐）」：识别完整标签边界，按手机镜头
支持的倍率放大（最多 4 倍），对标签中心对焦和测光，等待画面稳定、标签清晰后
自动拍照。也可以直接点「拍照」进入同一相机，或按快门手动拍摄。

「Google 文档扫描（原版）」保留 Google 自带的找边、裁切和增强界面。
Google 的 [文档扫描 SDK](https://developers.google.com/ml-kit/vision/doc-scanner/android)
没有开放相机变焦控制；自动放大使用本应用的 CameraX 相机，不代表给 Google
自带取景页增加了隐藏参数。两条路径拍完仍使用原有的全量扫码和本地 OCR，
不会仅为了放大某一个条码而主动裁掉同标签上的其他码。

自动检测针对较亮的矩形标签；低对比度、复杂背景或过远的标签仍可能需要靠近、
调整位置或手动拍照。自动变焦不能替代光学分辨率。安装后需实机检查：小标签、
竖向长标签、偏心标签、低光、固定焦距镜头、拍摄失败重试，以及原版 Google
入口和相册导入是否正常。

## 技术栈

- Kotlin + View（非 Compose，轻量）
- CameraX 1.6.1（预览 + ImageAnalysis）
- ML Kit barcode-scanning 17.3.0 + text-recognition 16.0.1（中文）
- Gradle 9.4.1 / AGP 8.11.1 / compileSdk 36 / minSdk 26

## 构建

```bash
./gradlew assembleDebug   # 本地
# 或推 main 分支，GitHub Actions 自动构建 APK artifact
```

## 数据映射（库存导入模板）

| 字段 | 模板列 | 规则 |
|---|---|---|
| 物料编码 | D | 10 位补 01；12 位原样；69 开头保留 |
| 箱号 SN | E | = 序列号 |
| 数量 | F | 默认 1 |
| 生产日期 | I | yyyymmdd |
| SN 码 | O | = 序列号（同 E） |
