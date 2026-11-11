# 贡献指南

这个项目只有一个维护者，代码量不大但每一处都踩过真机器的坑。为了让 PR 能快速合进来，
下面几条是硬要求，其余都好商量。

## 提 PR 之前

```bash
# 1. 机型库必须自洽（改了 devices.tsv 就一定要跑）
python3 app/tools-src/check-fingerprints.py --cover

# 2. 改了构建脚本，先过语法
bash -n app/build.sh module/build.sh
```

两样都过不了就先别开 PR —— CI 也会跑同一套（见 `.github/workflows/ci.yml`）。

## 改机型库（`app/assets/devices.tsv`）

- **13 列，制表符分隔，不写表头。** 列顺序：
  `品牌 制造商 通俗名 机型码 代号 产品名 版本 构建号 内部版本 指纹 补丁 芯片 平台`
- **不许编值。** 没有真值就留空 —— 空值会被程序按品牌与型号推导，编一个假值会污染整条身份。
- 指纹的 8 段必须与同一行的 `品牌 / 产品名 / 代号 / 版本 / 构建号 / 内部版本` 逐项一致，
  `check-fingerprints.py` 会逐行比对（B/C 两项）。
- 「半族」行不允许：要么整行有指纹，要么 `构建号` 和 `内部版本` 都留空。
- 数据来源请写进 `THIRD-PARTY.md`，或者在你的 PR 描述里给链接。

## 改代码

- 目标是 **Java 8 + 无第三方依赖**：不用 Gradle、不引 `androidx`、不加 `build.gradle`。
  构建走 `app/build.sh`（aapt2 + javac + d8 + apksigner），这是刻意的选择 ——
  整套构建能在手机上跑完。
- 原生部分只用 C++20 的**无异常无 RTTI**子集（`-fno-exceptions -fno-rtti -nostdlib++`），
  不要引入需要 `libc++_shared.so` 的东西，否则 Zygisk 从任意目录 `dlopen` 会缺库。
- 界面代码集中在 `app/java/.../ui/`，属性与流程在 `core/`。改引擎不要顺手改界面。
- 注释与提交信息用中文可以，变量名和 API 名保持英文。

## 提交信息

沿用 `type(scope): 说明` 的形式，type 用 `fix` / `feat` / `chore` / `docs` / `refactor` /
`perf` / `test` / `build`。scope 写模块名（`app`、`module`、`ci`、`db`、`ui`、`engine`）。

```
fix(engine): 六分区属性表按目标机型实时算条数，不再用写死的数字
feat(db): 补 2026 年旗舰机型 42 条，全部带真实指纹
docs(readme): 补英文版与反馈渠道
```

一个 PR 只做一件事。混着「顺手重构」的 PR 会被要求拆开。

## PR 自查清单

- [ ] `check-fingerprints.py` 通过（改了机型库时）
- [ ] `bash -n` 通过（改了构建脚本时）
- [ ] 在真机上验证过：目标机型写入后 `getprop` 与「关于手机」口径一致
- [ ] 涉及写入逻辑的改动，说明了失败时怎么回滚（快照是否仍然有效）
- [ ] 没有把密钥、口令、设备序列号、个人 token 带进 diff
- [ ] 新增的字符串如果有对外文案，中英文都写了

## 合并之后

CI 会在 `main` 上跑机型库校验与脚本语法检查；打 `v*` tag 会触发构建，
产物（APK + Zygisk 模块 zip）自动挂到 Release 上。版本号改 `app/AndroidManifest.xml`
里的 `versionName`，同时补 `CHANGELOG.md`。

## 安全相关

能导致属性注入、路径穿越、任意命令执行的问题，**不要开公开 PR 或 issue**，
走 [SECURITY.md](SECURITY.md) 里的私密通道。
