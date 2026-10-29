# DSB 悬浮抢单（新 Xposed API 版）

用 libxposed API 101 重写，LSPosed 2.x 原生支持。功能和旧版一致：
读宿主登录态 → 读楼栋 → 按楼栋+楼层轮询 → 命中就抢单，不调用 /login，不会顶号。

## 和旧版的区别

- 入口改为 `META-INF/xposed/java_init.list` + `XposedModule`，不再用 `assets/xposed_init`。
- 钩子改成框架里的 `Activity.onResume` / `Activity.onCreate`，不再依赖宿主 `MainActivity` 是否自己声明了 `onResume`。
- 面板会出现在迪速帮的所有页面（按类名含 `disubang` 过滤），换页面时会保留楼层、间隔、勾选和日志。
- 面板第一次挂上去时会弹一次「DSB 抢单面板已加载」，用来确认模块已注入。不需要可把 `GrabPanel.java` 里的 `HELLO_TOAST` 改成 `false`。
- 拖动面板时不再先跳到屏幕左边。

## 怎么编译

### 方式一：GitHub Actions（不用装任何东西）

1. 新建一个 GitHub 仓库，把本目录里的**所有文件（包括隐藏的 `.github` 文件夹）**上传到仓库根目录。
2. 打开仓库的 Actions 页，等 `build-apk` 跑完（也可以点 Run workflow 手动触发）。
3. 进入这次运行，在 Artifacts 里下载 `DSB-Xposed-apk`，里面就是 APK。

### 方式二：Android Studio

用 Android Studio 打开本目录，等同步完成，菜单 Build → Build APK(s)。
要求 JDK 17，compileSdk 36。

## 安装

1. **先卸载旧的 DSB 模块**（签名不同，不能覆盖安装，也避免两个模块同时往宿主里挂面板）。
2. 安装新 APK，在 LSPosed 里启用，作用域勾选「迪速帮配送版」。
3. 在系统设置的应用信息里强制停止迪速帮，再重新打开。

## 排错

- 日志：LSPosed 「日志」→「模块日志」，搜 `DSB`。正常会看到 `module loaded` / `package ready` / `hooked Activity.onResume`。
- 依赖版本编译不过时：`app/build.gradle` 里 `io.github.libxposed:api:101.0.0` 是编译期依赖，AGP 是 `8.13.2`，Gradle 是 `8.13`。
