---
name: python-data
description: Python 数据处理技能。沙盒内使用 python3 执行 CSV/JSON 读取与 pandas 清洗，输出摘要表。用户要求数据清洗、分析、格式转换时使用。
---

# Python 数据处理技能

沙盒内使用 `execute_shell_command` 调用 `python3` 执行。

## 流程
1. `python3` 读取 CSV/JSON 文件
2. pandas 清洗（去重、空值处理、类型转换）
3. 输出摘要表

## 用本机工具时
- `execute_shell_command` 运行 `python3` 脚本，示例：

```sh
python3 -c "
import pandas as pd
df = pd.read_csv('/sdcard/Download/data.csv')
print(df.describe())
"
```

- 文件读写配合 `android_files` 使用（路径需在 /sdcard、/storage 或 /mnt/media_rw 下）
- 高危操作（如删除数据）先向用户说明并获得确认
