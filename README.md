<div align="center">

<img src="app/src/main/res/drawable-nodpi/mask_icon.png" width="120" alt="MokrRun" />

# 随心跑 · MokrRun

**在地图上画一条线，让它替你跑完**

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)](#-兼容性)
[![Framework](https://img.shields.io/badge/framework-LSPosed-purple.svg)](#-安装)
[![Version](https://img.shields.io/badge/version-1.0.74-orange.svg)](../../releases)

一个 LSPosed 模块，把「校园跑」类应用的轨迹与步数变成可规划、可调速的模拟。

</div>

---

## ✨ 特性

- **🗺️ 地图上画路线** — 全屏地图点几个途经点，起点自动取你的真实位置
- **🎯 位置与步数同源** — 同一个 250ms tick 驱动，物理上不可能出现「步数涨了位置没动」
- **⏯️ 悬浮窗控制** — 可拖动的悬浮面板，开始 / 暂停 / 继续 / 结束一键切换
- **🎚️ 实时调速** — 拖动速度条，速度和步频立即跟着变，不用回 App
- **📏 自动换算** — 改速度时步频与步长按真人步态规律自动算出
- **🕶️ 反检测** — mock 标记恒为 false，模拟定位类应用从包列表隐藏
- **🔒 零权限门槛** — 不需要 adb、不需要 root，装上勾选就能用
- **📝 最近三条路线** — 画过的路线自动留档，超出三条淘汰最旧的

## 📸 界面

| 主页 | 画线 | 悬浮窗 |
|:---:|:---:|:---:|
| 地图预览 + 起点 / 画线入口 | 起点固定，点击加途经点 | 距离 / 步数 / 速度条 |

> 截图待补充

## 🚀 安装

### 环境要求

| 项 | 要求 |
|---|---|
| Android | 8.0 及以上 |
| Root | 需要（解锁 Bootloader） |
| 框架 | LSPosed（或兼容的 Xposed 实现） |

### 步骤

1. 从 [Releases](../../releases) 下载最新 `MokrRun-x.x.x.apk`
2. 安装后打开 **LSPosed**，在模块列表里启用「随心跑」
3. 进入模块的作用域设置，**勾选目标应用**（默认 `com.bxkj.student`）
4. 强制停止目标应用一次，让它重新加载模块

> 第一次打开「随心跑」时，需要手动允许**悬浮窗**和**定位**权限。

## 📖 使用

```
① 打开「随心跑」→ 点「修改起点」→ 拖动地图对准起点 → 确定
② 点「绘制路线」→ 点几个途经点 → 保存
   （悬浮窗出现，显示「就绪」）
③ 切到目标应用，进入跑步页面
④ 点悬浮窗「开始」→ 位置开始沿你画的路线移动
```

运行中随时可以：

- 拖动悬浮窗移动位置
- 拖速度条调整快慢（步频同步变化）
- 「暂停」冻结在原地，「结束」交还真实位置

## 🔧 工作原理

```
┌──────────────────┐                    ┌───────────────────┐
│  MokrRun App     │   Intent extras    │  目标应用进程       │
│                  │ ─────────────────→ │                   │
│ 画路线 / 调参数    │  启动时带配置       │  MotionEngine     │
│ 悬浮窗控制        │                    │   ├ 位置插值推进   │
│                  │                    │   └ 步数生成       │
└──────────────────┘                    │                   │
                                        │  LocationHook     │
                                        │   └ 接管定位读取   │
                                        │  SensorHook       │
                                        │   └ 注入步数       │
                                        └───────────────────┘
```

几个关键设计：

**配置走 Intent，不走跨进程通信**
点「开始」时把速度、起点、途经点塞进启动 Intent，hook 在 `Activity.onCreate` 和 `onNewIntent` 里读出来。这是一次标准的 Activity 启动，不需要任何特殊权限。

**位置和步数由同一个 tick 驱动**
每 250ms 推进一次：位置沿折线插值前进，步数按步频累加。两者共享同一个时间轴，不会漂移。

**接管所有定位读取**
不仅拦 `LocationManager`，还 hook 了高德地图 SDK 的 `AMapLocation.getLatitude()` 等方法——因为高德有自己的网络定位，不走系统 API。

## 🧩 兼容性

| 环境 | 状态 | 说明 |
|---|---|---|
| 原生 Android / LineageOS | ✅ | 配置通道直接可用 |
| MIUI / 澎湃 OS | ✅ | 已适配，无需额外授权 |
| 目标应用 `com.bxkj.student` | ✅ | 测试于 v2.0.22 |

> 其他「校园跑」应用理论上可用，但需要在模块设置里把包名改掉，
> 且定位 SDK 不同的话可能要补充对应的 hook。

## 🛠️ 从源码构建

没有 Gradle，用一套手工流水线（`aapt2 → javac → d8 → zipalign → apksigner`）。

### 1. 准备工具链

把下面这几样放进同一个目录（下称 `$MOKR_TOOLS`）：

```
$MOKR_TOOLS/
├── jdk/                  JDK 17 或 21
├── bt/android-14/        Android build-tools（含 aapt2 / zipalign / apksigner）
├── r8/r8.jar             R8（必须，自带 d8 8.2 有内部类 NPE）
└── android.jar           API 34
```

> R8 从 Google Maven 取：`com/android/tools/r8/<version>/r8-<version>.jar`

### 2. 构建

```powershell
$env:MOKR_TOOLS  = "D:\android-tools"
$env:MOKR_KS_PASS = "yourpass"      # 可选

.\build.ps1                          # 主模块
.\tools\probe\build-probe.ps1        # 测试探针
.\install.ps1                        # 安装到已连接的设备
```

第一次构建会自动生成一个开发用 keystore（`CN=MokrRun Dev`），不需要手动准备。

> 工程放在**中文路径**下会失败（aapt2 / d8 的限制），用 `mklink /J` 映射到纯 ASCII 路径。

## ❓常见问题

**Q：装上之后步数正常但位置不动？**
A：大概率是目标应用重启前加载的旧模块。强制停止它再打开。

**Q：悬浮窗不出现？**
A：检查系统设置里是否允许「随心跑」显示在其他应用上层。

**Q：点了开始但位置没变？**
A：确认已经画过路线。只有起点没有路线时，位置会固定停在起点。

**Q：换了签名后装不上？**
A：Android 不允许签名不同的同包名应用覆盖安装，先卸载旧版本。

## 📄 许可

[MIT](LICENSE)

本项目仅供学习 Android 定位与传感器 API 的 hook 技术使用。
