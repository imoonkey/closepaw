# Eval as Regression CI

- AndroidWorld full set 作为 regression gate，不再作为优化目标
- CI 集成：PR 跑 eval，regression 才 fail
- 保留 eval infra 不变（runner, bridge, parallel runner, remote worker）
- 停止 autotune optimization loop（R58 是最后一轮主动优化）
