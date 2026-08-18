# 🔋 Battery (电池检测与健康分析)

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="Battery App Icon" />
</p>

<p align="center">
  <strong>一款专为 Android 设计的轻量、精准、现代的电池健康与硬件参数分析工具。</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-1.9.0-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Min%20SDK-24-blue.svg" alt="Min SDK" />
  <img src="https://img.shields.io/badge/Target%20SDK-34-orange.svg" alt="Target SDK" />
  <img src="https://img.shields.io/badge/License-MIT-lightgrey.svg" alt="License" />
</p>

---

## 🌟 核心特性

### 1. 🔍 三重数据采集与深度解析引擎
* **系统标准 API (BatteryManager)**：零门槛读取系统广播与标准接口数据（电量、状态、健康、温度、电压、电流等）。
* **Shizuku 提权深度读取 (Rootless ADB)**：
  - 无需 Root，通过 Shizuku 授权获取底层 shell 权限；
  - 穿透读取 `/sys/class/power_supply/` 底层驱动节点与 `dumpsys` 服务；
  - 提取电量计真实充满容量（FCC）、循环次数（Cycle Count）、真实微安级瞬时电流与双电芯信息；
  - 适配主流厂商（小米、OPPO、vivo、华为等）私有电池服务。
* **错误报告 (Bugreport) 极速秒级解析**：
  - 支持直接导入几十至几百兆的系统错误报告文件（`bugreport-*.zip` 或 `.txt`）；
  - 采用原生**字节流滑动窗口快扫技术**，毫秒级快速定位并结构化解析 `getHealthInfo` 结构体；
  - 自动提取并展示错误报告实际生成/抓取的日期时间。

---

### 2. 📊 15 项全维度电池硬件参数
每一项参数均经过严格清洗与物理量纲换算：

| 参数项 | 说明 | 数据来源 |
| :--- | :--- | :--- |
| 🔋 **当前电量** | 剩余电量百分比（如 `85%`） | 系统 API / 底层节点 |
| ⚡ **充电状态** | 正在充电 / 放电中 / 未充电 / 已充满 | 电源芯片状态 |
| 🩺 **电池健康** | 系统诊断状态（良好 / 过热 / 损坏 / 过压等） | Android 诊断接口 |
| ❤️ **健康度 (Health)** | 真实健康度百分比（充满容量 FCC ÷ 出厂设计容量） | 算法精准计算 |
| 🔄 **循环计数** | 电量计记录的等效充放电循环次数 | 底层硬件计步器 |
| 🌡️ **电池温度** | 实时摄氏度（精确至 `0.1℃`） | 电池内部热敏电阻 |
| 🔋 **电池电压** | 实时端电压（`mV` / `V`） | 模数转换器 (ADC) |
| ⚡ **瞬时电流** | 实时充放电电流（`mA`，正负分明） | 库仑计采样电阻 |
| ⚡ **电池功率** | 实时瞬时工作功率（`W` 瓦数） | 动态功率计算公式 |
| 🔋 **设计容量** | 厂商出厂额定设计总容量（`mAh`） | PowerProfile / 底层参数 |
| 🔋 **充满容量 (FCC)** | 当前电池完全充满时的最大容量估算（`mAh`） | 芯片电量计学习值 |
| 🔋 **当前容量** | 当前剩余可用电量容量（`mAh`） | 库仑计电荷计数 |
| 🔋 **双电芯检测** | 是否为双串联/并联电芯架构 | 硬件拓扑识别 |
| 🔬 **电池技术** | 电池材料类型（如 `Li-ion`、`Li-poly`） | 硬件属性 |
| 📅 **检测时间** | 该次检测或错误报告抓取的精确日期时间 | 自动时间戳 |

---

### 3. 🎨 现代 Material 3 界面与丝滑交互
* **Material 3 底部导航**：包含“电池检测”、“历史记录”、“设置”三大页面。
* **手势与下拉刷新**：集成 `SwipeRefreshLayout`，全屏向下拉动触发平滑刷新动画。
* **分组卡片化设置**：
  - 用户界面、数据与刷新、Shizuku 提权、关于等分类卡片；
  - 统一 `56dp` 物理高度与自适应居中对齐；
  - 精准右对齐、从右上角向左下方弹性展开的紧凑下拉弹出菜单（支持 1s / 2s / 3s / 5s / 10s 刷新间隔调节）。
* **全系统暗黑模式与圆角触控波纹**：
  - 完美适配浅色与深色（AMOLED 纯黑/深灰）主题；
  - 点击组件自带带圆角 Mask 裁剪的水波纹高亮，视觉精致无溢出。

---

## 🛠️ 技术架构

* **编程语言**：100% [Kotlin](https://kotlinlang.org/)
* **架构模式**：MVVM (Model-View-ViewModel) + 响应式单向数据流
* **异步与并发**：Kotlin Coroutines (协程) + Flow / StateFlow
* **视图绑定**：Android Jetpack ViewBinding
* **组件库**：
  - Google Material Components 3 (`com.google.android.material:material:1.11.0`)
  - AndroidX SwipeRefreshLayout (`androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`)
  - Rikka Shizuku API (`dev.rikka.shizuku:api:13.1.5` / `provider:13.1.5`)
  - AndroidX Lifecycle & ViewModel KTX (`2.7.0`)
  - Kotlinx Coroutines Android (`1.7.3`)

---

## 📦 项目结构

```
Battery/
├── app/
│   ├── src/main/
│   │   ├── java/com/battery/analysis/
│   │   │   ├── model/                  # 电池参数与结果数据模型 (BatteryInfo, HealthInfoItem)
│   │   │   ├── provider/               # 数据提供者 (NormalApi, Shizuku, BugreportParser, Fusion)
│   │   │   ├── ui/                     # UI 视图层 (Fragment, ViewPager2 适配器, ViewBinder)
│   │   │   ├── viewmodel/              # 业务逻辑与状态流 ViewModel
│   │   │   └── MainActivity.kt         # 主入口 Activity (导航与生命周期调度)
│   │   └── res/
│   │       ├── anim/                   # 右上角展开动画资源
│   │       ├── color/                  # 状态选择器与颜色资源
│   │       ├── drawable/               # 矢量图标与圆角卡片背景
│   │       ├── layout/                 # 页面与组件布局
│   │       ├── values/                 # 主题样式、文字与浅色调色板
│   │       └── values-night/           # 深色主题与高对比度调色板
├── .github/workflows/                 # CI/CD 自动化构建与 Release 签名打包脚本
└── build.gradle.kts                   # 顶级与模块级构建配置
```

---

## 🚀 编译与构建

### 1. 环境要求
* **Android Studio**：Hedgehog (2023.1.1) 或更高版本
* **JDK**：OpenJDK 17 或以上
* **Android SDK**：API 34 (Android 14)

### 2. 本地编译步骤
```bash
# 克隆仓库
git clone https://github.com/zhyang18/Battery.git
cd Battery

# 编译 Debug 版 APK
./gradlew assembleDebug

# 编译 Release 版 APK
./gradlew assembleRelease
```
编译产物位于 `app/build/outputs/apk/` 目录下。

---

## 📱 使用指南

1. **普通检测**：直接打开 App，进入“电池检测”页，向下拉动即可刷新普通 API 读取到的所有电池参数。
2. **Shizuku 提权检测**：
   - 手机安装并启动 [Shizuku](https://shizuku.rikka.app/)（通过无线调试或电脑 ADB 配对激活）；
   - 在本 App “设置”中点击“请求授权”或直接切换至“Shizuku”Tab，授权后即可实时读取电量计真实循环次数与底层数据。
3. **错误报告分析**：
   - 在手机“开发者选项”中点击“抓取错误报告 (Take bugreport)”；
   - 生成完成后在“错误报告”Tab 中点击“导入报告”，选择生成的 `.zip` 或 `.txt`，App 将瞬间完成解析并展示抓取时间与详细健康数据。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 协议开源。
