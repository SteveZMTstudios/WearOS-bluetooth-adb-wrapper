# WearOS 蓝牙调试桥接

这是一个安装在手机上的工具，用来帮助 Wear OS 手表建立蓝牙 ADB 调试中转。它不安装在手表上，而是在手机端工作：手机通过 App 选择已配对的手表后，代替手动打开手表上的“通过蓝牙调试”开关，拉起并维持中转连接。相当于复刻了wear os by google 在wear os2的蓝牙调试开关，使得在wear os3上也能通过蓝牙进行 adb 调试。

同时，应用也支持可选的 TCP 监听。开启后，手机本机以及手机内的其他程序可以直接通过 `127.0.0.1:端口` 访问这条中转通道，不需要额外的手动转发操作。

这相当于复刻了 Wear OS by Google 在 Wear OS 2 上提供的蓝牙调试开关，只是入口放在手机 App 里，便于日常使用。



## 功能

- 读取已配对的蓝牙设备列表，并允许选择目标 Wear OS 手表
- 通过前台服务在手机端启动和维持蓝牙 ADB 桥接
- 可选开放 TCP 监听，便于手机本机或其他程序通过 `127.0.0.1` 访问中转通道
- 在界面底部展示当前状态、端点信息和可直接复制的连接命令
- 运行状态通过通知常驻，便于后台查看服务是否正常

## 环境要求

- 手机一台，用于安装和运行本 App
- Wear OS 手表一台，且已与手机完成蓝牙配对
- Android Studio 或命令行构建环境
- JDK 11
- Android Gradle Plugin 会使用项目内置的 Gradle Wrapper
- 首次构建时需要可访问 Maven Central 和 Google Maven 仓库

## 权限说明

应用会申请以下权限，具体取决于系统版本和实际功能使用情况：

- 附近设备
- 蓝牙连接
- 蓝牙扫描
- 通知
- 前台服务相关权限
- 网络访问权限

如果权限未授予，应用不会进入桥接流程。

## 构建方式

### Debug 包

Windows：
```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：
```bash
./gradlew assembleDebug
```


## 使用方法

1. 在和手表配对的手机上安装应用。
2. 授予所需权限，至少包括“附近设备”和“通知”。
3. 打开应用，在主界面选择一个已配对的 Wear OS 手表。
4. 在手表端手动打开“通过蓝牙调试”后，再点击“启动桥接”；App 会负责维持这条中转链路。
5. 如需让手机上的其他程序直接访问这条中转通道，可开启“开放 TCP 监听”，并填写端口。
6. 观察底部“连接命令”区域，按提示在电脑端或本机程序中执行对应命令。

### 蓝牙桥接

蓝牙桥接是默认路径。启动后，应用会尝试建立到目标手表的桥接通道，并在界面与通知中展示当前状态。

### TCP 监听

如果开启 TCP 监听，应用会在手机侧开放指定端口。这样手机本机上的其他程序可以直接连接 `127.0.0.1:端口`，也可以按需在局域网环境里使用对应地址访问。界面会显示可用的本机 IP 地址，以及对应的连接命令。

常见命令示例：

```bash
adb forward tcp:44444 localabstract:/adb-hub
adb connect 127.0.0.1:44444
```

如果你直接使用手机的局域网地址，则命令会根据实际 IP 和端口变化。

## 项目说明

- `minSdk`：24
- `compileSdk`：36
- `targetSdk`：36
- `sourceCompatibility` / `jvmTarget`：11
- UI 使用 ViewBinding
- 主入口为 `MainActivity`
- 后台桥接由前台服务 `BluetoothAdbBridgeService` 负责

## 目录结构

- `app/src/main/java`：核心逻辑、桥接控制、服务与页面代码
- `app/src/main/res`：界面布局、字符串、图标和主题资源
- `app/build.gradle.kts`：模块级构建配置
- `gradle/libs.versions.toml`：依赖版本管理

## 许可

MIT License