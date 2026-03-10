status: draft

# 远端 Emulator Eval 迁移评估 (Codex)

Date: 2026-03-09  
Target host: `qiguo-ld1`  
Goal: 把 Android Agent 的 AndroidWorld eval 从本地 laptop 迁到家里的 `qiguo-ld1`，降低本地内存压力，并为后续双 emulator 并行 eval 留出容量。

## 1. 结论先说

结论：**值得迁，而且硬件完全够，但当前远端环境还不具备“直接开跑”条件。**

从第一性原理看，这件事的瓶颈不是 CPU 或内存，而是：
1. 远端缺 Android SDK / emulator / AVD。
2. 远端 Java 版本不满足当前 app 构建要求。
3. 现有 eval 启动脚本默认按“本地桌面机”假设写，远端长期运行最好补 headless 路径。

因此，这不是“不可行”，而是一个**中等环境搭建问题**。

补充判断：
1. **短期不必先升级系统**，先尝试在现有 `Ubuntu 18.04` 上把远端 eval 跑起来更稳。
2. **长期应该计划升级**，因为 `18.04` 对未来 Android SDK / emulator 的兼容漂移只会越来越大。

## 2. 已验证基线

### 2.1 远端机器资源

已通过 SSH + Tailscale 实测：

- Host: `qiguo-ld1`
- CPU: `Intel i9-7900X`, 20 logical CPUs
- Memory: `62G`
- Disk free: `~525G`
- Virtualization: `VT-x`
- `/dev/kvm` 存在

这说明远端机器从硬件层面明显强于当前 laptop，适合承载：
1. 单 emulator eval
2. 双 emulator parallel eval
3. baseline prep 这种重 I/O + 长时任务

### 2.2 远端访问链路

已验证：

- 公网 SSH 可达：`67.164.34.234:222`
- `tailscaled` 已安装并开机自启
- Tailscale IPv4: `100.79.206.115`
- 从当前 Mac 通过 Tailscale SSH 到 `qiguo-ld1` 成功

这意味着后续远端 eval worker 的运维入口已经稳定，不再依赖公网端口转发。

### 2.3 当前仓库 eval 真实依赖

仓库里现有 eval 不是“只要 Python venv 就行”的任务。

从 `scripts/prepare_baseline.sh`、`scripts/eval_parallel.sh`、`eval/aw_bridge/runner_preflight.py` 可以确认，eval 依赖：

1. 本机 `adb`
2. 本机 Android emulator
3. 本机 AVD (`AndroidWorldAvd`, `AndroidWorldAvd2`)
4. 本机 localhost gRPC 端口（如 `8554`, `8556`）
5. eval Python 环境 `eval/.venv`
6. Android app APK 构建与安装能力

结论：**正确迁移方式是把整套 eval 运行面一起迁到远端**，而不是本地 runner 去遥控远端 emulator。

## 3. 当前阻塞项

### 3.1 Android 工具链缺失

远端当前不存在：

- `adb`
- `emulator`
- `sdkmanager`
- `avdmanager`

这意味着远端还没有 Android SDK / command-line tools，也没有 AVD。

### 3.2 Java 版本不够

远端当前是 `OpenJDK 11`。  
但当前项目在 `app/build.gradle.kts` 中要求：

- `sourceCompatibility = JavaVersion.VERSION_17`
- `targetCompatibility = JavaVersion.VERSION_17`
- Kotlin `jvmTarget = 17`

结论：远端如果要自己执行 `./gradlew assembleDebug` 或 `./scripts/setup.sh`，必须先补 `JDK 17`。

### 3.3 远端还没有现成工作目录

当前没有在常见路径下发现 repo checkout，也没有看到现成 Android SDK 根目录。  
因此迁移不是“切个环境变量”，而是要把 worker 环境完整 provision 一次。

### 3.4 现有脚本偏本地桌面机假设

当前 emulator 启动路径（例如 `scripts/eval_parallel.sh` 与 `runner_preflight.py`）默认参数是：

- `-avd`
- `-port`
- `-grpc`
- `-no-snapshot`
- `-no-boot-anim`

但没有统一的 headless 参数（例如 `-no-window`）入口。

虽然 `qiguo-ld1` 当前机器上确实有 `Xorg/gnome-shell` 运行，但 SSH 会话里 `DISPLAY` 为空。  
这说明：

1. 短期可以先依赖已有桌面环境试跑。
2. 长期更稳的做法是给脚本补 headless 支持。

### 3.5 远程升级系统本身有运维风险

如果现在直接在远端做发行版升级，真正的风险不是“升级命令跑不动”，而是**升级后机器无法自动恢复到当前可登录状态**。

潜在故障面包括：

1. `sshd` 没有正常启动
2. `tailscaled` 没有正常启动，或网络起来前时序有问题
3. 网络配置变化导致 Tailscale 没回来
4. 重启后 DHCP 分配变化，导致 Google Wifi 端口转发目标失效
5. 升级中断或升级后需要人工处理包冲突 / 服务失败

结论：**在没有带外控制台、没有现场人手、没有确认 DHCP reservation 的前提下，远程做系统升级有把自己锁在门外的真实风险。**

### 3.6 LLM proxy 拓扑需要和运行位置一起设计

当前你主要测两类模型：

1. `gpt-*`：`provider = OPENAI`
2. `qwen3.5`：`provider = OPENROUTER`

从代码可确认：

1. `qwen3.5` 走 `OPENROUTER`，只依赖 `OPENROUTER_API_KEY`
2. `OPENAI_BASE_URL` 只影响 OpenAI provider 路径
3. eval 启动时会把 `OPENAI_BASE_URL` 从 `.env` 读出来并透传给 app
4. 如果 `OPENAI_BASE_URL` 里写的是 `localhost` / `127.0.0.1`，bridge 会自动改写成 emulator 里的 `10.0.2.2`

这件事的关键含义是：

1. `10.0.2.2` 永远表示“运行该 emulator 的宿主机 loopback”
2. 它不能直接代表 tailnet 上的另一台机器
3. 因此一旦 eval 从 laptop 挪到 `qiguo-ld1`，`OPENAI_BASE_URL=http://10.0.2.2:18080/v1` 就不再指向你 laptop 上的 proxy，而是会指向 `ld1` 自己

结论：**如果只想在一台机器上跑 proxy，远端 eval 的 base URL 方案必须重设计。**

## 4. 可行性判断

### 4.1 能不能跑？

**能。**

硬件已经满足，而且比本地 laptop 更适合。

### 4.2 现在能不能直接跑？

**不能。**

当前缺的不是小修小补，而是完整的 remote worker 基础环境：

1. JDK 17
2. Android SDK / platform-tools / emulator / system image
3. AVD 创建
4. repo checkout
5. `eval/.venv`
6. `.env` / API key / backend 配置

### 4.3 值不值得迁？

**值得。**

原因很直接：

1. 你当前 laptop 已经被 emulator 内存占用拖慢。
2. `qiguo-ld1` 有 62G 内存，天然适合双 emulator。
3. eval / baseline prep / autotune 本来就是长任务，放远端更合理。
4. 你已经有 Tailscale，远程运维成本很低。

### 4.4 现在该不该先升级系统？

**不该。**

当前最合理的顺序是：

1. 先在 `Ubuntu 18.04` 上验证“远端 smoke eval 能不能跑”
2. 如果 Android SDK / emulator 兼容性开始频繁卡住，再把系统升级提到前面
3. 等你有更强的回连兜底条件后，再把 `22.04 LTS` 作为长期目标

原因很简单：

1. 当前远端最大的确定性收益来自“把 eval 从 laptop 挪走”
2. 远程升级系统的收益是中期收益，不是立即收益
3. 远程升级的失败代价比“先试跑 18.04 上的 emulator”大得多

## 5. 推荐迁移路线

## Phase 1: 先做“远端单机可跑”

目标：在 `qiguo-ld1` 上跑通单 emulator smoke eval。

步骤：

1. 安装 `JDK 17`
2. 安装 Android command-line tools
3. 安装：
   - `platform-tools`
   - `emulator`
   - 对应 API level 的 `platforms`
   - AndroidWorld 使用的 `system-images`
4. 创建 `AndroidWorldAvd`
5. clone repo 到远端
6. 创建 `eval/.venv` 并安装 eval 依赖
7. 配置 `.env`
8. 跑一次 `./scripts/prepare_baseline.sh`
9. 跑 `eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_smoke.txt`

验收标准：

1. emulator 能稳定启动
2. `adb` 和 gRPC 端口可用
3. baseline prep 能完成
4. smoke eval 能出 `eval/results/<timestamp>/summary.json`

## Phase 2: 补全远端双 emulator 能力

目标：让 `qiguo-ld1` 成为标准 parallel eval worker。

步骤：

1. 创建第二个 AVD：`AndroidWorldAvd2`
2. 分别完成两套 baseline prep
3. 跑 `./scripts/eval_parallel.sh eval/config/aw_subset_smoke.txt`
4. 记录 wall-clock、成功率、infra failure

预期：这台机器比 laptop 更适合长期运行 `eval_parallel.sh`。

## Phase 3: 把远端运行做成稳定工作流

目标：减少每次远端跑 eval 的手工操作。

建议：

1. 补一个远端 worker runbook
2. 固化 Android SDK 路径、JDK 路径、`ANDROID_HOME` / `ANDROID_SDK_ROOT`
3. 给 emulator 启动脚本增加 headless 模式
4. 视需要增加 tmux/systemd 包装，避免 SSH 断开中断长任务

## Phase 4: 固化单 proxy 工作流

目标：`gpt-*` 模型只跑一个 proxy server，不在两边都维护。

建议优先级如下：

### 方案 A（推荐默认）：proxy 只跑在一台机器上，另一台通过 Tailscale IP 直接访问

假设 proxy 继续跑在 laptop：

1. laptop 本地 eval 继续用 `OPENAI_BASE_URL=http://10.0.2.2:18080/v1`
2. `qiguo-ld1` 上的远端 eval 改成 `OPENAI_BASE_URL=http://<laptop-tailscale-ip>:18080/v1`
3. proxy 监听地址不能只绑 `127.0.0.1`，至少要能被 tailnet 上其他设备访问

优点：

1. 只有一个真实 proxy server
2. 不需要额外 tunnel 进程
3. `qwen` / `OPENROUTER` 路径完全不受影响

缺点：

1. 本地与远端 `.env` 中的 `OPENAI_BASE_URL` 不同
2. 最好使用字面量 `100.x` Tailscale IP，而不是依赖 emulator 里的主机名 / MagicDNS 解析

### 方案 B（推荐备选）：proxy 只跑一台，另一台建立本地转发，保留 `10.0.2.2` 语义

假设 proxy 跑在 laptop，`ld1` 上建立一个长期 SSH/Tailscale tunnel：

1. `ld1` 上开本地转发：`localhost:18080 -> laptop:18080`
2. `ld1` 上的 eval 仍可写 `OPENAI_BASE_URL=http://10.0.2.2:18080/v1`
3. 因为 emulator 内的 `10.0.2.2` 指向 `ld1` 宿主机 loopback，实际会命中本地 forward，再转发到 laptop proxy

优点：

1. 只有一个真实 proxy server
2. 本地和远端都可以保留同样的 `10.0.2.2:18080` 语义
3. 不需要在 app / eval 里区分“本地 URL”和“远端 URL”

缺点：

1. 需要长期维护一个 tunnel 进程
2. tunnel 掉了，`gpt-*` eval 会直接失败
3. 如果要稳定运行，最好做成 `autossh` / `systemd --user` 服务

### 方案 C（不推荐）：两边各跑一个 proxy

这当然最直观，但和目标相反：

1. 配置重复
2. 版本漂移风险更高
3. 排障面更大

### 当前推荐

我当前推荐顺序是：

1. **先用方案 A**
2. 如果发现 emulator 到对端 Tailscale IP 的路径在 DNS / 路由上不够稳定，再退到方案 B

原因：

1. 方案 A 最简单，最少状态
2. 方案 B 虽然更“统一”，但多了一个长寿命 tunnel 进程，运维复杂度更高
3. `qwen` 走 OpenRouter，本来就不依赖这个 proxy，因此真正需要解决的只有 `gpt-*` 路径

## 6. 关键风险

### 6.1 Ubuntu 18.04 较老，emulator 兼容风险是中等到中高

远端当前是 `Ubuntu 18.04.5 LTS`。  
Android SDK / emulator 新版本在这个系统上未必总是最顺滑，可能会遇到：

1. 较新的 glibc / 依赖要求
2. command-line tools 或 emulator 包兼容性问题
3. 某些图形/音频依赖缺失
4. 新版 emulator 可以下载，但二进制启动失败
5. Android Studio/SDK 的“当前官方支持面”逐步偏离 `18.04`

这个风险的定性不是“高概率完全跑不了”，而是：

1. **短期 smoke run：中等风险，值得先试**
2. **长期持续跟随最新 emulator：中高风险**
3. **长期无人值守远端 worker：中高风险**

因此，`18.04` 可以作为**短期过渡环境**，但不应该被当成长期稳定基线。

### 6.2 Headless 路径未标准化

当前仓库没有明确的“远端无头 emulator”标准入口。  
如果后续要让这台机子长期后台跑 eval，最好显式支持：

1. `-no-window`
2. 必要时 `-no-audio`
3. 可配置的 emulator args

### 6.3 远端与本地环境漂移

一旦本地和远端使用不同：

- JDK 版本
- Android SDK 版本
- emulator 版本
- system image

就可能造成 baseline 或稳定性差异。  
因此远端 worker 最好被视为一个明确版本化的执行环境。

### 6.4 远程升级系统可能导致 SSH / Tailscale 双失联

远程升级系统最大的风险不是升级本身，而是**重启后你无法再连回机器**。

当前回连路径有两条：

1. `Tailscale`
2. Google Wifi 端口转发下的公网 SSH

但两条都不是绝对保险：

1. `tailscaled` 理论上会作为 systemd 服务自动启动，但升级后仍可能因为网络、服务依赖或包状态异常而没有恢复
2. 公网 SSH 依赖 Google Wifi 的端口转发仍然指向正确的内网 IP；如果机器重启后 DHCP 变化，没有 reservation，转发可能直接失效
3. 从 `18.04` 往上升级不是一步到位，而是多跳过程，风险高于普通重启

结论：如果没有现场兜底，不应该把“远程发行版升级”放在远端 eval 迁移的第一步。

### 6.5 单 proxy 方案的网络风险

如果坚持“只有一个地方跑 proxy”，需要额外接受一层网络依赖：

1. 当 proxy 在 laptop，上面跑 `gpt-*` 的远端 eval 会依赖 `ld1 -> laptop` 的 Tailscale 连通性
2. 当 proxy 在 `ld1`，本地 laptop 上的 `gpt-*` eval 则反过来依赖 `laptop -> ld1` 的 tailnet 连通性
3. `qwen` / OpenRouter 不受这条链路影响，因此两个模型的故障模式会分叉

这意味着：

1. “qwen 正常、gpt 全挂” 很可能是 proxy / Tailscale 路径问题
2. “本地 gpt 正常、远端 gpt 全挂” 很可能是远端 `OPENAI_BASE_URL` 配置问题
3. `10.0.2.2` 配置如果原样复制到远端，几乎一定会指错机器

因此，单 proxy 路线是可行的，但要把它当成**一条显式网络依赖**来管理，而不是默认认为 `localhost` 语义会自动跨机器成立。

## 7. 建议的最小实施顺序

如果只走最短路径，建议按下面顺序做：

1. **不要先升级系统**
2. 在 `qiguo-ld1` 安装 `JDK 17`
3. 安装 Android SDK + emulator + `adb`
4. clone 当前 repo
5. 跑通单 AVD baseline prep
6. 先确定单 proxy 方案（优先方案 A）
7. 跑通 smoke eval
8. 再做第二个 AVD
9. 最后再补 headless/worker 文档和脚本清理

这是最小风险、最少返工的顺序。

如果后续确实需要升级系统，前置条件至少应包括：

1. 先做一次普通远程重启演练，确认 `sshd` 和 `tailscaled` 都会自动恢复
2. 确认 Google Wifi 对该机器已经做了固定内网 IP 映射 / reservation
3. 保留公网 SSH 和 Tailscale 两条独立回连路径
4. 最好有现场人手或其他带外恢复手段

## 8. 一句话结论

`qiguo-ld1` 是一个合适的远端 eval worker，迁移方向正确；  
当前差的是环境 provisioning，不是能力上限。  
先在现有系统上把它跑成“单 emulator smoke 可用”，而不是先远程升级系统；  
等兼容性真的开始卡住，再把系统升级提上日程，是最稳的路线。
