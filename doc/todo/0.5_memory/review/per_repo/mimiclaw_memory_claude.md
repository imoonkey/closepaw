# mimiclaw Memory System Analysis

## 1. Product层面

### Memory分类
mimiclaw 是一个 **ESP32 嵌入式设备**上的记忆系统，运行在 SPIFFS 文件系统上。记忆分两类：

**长期记忆（MEMORY.md）:**
- 持久化的用户信息和学习到的知识
- 初始内容为空："MimiClaw will write memories here as it learns"

**每日记忆（YYYY-MM-DD.md）:**
- 当天的对话笔记和事件

### 每类结构
纯文本 Markdown 文件，无结构化 schema。受 SPIFFS 文件系统限制（扁平目录，有限存储空间）。

## 2. System层面

### 架构
```
memory_store.c / memory_store.h
├── memory_store_init() — 初始化 SPIFFS
├── memory_read_long_term() — 读 MEMORY.md
├── memory_write_long_term() — 写 MEMORY.md
├── memory_append_today() — 追加当日笔记
└── memory_read_recent() — 读取最近 N 天
```

纯 C 实现，极简接口。

### 存储/索引
- **SPIFFS 文件系统**：ESP32 的 flash 存储分区
- **扁平目录**：SPIFFS 不支持真正的目录结构
- **无索引**：纯文件顺序读取
- **存储路径**：`MIMI_SPIFFS_BASE`（配置常量）

### 写入方法
- `memory_write_long_term(content)` — 覆盖写入 MEMORY.md
- `memory_append_today(note)` — 追加到当日文件（自动创建带日期标题）
- 标准 C `fopen/fputs/fclose`，无特殊序列化

### 检索方法
- `memory_read_long_term(buf, size)` — 读取整个 MEMORY.md 到 buffer
- `memory_read_recent(buf, size, days)` — 连续读取最近 N 天文件，以 `\n---\n` 分隔
- **无搜索能力**：只能全文读取
- Buffer 大小限制（嵌入式环境的内存约束）

### 写入时机
- 由调用方决定（LLM agent 或固件逻辑）
- 无自动提取或触发条件
- 基于 `localtime_r()` 获取当前日期

## 3. Lifecycle层面

### 淘汰/上限
- **SPIFFS 空间限制**是天然上限（通常几百KB到几MB）
- **无自动淘汰**
- `memory_read_recent()` 通过 `days` 参数控制读取范围（默认3天）
- 旧文件需手动清理或由上层逻辑管理

### 去重/合并
- **无去重**
- `memory_append_today()` 只追加，不检查重复

### 时间衰减
- **无时间衰减**
- `days` 参数间接限制了可见的历史范围

## 4. Injection层面

### Token预算
- **无 token 预算**
- 受限于 buffer size（C 函数的 `size` 参数）
- 嵌入式设备的 RAM 是天然的 token 预算约束

### 分级加载
- 简单两级：long-term（MEMORY.md）+ recent daily（最近 N 天）
- 调用方决定加载哪些

### 作用域隔离
- **单设备单用户**：ESP32 设备级别隔离
- 无多用户支持

## 5. Abstraction层面

### 反思/提炼
- **无反思/提炼**
- 存储层完全被动，不做任何内容处理
- 所有智能逻辑在调用方（LLM agent）

### Working Memory ↔ Long-term Memory
- **Working Memory** = RAM 中的 buffer（C 程序的局部变量）
- **Long-term Memory** = SPIFFS 上的文件
- 极其简单的模型：读文件 → buffer → 处理 → 写文件
- **特点**：为嵌入式环境设计的最小实现，是 nextclaw 模式的 C 移植版
