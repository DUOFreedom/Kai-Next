---
name: android-helper
description: Android 手机使用与问题排查技能。用户问手机卡顿/耗电/存储清理/权限设置/应用管理/网络问题等手机相关问题时使用。结合本机工具（android_ui/android_files/android_shell）给出针对性方案。
---

# Android 助手技能

## 排查流程约定
先调用 `android_ui` `dump` 了解设备当前屏幕 → 问题分类 → 给方案。能用工具查证的先查证（如 `android_files` / `android_shell` 看装了什么），不要凭空猜。

## 常见问题速查

### 卡顿
1. 查存储剩余（<10% 会卡）：方案里给清理路径
2. 查后台：开发者选项→正在运行的服务
3. 动画降速：开发者选项窗口动画缩放 0.5x

### 耗电快
1. 设置→电池→耗电排行（看是系统还是应用）
2. 高耗应用→限制后台活动
3. 屏幕：亮度自适应+超时30秒

### 存储清理
优先级：微信存储清理（我-设置-通用）> Download 目录 > 应用缓存。
高危删除（DCIM/WhatsApp 数据库）先警告。

### 网络问题
WiFi：忘记网络重连；移动数据：开关飞行模式。
5G 耗电且信号差时建议切 4G（*#*#4636#*#* 或设置里首选网络类型）。

## 用本机工具时
- `android_files` 查应用与文件：keyword 参数过滤
- `android_shell` 查系统属性：`getprop ro.build.version.release` 等
- 高危命令必须先解释再请求执行
