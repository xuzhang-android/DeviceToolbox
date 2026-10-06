# 改机型工具箱 · Zygisk 原生模块

Xposed 只能改 Java 层，native 代码走 `__system_property_find` 读的是 bionic 直接映射的
属性区内存 —— 那不走函数调用，hook 不到，只能改内存。这个模块就是干这个的。

## 三层

1. **root companion**：模块跑在目标应用进程里（无 root），读不到 `/data/adb`。
   用 `connectCompanion()` 拿一个通往 root 侧进程的 socket，从那里取配置。
2. **JNI**：在 `preAppSpecialize` 阶段（应用代码还没跑）直接改 `android.os.Build`
   的静态字段 —— MODEL/BRAND/DEVICE/FINGERPRINT/HARDWARE/BOARD + VERSION.*
3. **属性区私有副本**：把 `/proc/self/maps` 里 `/dev/__properties__/*` 的映射
   换成 `MAP_PRIVATE|MAP_FIXED` 副本，再用 `__system_property_find` 拿到 `prop_info*`
   原地写值，按 bionic 协议更新 serial。**只影响本进程**，别的应用和 `getprop` 完全不变。

Java 与 native 两条路一起改，进程内才不会出现「两个来源对不上」。

## 编译（设备内，不需要电脑）

```bash
bash build.sh        # clang++ 21.1.8，target aarch64-linux-android24
```

## 打包

```
pkg/
├── module.prop
└── zygisk/
    ├── arm64-v8a.so          # 27 KB
    └── libc++_shared.so      # rpath 用 $ORIGIN
```

## 待接

- App 侧把配置写到 `/data/adb/devicetoolbox/zygisk.conf`（每行 `包名<TAB>键<TAB>值`）
- 装机测试
