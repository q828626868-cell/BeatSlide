# BeatSlide

讓圖片跟著音樂拍子自動切換的 Android 幻燈片 App，可以匯出成含音樂的 MP4 影片。

## 功能

- **專案管理**：建立、重新命名、刪除多個專案，資料存在手機本地
- **素材**：匯入一首音樂（mp3 / m4a / wav…）和多張圖片；長按拖曳排序、刪除、一鍵隨機打亂
- **節拍設定**
  - BPM：手動輸入、±1 / ±0.1 微調、÷2 / ×2
  - Tap Tempo：跟著節奏點按，取最近幾次間隔的平均
  - 自動偵測 BPM（建議值），同時估計第一拍位置
  - 起點偏移 Offset（毫秒），可在播放中一鍵「設為第一拍」
  - 每 1 / 2 / 4 / 8 拍換一張圖、每小節拍數（判斷重拍）
  - 節拍指示燈（重拍粉紅、其他拍紫色）
- **播放**：全螢幕、圖片循環、暫停、拖動進度條；填滿裁切 / 完整顯示加黑邊
- **換圖特效**（可個別開關與調整強度）：閃白、閃黑、縮放衝擊、畫面震動、RGB 色偏、故障切片、交叉淡入淡出、緩慢推近（Ken Burns）；隨機模式、強弱拍模式
- **匯出 MP4**：9:16 直式（1080×1920）或 16:9 橫式（1920×1080）、30 / 60 fps、可選起訖範圍，存到相簿「影片 / BeatSlide」

## 安裝

到 [Releases](../../releases) 下載最新的 `BeatSlide-x.x.apk`，在手機上開啟並允許「安裝不明應用程式」。需要 Android 8.0 以上。

## 對拍子的方法

1. **BPM**：按「自動偵測 BPM」，或播放音樂後跟著鼓點連點 TAP 區（4 下以上自動套用）。感覺快一倍 / 慢一倍就按 ÷2 / ×2。
2. **Offset**：播放中，在第一個重拍出現的瞬間按「設為第一拍」。只想對齊相位、讓圖片從開頭就開始換，用「對齊這一拍」。
3. **微調**：看指示燈——燈比鼓聲早亮就把 Offset 加大，晚亮就減少；播久了越來越偏就用 ±0.1 調 BPM。

## 技術說明

- Kotlin + Jetpack Compose，**不使用第三方套件**，只用 AndroidX 與系統 API
- 換圖時間點一律由「音樂目前播放位置」計算（`MediaPlayer` + `MediaTimestamp`），不用計時器累加，不會越播越偏
- 畫面由自訂 `View` + 硬體加速 Canvas 逐格繪製；所有特效都是「時間 → 畫面」的純函式，暫停、拖動、匯出都畫出一樣的結果
- 自動偵測 BPM：`MediaExtractor` + `MediaCodec` 解碼 → 起音強度曲線 → 自相關 + 精細梳狀搜尋
- 匯出 MP4：`SlideRenderer` 畫到硬體 Canvas → `SurfaceTexture` → OpenGL → H.264 編碼器（`eglPresentationTimeANDROID` 設定精確時間戳記）→ `MediaMuxer`。音訊解碼後重新編碼為 AAC，並在每次匯出時實測編碼器延遲加以補償，確保音畫對齊

### 專案結構

```
app/src/main/java/com/beatslide/app/
├── audio/    MusicPlayer（時間來源）、BeatMath（拍子計算）、BpmDetector
├── data/     Project 資料模型、ProjectStore（JSON 存檔）、MediaImporter
├── render/   SlideRenderer（特效）、SlideView、BitmapLoader
├── export/   VideoExporter、GlBlitter、ExportManager
└── ui/       各畫面（Compose）
```

## 編譯

需要 Android SDK（compileSdk 36）與 JDK 17 以上（可用 Android Studio 內附的 JBR）。

```bash
./gradlew assembleRelease
# 輸出：app/build/outputs/apk/release/app-release.apk
```

> release 版目前用 debug 金鑰簽章，方便直接安裝測試；若要上架 Google Play 需改用自己的正式金鑰。
