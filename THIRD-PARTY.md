# 第三方组件与数据来源

本项目在实现过程中参考或使用了下列**公开**资源。感谢这些项目的作者与贡献者。
所有第三方内容均按其自身许可证使用；本项目代码为作者自行编写。

## 代码依赖

| 组件 | 作者 / 项目 | 用途 | 许可证 |
|---|---|---|---|
| `zygisk.hpp` | John "topjohnwu" Wu（Magisk） | Zygisk 模块头文件 | 0BSD/ISC 式宽松许可（保留原版权声明，见文件头） |
| Zygisk / Magisk / KernelSU / APatch / Zygisk Next | 各自社区 | 模块加载与 root 环境 | 各自许可证 |
| Plus Jakarta Sans | Tokotype（Google Fonts） | 界面字体 | SIL Open Font License 1.1（见 `app/assets/fonts/OFL.txt`） |

## 机型数据来源

内置机型库（`app/assets/devices.tsv`）由下列**公开**来源整理、去重、校验而成：

| 来源 | 贡献内容 |
|---|---|
| 本项目整理 | 近年主流机型条目，含中文通俗名与芯片信息 |
| [`xdaGari/tadiphone-buildprop-archive`](https://github.com/xdaGari/tadiphone-buildprop-archive) | 分区级 `build.prop` 归档（最大来源） |
| [`TheFreeman193/PIFS`](https://github.com/TheFreeman193/PIFS) | 真实指纹档案 |
| [`Seyud/device_faker_config`](https://github.com/Seyud/device_faker_config) | 社区机型模板（含 `marketname`） |
| samfrew / sammobile 等公开固件库 | 三星机型 PDA/构建号核对 |

数据的整理规则（写入「项目」文档与代码注释中亦有说明）：
**能用公开真实数据就用真实数据，不重建、不编造**；指纹与其派生的构建号、补丁日期、内部版本号
必须自洽；同一指纹只保留一条记录；地区版本不交叉匹配。

## 思路参考

以下**公开**项目在「Zygisk 逐应用注入 + Java 静态字段与 native 属性区一起改」这条技术路线上
提供了公开的思路参考（本项目实现与代码全部自行编写）：

- [`Seyud/device_faker`](https://github.com/Seyud/device_faker)
- [`Seyud/device_faker_config`](https://github.com/Seyud/device_faker_config)
- Zygisk Next / Magisk / KernelSU 社区文档与 AOSP（bionic 属性区实现）
