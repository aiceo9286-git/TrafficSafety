# TrafficSafety 汽車安全助理 App 修改履歷

## [2.5.2] - 2026-05-23

### 修復物體辨識功能
- **修正類別索引錯誤** - 修復 COCO 類別對應
  - 將 91 類索引改為標準 80 類 COCO 索引
  - VALID_CLASS_INDICES: 1→0, 2→1, 3→2, 4→3, 6→5, 7→6, 8→7, 10→9, 13→11
- **修正 NUM_CLASSES** - 從 91 改為 80，與 labels.txt 對應
- **修復紅綠燈偵測判斷** - 將中文 "紅綠燈" 改為英文 "traffic light"
- **更新 COCO_LABELS_80** - 移除 COCO 91 類的占位符，使用標準 80 類標籤

---

## [2.5.1] - 2026-05-22

### 重大更新
- **核心模型解析重構** - 完全重寫 ObjectDetectorWrapper
  - 實現真正的多輸出解析 (boxes + scores)
  - 正確讀取兩個 tensor：boxes [1,12276,4] 與 scores [1,12276,91]
  - 修正座標格式解讀 [ymin,xmin,ymax,xmax]

### Bug 修復
- **修正類別對應錯誤** - 使用模型真正的 91 類 COCO 標籤索引
  - person (0), bicycle (1), car (2), motorcycle (3), airplane (4)
  - bus (5), train (6), truck (7), traffic light (9) 等
  
- **修正座標轉換錯誤** - 256x256 輸入尺寸正確映射到預覽畫面
  - 解決 bounding box 位置偏移問題
  - 解決建築物被誤認為行人的問題

### 介面優化
- **OverlayView 重構** - 簡化紅綠燈顯示邏輯
- **MainActivity 更新** - 移除場景模式顯示 (暫時)
- 統一使用 DetectionResult 顯示所有偵測結果

### 已知問題
- 模型實際為 EfficientDet/TFLite Object Detection API 格式
- 若測試仍發現問題，可考慮下載真正的 YOLOv8n TFLite 模型替換

---

## [2.4.0] - 2026-05-22

### 優化
- **閾值調整** - 提高 CONFIDENCE_THRESHOLD 0.30→0.45
- **ROI 範圍調整** - top 20%→30%, bottom 95%→90%
- **FPS 顯示** - 新增即時幀率計算
- **視覺回饋** - 危險狀態閃爍動畫

---

## Version Info
- **Application ID**: com.sharn.trafficsafety
- **Min SDK**: 24
- **Target SDK**: 34
- **Compile SDK**: 34
- **Java Version**: 17
