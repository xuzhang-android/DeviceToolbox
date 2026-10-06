# 改机型工具箱

一个跑在 Android 上的机型伪装工具：改掉系统里跟「这台手机是什么」有关的属性，
并支持开机持久化、快照回滚、App 级伪装与 root 痕迹隐藏。

- 包名 `com.xuzhang.devicetoolbox`
- 最低 Android 5.0（API 21），目标 API 34
- 系统级伪装依赖 root：Magisk / KernelSU / APatch 任一（需要它提供的 `resetprop`）
- App 级伪装依赖 Zygisk（Zygisk Next / KernelSU 自带），逐应用、不需要重启

**本项目由作者手工编写与维护** —— 界面、脚本、原生模块与内置机型库的整理都是自己写的；
路线上的方向参考见文末「思路参考」。

---

## 功能

### 属性档位

属性表按「改完之后有多少地方会露馅」分成三档：

| 档位 | 改什么 |
|---|---|
| 轻量 | 只动「关于手机」直接显示的键：型号 / 品牌 / 制造商 / 设备名 / 代号 |
| 标准 | 把 6 个分区（system / vendor / odm / product / system_ext / bootimage）的机型键全部对齐，并改写各分区的构建指纹 |
| 深度 | 再改构建信息、Android 版本、安全补丁与各分区版本 |

当前版本按**深度**档写入（覆盖最全、露馅点最少）。动手前的确认框会把这次要改的条数报给你：
条数由程序按目标机型实时算出 —— 机型里没有真值的键不写，所以不同机型会略有差异。
界面上显示的就是这一次真实要写的条数，不是写死的宣传数字。

### 机型库

内置机型库 **3,539 条**，其中 **3,312 条带真实指纹（93.6%）**，
覆盖 **278 个品牌**，文件约 **572 KB**（585,835 字节）。目标机型从 Android 5.0（API 21）
一直覆盖到 Android 17。

- 每一条都是**完整可用的身份**：品牌 / 制造商 / 通俗名 / 机型码 / 代号 / 产品名 /
  Android 版本 / 构建号 / 内部版本 / 完整指纹 / 安全补丁 / 芯片 / 平台 —— 不是只有一个名字
- 列表里显示的是**通俗名**（「三星 Galaxy S24 Ultra」），写进系统的是机型码（`SM-S928B`）
- 支持搜索（通俗名 / 机型码 / 芯片）、按品牌筛选、**按 Android 版本筛选**（全部 / 10+ / 12+）、收藏置顶
- 代号不确定的机型留空，由程序按品牌与型号推导 —— 宁可推导，也不编一个假代号
- 列表用 `ListView` + 适配器渲染，只创建可见行（三千多行全量创建会明显卡顿）
- 内置「自定机型生成器」：给品牌 + 型号 + 芯片 + 版本，推导出代号、构建号、安全补丁与完整指纹

#### 数据来源

机型库由下面这些公开来源合并去重而来（保留项目名与链接，本身也是致谢的一部分）：

| 来源 | 贡献内容 |
|---|---|
| 本项目整理 | 2023–2025 旗舰与热门机型，带中文通俗名与芯片信息 |
| [`xdaGari/tadiphone-buildprop-archive`](https://github.com/xdaGari/tadiphone-buildprop-archive) | 最大的来源：44,716 份**分区级** `build.prop` 公开归档 |
| [`TheFreeman193/PIFS`](https://github.com/TheFreeman193/PIFS) | 真实指纹档案 |
| [`Seyud/device_faker_config`](https://github.com/Seyud/device_faker_config) | 公开的机型模板仓库，含 `marketname` |
| 公开固件库（如 samfrew / sammobile 的三星 PDA 码） | 三星机型的构建号与版本信息 |

各来源的口径并不一致：有的只有指纹、有的只有模板、有的字段残缺。合并时按机型码去重，
同一条以「字段更全、且带真实指纹」的那份为准；指纹与本机型的品牌 / 代号 / 版本对不上的，
宁可留空，也不拼一条假的进去。

### 安全护栏

- **还原以「原厂真值」为准**：真值只可能来自**只读的 build.prop 文件**（`/system`、`/vendor`、`/odm`），
  `resetprop` 改不到它们。首次使用时取一份基线落盘，之后所有还原都从基线取 ——
  这修掉了「还原出来还是上次伪装的那个机型」（快照读的是 getprop，也就是当时内存里的值，
  一旦上一次没还原干净，快照存的就是伪装值）
- **改之前自动快照**：把即将被改动的键在改动前的值写成 `restore.sh`，同时留一份 `values.tsv` 期望值清单
- **改完读回校验**：逐键 `getprop` 比对，不一致就如实报告「未生效」，不假装成功
- **还原也读回校验**：还原后拿 `values.tsv` 逐项核对，还原不干净会直接告诉你哪几项没回去
- **原本不存在的属性要删掉而不是写空**：`getprop` 不输出空值，所以「原机没有」与「值是空」
  在快照里都表现为缺失。这类键还原时走 `resetprop --delete` —— 留个空壳属性仍然能被检测到
- **还原只回写自己改过的键**，不动系统其它属性
- 「恢复出厂」等危险操作要过两道确认

### App 级伪装（Zygisk 逐应用）

只对勾选的应用生效。模块在**目标进程 specialize 阶段**（应用的代码还没跑起来之前）
同时改两条路：

- **Java 侧**：替换 `Build` / `Build.VERSION` 的静态字段；
- **native 侧**：在该进程内克隆一份**属性区私有副本**，把 bionic 的属性查找指向这份副本，
  再按 bionic 的协议写值。

于是 `SystemProperties` 与 `__system_property_find` 读到的是同一份假值，
进程内不会出现「两个来源对不上」。**不改系统属性（整机真值原封不动），配置改完也不用重启。**

作用范围按**包名精确匹配**，不是前缀匹配 —— `com.tencent.mobileqq2` 不会被
`com.tencent.mobileqq` 的配置误命中（模块侧拿整串包名比对，不取前缀）。

配置由本应用写进只有 root 能读的模块目录；目标进程自己没有 root、读不到那里，
所以它通过 Zygisk 的 companion socket 向 root 侧的模块进程要
（删除清单走同一条连接的另一趟往返，值域里永远不会出现删除标记）。

> 早先还有一条 Xposed 路，已经**退役**：它只在目标进程里改 Java 层，native 直接读内存绕不过去；
> 而且挂在 LSPosed 里时会因为缺方法抛 `AbstractMethodError`（真机实测）。现在
> `AndroidManifest.xml` 里没有任何 Xposed 模块注册，相关源码与资源都已删除。

### 隐藏 root 痕迹

把「已解锁 / 可调试」这类暴露点写回原厂锁定状态，共 20 项：
`ro.debuggable`、`ro.secure`、`ro.build.type/tags`、`ro.boot.verifiedbootstate`、
`ro.boot.flash.locked`、`ro.boot.vbmeta.device_state`、各分区的 `verifiedbootstate` 等。
动手前自动存快照，「还原」把原值写回。

### 其它

- **持久化模块**：往 root 管理器的模块目录装一个模块，开机早期自动写入
- **方案与分享码**：存成方案随时套用，导出为 `DTB1.xxxx.yyyy` 分享码（带 CRC 校验）
- **三路一致性自检**：`getprop` / `android.os.Build` / 目标值 并排对比
- **明暗主题**，切换不用重启
- **导出独立脚本** 到手机的公共目录，不依赖本 App 也能跑

---

## 设计

界面按 Material 3 组织，全部用代码构建（不用 XML 布局），因此换主题只要重建视图。

几个刻意的取舍：

- **不用实心 1px 灰边**，改带透明度的发丝描边；**不用硬阴影**，深度靠表面色阶
  （深色下是色调抬升，浅色下才加一层极轻的环境阴影）。
- **同一层级的按钮只有一个高度**（52dp），只有填充 / 色调 / 描边 / 文字四种语义区分主次。
- **导航项用固定尺寸的图标容器 + 药丸选中指示器**，图标与文字因此永远在同一中轴线上，
  不受字体基线影响。
- **状态芯片排 2×2**：四个挤一行时最后一个会被压成竖条（这是踩过的坑）。
- **列表用「单容器 + 发丝分隔行」**，不是一摞卡片 —— 三千多台机每台一张卡会碎成一片。
- **标题不带上方小标签**（kicker）：标题自己扛得住；标题上方的留白永远大于下方。
- **状态用图标而不是符号**：收藏星是矢量图，不是 `★` 字符。

配色从应用图标里取样，深色走 OLED 深底，浅色走近白底；正文与次要文字都按 4.5:1 对比度选过。

---

## 构建

仓库分两部分，**在 Android 设备上即可完成构建**，不需要电脑：

```
app/      Android 应用（Java）
module/   Zygisk 原生模块（C++）
```

顺序是**先构建模块、再构建 App** —— App 会把模块产出的同一个 `.so` 作为自己的 native
自检库打进 APK，两条路走的是同一份代码。

### 1. 先构建模块

```bash
cd module
bash build.sh
```

产出 `module/pkg/zygisk/libdtbprobe.so`（同一个二进制另存一份 `arm64-v8a.so`，供 Zygisk
按约定名加载），同时刷新 `module/pkg/module.prop`。

需要 clang：**NDK 的 clang 或 Termux 的 clang** 都可以（脚本按 `aarch64-linux-android24`
目标编译）。`.so` 不链接 libc++，只依赖 `libc` / `liblog`，因此 Zygisk 从哪个目录加载它
都不会缺库。

### 2. 再构建 App

```bash
cd app
bash build.sh
```

按 `app/build.sh` 里的步骤，依次是：

```
aapt2 compile res -o res.zip             # 编译资源
aapt2 link ... --java build/gen          # 链接资源、生成 R.java（--min-sdk-version 21 --target-sdk-version 34）
javac -cp tools/android.jar ...          # 编译 Java
d8 --min-api 21 ...                      # class → classes.dex
zip 追加写入                              # 把 classes.dex 与模块的 .so 打进 APK
apksigner sign --ks <你的密钥> ...        # 签名
```

其中 `.so` 的落点是 `lib/arm64-v8a/libdtbprobe.so`；如果模块还没构建，这一步会提示你
先回去跑 `module/build.sh`。产物是签名好的 APK，可直接安装。

**需要自备三样（仓库里都不含）：**

- **`android.jar`**：约 26 MB，自行放到 `app/tools/android.jar`（或用 `ANDROID_JAR` 指定）
- **`d8.jar` 与 `apksigner.jar`**：Android SDK build-tools 里自带；这两个没有默认值，
  必须用环境变量 `D8`、`APKSIGNER` 给出 jar 的完整路径，否则脚本会直接报错退出
- **签名密钥**：仓库不含任何 keystore，请自建一个，用环境变量传入
  `KEYSTORE`（密钥库路径）、`KS_PASS`（口令）、`KS_ALIAS`（别名），
  别名口令不同时再用 `KEY_PASS`

工具链：`aapt2` / `javac` / `java` / `jar`（另外 `PYTHON` 用于把 `classes.dex` 与
`lib/arm64-v8a/libdtbprobe.so` 追加进 APK，可用 `PYTHON` 指定解释器）。
两个构建脚本都用 `cd "$(dirname "$0")"` 定位自身目录，不写死任何绝对路径，
可以在任意工作目录下调用；脚本开头注释里列出了全部可覆盖的环境变量及其默认值。

---

## 源码结构

```
app/
├── AndroidManifest.xml
├── build.sh                     设备内构建脚本
├── assets/devices.tsv           内置机型库（3,539 条：品牌 / 制造商 / 通俗名 / 机型码 / 代号 / 版本 / 构建号 / 指纹）
├── res/                         资源（矢量图标、颜色、字符串）
├── tools/android.jar            自备，不入库
└── java/com/xuzhang/devicetoolbox/
    ├── MainActivity.java        顶部应用栏 + 底部导航 + 页面宿主
    ├── core/
    │   ├── Sh.java              执行 shell，带超时看门狗
    │   ├── Runner.java          脚本文本落盘后一次性交给 root 执行
    │   ├── Device.java          读当前属性 / Build / root / resetprop
    │   ├── Target.java          目标机型 + 派生值推导（指纹、补丁、构建号…）
    │   ├── Props.java           属性表（轻量 / 标准 / 深度三档）
    │   ├── Scripts.java         各类脚本文本（应用 / 还原 / 模块 / 隐藏 root）
    │   ├── Library.java         内置机型库 + 自定机型生成器
    │   ├── Engine.java          操作层：快照、应用、校验、还原、模块、自检
    │   ├── Zygisk.java          逐应用伪装的配置生成与模块安装
    │   ├── AppSpoof.java        逐应用配置（勾选哪些应用参与）
    │   ├── Store.java           本地持久化
    │   └── Share.java           分享码编解码（带 CRC）
    └── ui/
        ├── Palette.java         Material 3 色彩角色（明 / 暗两套）
        ├── Ui.java              间距 / 字号 / 圆角令牌 + 组件工厂
        ├── Page.java            页面基类
        ├── HomePage / SpoofPage / ModelPage / SchemePage / ToolsPage
        └── Task.java            后台线程 + 回主线程

module/
├── build.sh                     设备内构建脚本
├── jni/
│   ├── spoof.cpp                Zygisk 模块：逐应用入口 + Build 静态字段 + 属性区
│   ├── prop_area.hpp            属性区（bionic property area）的读取与写入
│   └── zygisk.hpp               Zygisk 模块 API 头文件（来自 topjohnwu，见「致谢」）
└── pkg/                         打包产物：module.prop + zygisk/*.so
```

---

## 关于「一致性」——这是这类工具最难的地方

改完属性，很多检测依然能识破，原因不在属性本身，而在**读取时机**：

> `Build.MODEL` / `Build.FINGERPRINT` 这些是 Java **静态字段**，在应用进程启动时就读死了。
> 运行时用 `resetprop` 改的是内存属性区，**只影响之后才启动的进程**；
> 已经开着的应用里，`Build.MODEL` 还是旧值，而 `SystemProperties.get()` 已经是新值 ——
> 同一个进程里两个来源对不上，检测软件一比就露馅。

Momo 这类检测应用会同时打印 `Build.FINGERPRINT` 和 `ro.build.fingerprint`，
两个不一致本身就是信号。

要做到真正一致，属性必须在**应用启动之前**就写好。两条可行路径：

| 路径 | 覆盖范围 | 说明 |
|---|---|---|
| **开机模块**（post-fs-data） | 全设备 | 写入时机早于 zygote，所有进程读到的都是新机型；装完重启一次即永久生效 |
| **App 级伪装**（Zygisk） | 仅被勾选的应用 | 在该应用进程 specialize 阶段、它的代码跑起来之前改掉 Build 与属性区，所以该应用内部完全自洽 |

运行时 resetprop 适合「先看看效果」，但**不要指望它全设备一致** —— 界面上对这点有明确说明。

### 思路参考

[`Seyud/device_faker`](https://github.com/Seyud/device_faker) 与
[`Seyud/device_faker_config`](https://github.com/Seyud/device_faker_config) 等公开项目，
把「Zygisk 逐应用 + Java 静态字段与属性区 COW 一起改」这条路线公开描述了出来：
公开资料里说明了得同时覆盖 Java 层与 native 层，一个进程内才不会出现两套值。
这条路线给了我们方向上的参考。

**本项目的实现与代码全部自行编写**：原生模块、App 的 Java、机型库的整理都是自己写的，
没有沿用任何第三方项目的代码 —— 唯一的外来文件是 `module/jni/zygisk.hpp`（见「致谢」）。
开机模块走的是另一条更朴素但更彻底的路：干脆在 zygote 起来之前就把属性写好。

---

## 使用须知

1. **第一次用要先授权 root**。Magisk / KernelSU 按应用授权，第一次调用 `su` 时管理器会弹窗；
   如果管理器没在运行，请求会一直挂着，首页会显示未获取 —— 打开一次管理器即可。
   首页点「当前设备」那块能看到 `su` 的原始返回，方便排查。
2. **App 级伪装需要装好 Zygisk 模块**（Zygisk Next / KernelSU 自带；装完**重启一次**让
   zygote 加载模块，之后改配置不用重启）；应用内勾选的是「哪些应用参与伪装」。
   **不需要 LSPosed** —— 本应用不向任何 Xposed 框架注册模块。
3. **改属性有风险**。个别应用依赖机型信息做校验，改完可能行为异常。用「还原原始」可回退。
4. 还原用的是**快照**。从没成功存过快照时，「还原原始」会明确告诉你没有可用快照，而不是瞎写一气。
5. 卸载持久化模块后，**重启**才会失效（当前已生效的属性不会立刻变回去）。

---

## 许可证

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整条款见仓库根目录 [`LICENSE`](LICENSE)。

Copyright (C) 2026 xuzhang-android

你可以自由使用、修改和再分发本项目；但**衍生作品必须以相同许可开源**，并保留版权与许可声明。

在真机上改动系统属性属于有风险操作，请自行评估；因使用本工具产生的一切后果由使用者承担。

---

## 致谢

- `module/jni/zygisk.hpp` 来自 **John "topjohnwu" Wu**（Magisk 作者）。该头文件以
  0BSD / ISC 式的宽松许可发布，允许自由使用、修改与再分发，本项目原样引用。
- **Magisk / KernelSU / APatch / Zygisk Next** 生态：系统级写入依赖它们提供的 `resetprop`，
  逐应用伪装依赖 Zygisk 的模块接口；没有这套生态，这个工具无从谈起。
- **机型库的公开数据来源**：
  - [`xdaGari/tadiphone-buildprop-archive`](https://github.com/xdaGari/tadiphone-buildprop-archive)
    —— 44,716 份分区级 `build.prop` 公开归档，本库最大的来源
  - [`TheFreeman193/PIFS`](https://github.com/TheFreeman193/PIFS) —— 真实指纹档案
  - [`Seyud/device_faker_config`](https://github.com/Seyud/device_faker_config)
    —— 公开的机型模板仓库（含 `marketname`）
  - 公开固件库（如 samfrew / sammobile 的三星 PDA 码）—— 三星机型的构建号与版本信息
- [`Seyud/device_faker`](https://github.com/Seyud/device_faker) —— 「Zygisk 逐应用 + Java
  静态字段与属性区一起改」这条路线上的公开描述，给了我们方向参考
- 以及所有把这些公开数据整理、留档下来的人。
