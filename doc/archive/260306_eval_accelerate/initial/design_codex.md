status: draft

# Autotune Eval 加速设计 (Codex)

Date: 2026-03-05  
Goal: 把一轮 autotune eval（典型 20 tasks）从 1-2 小时降到可稳定复现的 30-60 分钟，并保留现有 AndroidWorld + native bridge 评测语义。

## 1. 问题重述（第一性原理）

你真正要优化的不是“脚本并发数”，而是**单位 wall-clock 时间内完成的有效 task 数**，同时不牺牲：
1. 评测语义一致性（同一套 runner + scoring）
2. 稳定性（timeout/infra flake 不显著上升）
3. 成本可控（本地优先，云端按需）

## 2. 现状结论

## 2.1 已有并行代码并非空壳

- `eval/aw_bridge/parallel_runner.py` 已实现。
- `eval/tests/test_parallel_runner.py` 存在，且本地执行通过（41 passed）。
- 但当前缺少端到端验证沉淀与使用文档；`autotune` skill 仍默认调用串行 `runner.py`。

## 2.2 当前实现的关键性能风险

`parallel_runner` 每个 shard 都起一个 `runner.py` 子进程，而 `runner_preflight.build_and_install_bridge()` 在每个 worker 内都会执行：
1. `:app:assembleDebug`
2. `adb install -r -t app-debug.apk`

结果：并行时会重复构建/安装，放大 CPU、I/O 和 adb 竞争，可能抵消并行收益。

## 2.3 你的机器容量（本机实测）

- CPU: 8 logical cores
- 内存: 16 GB
- AVD 配置：`hw.ramSize=1536M`, `hw.cpu.ncore=4`
- 当前单 emulator 进程 RSS 约 986 MB（不含宿主侧额外开销）

结论：本机更适合 **2 并发 emulator 作为稳定起点**；3+ 很容易进入内存压缩/调度争抢，导致 tail latency 变差。

## 3. 目标与非目标

## 3.1 目标

1. 在本地先实现稳定提速：P50 墙钟时间下降 >= 40%，成功率下降 <= 5pp。
2. 让并行 runner 成为 autotune 的“可选标准路径”（不是一次性脚本）。
3. 给出云端并行方案与成本模型，明确哪些可直接复用现有 harness。

## 3.2 非目标

1. 不重写成 Appium/Espresso-only 的评测体系。
2. 不做分布式多主机调度平台（v1）。
3. 不追求理论最大并发，优先稳定吞吐。

## 4. 设计方案（本地优先）

## 4.1 设计决策

1. **保留 `runner.py` 单设备语义**，并行仅做外层编排。
2. **把重复 preflight 成本前移并去重**：构建一次、每设备安装一次。
3. **资源门控先于并发**：先算可承载 worker，再分片。
4. **失败隔离**：任一 shard 失败不影响其他 shard 收敛与汇总。

## 4.2 目标架构

```text
autotune
  -> parallel_runner.py
      -> Stage A: global setup (build once)
      -> Stage B: per-device setup (install once/device, health check)
      -> Stage C: launch N runner workers (skip build/install)
      -> Stage D: merge per_task + summary
```

## 4.3 Worker 生命周期（状态机）

1. `Pending`
2. `DeviceReady`（adb + grpc + boot completed）
3. `Running`（runner task loop）
4. `Completed` 或 `Failed`
5. `Merged`（结果被并行总汇器收敛）

任何 `Failed` 都不阻断其他 worker 进入 `Completed/Merged`。

## 4.4 具体改动点

1. `eval/aw_bridge/runner.py`
- 新增配置/CLI 开关：`runner.skip_bridge_build_install`（默认 `false`，保持兼容）。

2. `eval/aw_bridge/runner_preflight.py`
- `run_preflight_checks()` 中按开关跳过 `build_and_install_bridge()`。

3. `eval/aw_bridge/parallel_runner.py`
- 新增 `--build-once`（默认开启）：
  - 在 supervisor 层只执行一次 `assembleDebug`
  - 对每个 device 执行一次 `adb install`
  - worker 配置注入 `skip_bridge_build_install=true`
- 新增 `--max-workers auto|N`
  - `auto` 根据资源估算并发上限（见 4.5）

4. 文档/流程
- `eval/README.md` 增加并行 runner 使用与排障章节
- `.ai-dev/skills/autotune/SKILL.md` Step 3 增加并行示例命令

## 4.5 资源门控（KISS 版本）

`max_workers_auto = min(device_count, cpu_cap, mem_cap)`

- `cpu_cap = max(1, floor(logical_cpu / 3))`
- `mem_cap = max(1, floor((total_mem_gb - reserve_gb) / per_emulator_gb))`
- 建议默认：`reserve_gb=4`, `per_emulator_gb=2.5`

对你这台机器：
- `cpu_cap = floor(8/3)=2`
- `mem_cap = floor((16-4)/2.5)=4`
- 结果：`max_workers_auto = min(device_count, 2, 4)`，建议先跑 2 并发。

## 4.6 分片策略

v1 继续 deterministic round-robin（已实现）。  
v2 增加时长感知（基于历史 `per_task.jsonl` 的 task p50）以减少最后一个慢 shard 的拖尾。

## 5. 云端方案评估（低成本视角）

假设：20 tasks * 5 min = **100 device-minutes / round**。

| 方案 | 官方计费/配额 | 对当前 harness 适配性 | 估算成本（100 device-min） | 结论 |
|---|---|---|---:|---|
| Firebase Test Lab | 虚拟设备 `$1/device-hour`，按分钟计费；还提供 no-cost 配额（虚拟设备 60 分钟/天） | 低。主路径是 Robo / Instrumentation / Game Loop（上传 app/test 工件），不是现成 `runner.py + AndroidWorld env` 直连 | 约 `$0.67`（若当日免费 60 分钟可用）到 `$1.67` | 最便宜，但需要改评测接入方式，非 drop-in |
| AWS Device Farm | `$0.17/device-minute`（public devices） | 中低。围绕 Appium/Espresso/内建测试，需按其测试模型接入 | 约 `$17` | 价格明显高于本地/Genymotion，不建议优先 |
| Genymotion SaaS | 文档显示 Pay-as-you-go `$0.06/min`；支持 `gmsaas ... adbconnect` | 中高。更接近“可控 emulator + ADB”模式，但默认并发上限（文档示例默认 2）和镜像/app 基线要单独处理 | 约 `$6` | 云端 burst 首选候选，先做小规模 PoC |
| 自建云 VM + nested virt | IaaS 机时 + 自维护 | 中。可复用现有 harness，但运维复杂；nested virt 有性能损耗 | 与机型相关 | 适合长期大规模，短期不如本地+小规模 SaaS |

补充：GCP 官方文档明确 nested virtualization 会有性能下降（通常 10%+），这会吃掉一部分“云端并行”收益。

## 6. 推荐路线

## Phase 1（立即做，1-2 天）

目标：本地 2 并发稳定跑通并优于串行。

1. 实现 build/install 去重（4.4）
2. 增加 `--max-workers auto`
3. 编写并行使用文档 + autotune skill 示例
4. 做 6-10 task A/B（串行 vs 2 并发）并记录：
- wall clock
- success rate
- timeout/infra failure rate

验收：
- wall clock 降低 >= 40%
- success rate 下降 <= 5pp

## Phase 2（可选，1 天）

目标：减少拖尾并提高吞吐稳定性。

1. 时长感知分片
2. shard 级健康指标（启动耗时、每 task 吞吐、失败原因聚类）

## Phase 3（可选，2-4 天）

目标：云端 burst 能力（只在本地机器不够时启用）。

1. 先做 Genymotion 2-4 并发 PoC（最小任务集）
2. 验证 ADB 连接、应用基线准备、评分一致性
3. 达标再做混合调度（本地 + 云）

## 7. 风险与缓解

1. 并行导致 flake 上升  
缓解：默认 2 并发 + 失败隔离 + shard 级日志。

2. adb/server 竞争造成假超时  
缓解：限制并发、提升 adb 命令超时、避免重复 install/build。

3. 云端环境与本地基线不一致导致分数漂移  
缓解：先做小样本一致性校验（同 tasks 同 seed）。

## 8. 成功标准（Definition of Done）

1. `parallel_runner` 有明确文档与 autotune 接入示例。
2. 本地 2 并发在至少两轮 eval 中稳定达标（见 Phase 1 验收）。
3. 形成云端使用决策阈值（例如：本地预计 > 75 分钟才触发云 burst）。

## 9. 参考资料

- Firebase Pricing: https://firebase.google.com/pricing  
- Firebase Test Lab docs: https://firebase.google.com/docs/test-lab  
- AWS Device Farm Pricing: https://aws.amazon.com/device-farm/pricing/  
- AWS Device Farm test types: https://docs.aws.amazon.com/devicefarm/latest/developerguide/test-types.html  
- Genymotion SaaS docs: https://docs.genymotion.com/paas/01_Getting_Started/02_Genymotion_SaaS/  
- GCP nested virtualization overview: https://cloud.google.com/compute/docs/instances/nested-virtualization/overview  

