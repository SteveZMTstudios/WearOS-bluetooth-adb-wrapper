# WearOS 蓝牙调试桥接

安装在手机上的前台服务应用，用来补回 WearOS 3 手机侧缺失的蓝牙 ADB 桥接能力。

手表端仍然使用系统设置里的“通过蓝牙调试”。本应用负责在手机侧：

- 选择已配对的手表
- 发起 RFCOMM 蓝牙握手
- 在本地暴露 `adb-hub`
- 可选开放 TCP 端口，便于本机或局域网 `adb connect`

> [!WARNING]
> WearOS 3 以后的版本完全移除了蓝牙调试，此工具只能在WearOS 2-3之间的版本上使用。🤦
> 如果你手表上的开发者选项里没有“通过蓝牙调试”，说明你的系统版本不受支持。


## 当前能力

- 列出手机上已配对的蓝牙设备并选择目标手表
- 冷启动时优先申请“附近设备 / 蓝牙连接 / 通知”权限
- 前台服务常驻后台，维持桥接逻辑
- 可选 TCP 监听端口，默认 `44444`，可手动改为 `1-65535`
- 服务、主机、目标三路状态分别显示并做颜色区分
- “端点信息”和“连接命令”默认折叠，可展开查看
- “端点信息”和“连接命令”支持点击复制到剪贴板
- 握手失败、端口占用、权限缺失等故障会在界面和通知中显示


## 环境要求

- Android 手机一台
- 已与手机完成蓝牙配对的 Wear OS 手表一台
- 手表端已在开发者选项中开启“通过蓝牙调试”
- JDK 11 或以上
- 可用的 Gradle 构建环境

## 构建

Windows：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

调试包输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

预编译的 APK 可在 [Releases 页面](https://github.com/SteveZMTstudios/WearOS-bluetooth-adb-wrapper/releases/)下载。

## 使用方法

1. 在手机上安装并启动应用。
2. 首次冷启动时授予所需权限。
3. 在主界面选择目标手表。
4. 如需开放 TCP，打开开关并填写监听端口。
5. 在手表系统设置中打开“通过蓝牙调试”。
6. 点击“启动桥接”。
7. 观察“服务 / 主机 / 目标”状态行。
8. 需要命令时展开“连接命令”，点击内容或复制按钮即可复制。

## 两种连接方式

### 1. 经典 `adb-hub`

手机侧会创建本地 abstract socket：

```text
localabstract:/adb-hub
```

电脑先连接手机（通过数据线或无线的方式）后，可执行：

```bash
adb forward tcp:44444 localabstract:/adb-hub
adb connect 127.0.0.1:44444
```

如果你改了 TCP 端口，把上面的 `44444` 一并替换掉。

### 2. TCP 监听

开启“开放 TCP 监听”后，应用会在手机上监听指定端口，并在“端点信息”里显示当前可用地址。

借助这项能力，手机上的工具（如Termux）无须连接到Wi-Fi也能直接通过 IP 地址连接到手表，局域网内的其他设备也能连接到手表进行调试。

例如：

```bash
adb connect 192.168.1.23:44444
```

<!-- ## 常见问题

### 提示缺少 `BLUETOOTH_SCAN`

Android 12 及以上需要“附近设备”权限。

### TCP 端口被占用

把 TCP 开关打开后，可在左侧输入框里直接改端口。有效范围为 `1-65535`。


### ADB 断开导致应用闪退

详见[issue #1](https://github.com/SteveZMTstudios/WearOS-bluetooth-adb-wrapper/issues/1)。
已修复 -->

## 许可

MIT License
