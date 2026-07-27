# 燧引（Suì Yǐn）

> 燧石擦出火星，引燃·引导。

一款基于蓝牙的安卓设备远程控制工具，让一台手机（主机）通过蓝牙远程控制另一台手机（备机）的网络、短信和媒体。

## 命名由来

**燧** — 燧石取火，摩擦生热。开热点就像点火，一瞬间的光亮。
**引** — 引出短信列表，引导媒体播放，也是远程控制的"牵引"。

原始、硬核、带点工具属性——像燧石一样可靠。

## 功能特性

| 功能 | 说明 |
|------|------|
| 🌐 WiFi 控制 | 远程开关备机 WiFi |
| 🔥 热点控制 | 远程开关备机热点（支持双网卡同开） |
| 📶 移动数据 | 远程开关备机移动数据 |
| 💬 短信同步 | 备机收到短信，主机实时弹窗显示 |
| 🎵 媒体控制 | 主机媒体状态同步到备机，支持手表控制 |
| 🔋 设备状态 | 实时显示备机电量、信号强度 |
| ⚡ 后台保活 | 前台服务 + 开机自启 + 电池优化白名单 |

## 设备要求

| 设备 | 系统 | Root | LSPosed |
|------|------|------|---------|
| 主机（A） | Android 14+ | 不需要 | 不需要 |
| 备机（B） | Android 13+ | 需要 | 可选（增强热点控制） |

> **关于热点控制**：MIUI 等国产 ROM 上，热点控制需要 root 权限执行系统命令。LSPosed 模块可进一步提升兼容性。

## 快速开始

### 安装

两台手机都安装 APK（`app/build/outputs/apk/debug/app-debug.apk`）。

### 备机设置（手机 B）

1. 授予 Root 权限
2. 打开 APP，选择「从机模式」
3. 开启「后台保活」和「开机自启动」
4. 选择要连接的主机设备
5. （可选）在 LSPosed 中启用本模块，作用域选择 `com.tool.btremote`

### 主机设置（手机 A）

1. 打开 APP，选择「主机模式」
2. 授予「通知使用权」（媒体控制需要）
3. 选择要连接的从机设备

## 项目结构

```
├── app/
│   └── src/main/
│       ├── java/com/tool/btremote/
│       │   ├── bluetooth/    # 蓝牙连接管理（SPP 协议 + 心跳保活）
│       │   ├── network/      # 网络控制（WiFi/热点/移动数据）
│       │   ├── sms/          # 短信同步
│       │   ├── media/        # 媒体控制
│       │   ├── service/      # 核心后台服务
│       │   ├── keepalive/    # 保活策略
│       │   ├── ui/           # 界面 Activity
│       │   └── App.kt        # 应用入口
│       ├── res/              # 资源文件
│       └── AndroidManifest.xml
├── tools/                    # 内置工具（Gradle + Android SDK）
└── build.bat                 # 一键编译脚本
```

## 编译

项目已内置 Gradle 和 Android SDK，无需额外配置。

```bash
# 编译 Debug APK
build.bat

# 或手动执行
tools\gradle-8.2\bin\gradle.bat assembleDebug
```

输出路径：`app\build\outputs\apk\debug\app-debug.apk`

## 技术栈

- **语言**：Kotlin
- **最低 SDK**：Android 13（API 33）
- **编译 SDK**：Android 14（API 34）
- **构建工具**：Gradle 8.2
- **蓝牙协议**：SPP（UUID: `00001101-0000-1000-8000-00805F9B34FB`）
- **协程**：Kotlin Coroutines
- **Hook 框架**：LSPosed（可选）

## 核心实现

### 蓝牙通信

- SPP 串口协议，兼容性最好
- 15 秒心跳保活，30 秒超时断线
- 断开自动重连机制

### 热点控制（多路径降级）

1. `cmd wifi start-softap <ssid> <encryption> <passphrase>`（Root，MIUI 验证有效）
2. `WifiManager.startTetheredHotspot` 反射 + appops 授权
3. `cmd connectivity start-tethering`
4. `setWifiApEnabled` 反射（旧版 API）
5. Settings Global 修改
6. MIUI 广播触发

### 短信同步

- 备机监听 `SMS_RECEIVED` 广播（优先级 999）
- 通过蓝牙发送短信内容到主机
- 主机弹出系统级全屏弹窗 + 通知 + 铃声

### 媒体控制

- 主机：`NotificationListenerService` 监听系统媒体会话
- 备机：创建 `MediaSession` 供系统/手表控制
- 控制命令通过蓝牙实时同步

## 常见问题

### 热点打不开？

1. 确认备机已授予 Root 权限
2. 在备机 APP 的「测试」按钮查看详细错误
3. MIUI 用户确认是否有安全拦截

### 后台经常断连？

1. 开启 APP 内的「后台保活」
2. 在系统设置中关闭电池优化
3. 加入后台白名单

### 短信收不到？

1. 确认备机已授予短信权限
2. 部分国产 ROM 需要在短信应用设置中允许第三方读取

## License

MIT
