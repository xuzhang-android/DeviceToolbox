# 更新记录

本文件按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 组织，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增

- **持续集成**（`.github/workflows/ci.yml`）：每次 push / PR 都跑机型库四项硬检查
  （指纹 8 段合法、行内自洽、无半族行、行数列数）与构建脚本语法检查。
- **自动构建**（`.github/workflows/build.yml`）：用 NDK 编译 Zygisk 模块、用 build-tools
  构建并签名 APK，产物 = `DeviceToolbox-<版本>.apk` + `DeviceToolbox-zygisk-<版本>.zip`；
  打 `v*` tag 时自动发布 Release。构建前先跑机型库校验，数据不合格就不打包。
- **Issue 表单**：Bug 报告与功能请求改成结构化表单，Bug 表单强制填设备、root 方案、
  Zygisk 实现与复现步骤；仓库关闭空白 issue。
- **SECURITY.md**：写清哪些问题算安全问题（属性注入、快照路径穿越、配置解析、机型库污染）、
  走私密通道而不是公开 issue、以及处理节奏。
- **CONTRIBUTING.md**：机型库改动规则（13 列、不许编值、指纹逐项一致）、
  代码约束（Java 8、无第三方依赖、无异常无 RTTI 的 C++ 子集）、提交信息格式、PR 自查清单。
- **README_EN.md**：英文版说明，含构建步骤与数据来源。
- **CHANGELOG.md**：本文件。

### 变更

- `README.md` 顶部加徽章与中英文切换入口，补「反馈渠道」与「持续集成」两节。

## [1.0.0] - 2026-10-06

首个公开版本。

### 新增

- **系统级机型伪装**：六个分区（system / vendor / odm / product / system_ext / bootimage）
  的机型键一起对齐，构建指纹、构建号、Android 版本、安全补丁同步改写，
  保证「关于手机」、`getprop`、`android.os.Build` 三处口径一致。
- **内置机型库 3,539 条**：覆盖 278 个品牌、API 21 至 Android 17，其中 3,312 条
  （93.6%）带真实指纹；按通俗名 / 机型码 / 芯片搜索，可按品牌与 Android 版本筛选、收藏置顶。
- **自定机型生成器**：给品牌、型号、芯片与版本，推导代号、构建号、安全补丁与完整指纹。
- **快照与回滚**：动手前自动存快照，出问题可一键回到改动前。
- **App 级伪装（Zygisk）**：逐应用生效、不需要重启，覆盖 Java 层与 native
  `__system_property_find` 两条读取路径。
- **方案与分享码**：一套机型配置可保存、可导出给别的机器复现。
- **完全离线**：机型库内置在 APK 里，不联网、不上传任何数据。

### 说明

- 逐应用伪装只打包 `arm64-v8a` 原生库；其他架构可安装，但只有不依赖原生库的功能可用。
- 系统级伪装需要 root（Magisk / KernelSU / APatch 任一，需要其 `resetprop`）。

[Unreleased]: https://github.com/xuzhang-android/DeviceToolbox/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/xuzhang-android/DeviceToolbox/releases/tag/v1.0.0
