# LabelScanner 标签录入

机器标签拍照 → 扫码/OCR → 自动填充 → 库存导入模板。

## 功能（获取阶段 v0.1）

- **扫码**：ML Kit Barcode Scanning，一帧同时检测多个条码（全格式：
  EAN13/Code128/QR 等），扫到什么返回什么
- **OCR**：ML Kit Text Recognition（中文模型），条码缺失时兜底识别文字
- **自动解析**：按值特征判断字段（8 位日期 / 69 开头 EAN / 10·12 位物料码 /
  字母数字混合 SN），10 位物料码自动补 01
- **人工确认**：识别结果自动填入字段，可编辑修正后保存

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

## 台账管理

主页「台账管理」入口：库存结存列表 + 出入库登记 + 出入流水。

- 出入库登记：扫码（沿用现有扫码和 69 码反查）或手动输入查库存，查到后自动带入库位、托盘号和当前结存，只填出入数量确认；出库库存不足或无记录会拦下，入库新物料自动建账。
- 存档：库存与流水存在远程数据库（与 69 码映射同一个 Turso 库，复用设置页的连接配置，首次使用自动建 `ledger_stock` / `ledger_movements` 两张表）；本地 SQLite 是工作副本，离线也能登记，联网时同步到远程。
- 台账从空开始，不把历史扫描记录导入成库存。
