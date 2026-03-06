# Review: Phase 1 Eval Acceleration

## Summary

Reviewed only these uncommitted files:

- `eval/aw_bridge/runner.py`
- `eval/aw_bridge/runner_preflight.py`
- `eval/aw_bridge/parallel_runner.py`
- `eval/config/default.yaml`
- `eval/tests/test_runner.py`
- `eval/tests/test_runner_preflight_policy.py`
- `eval/tests/test_parallel_runner.py`

Targeted tests passed:

```text
pytest -q eval/tests/test_runner.py eval/tests/test_runner_preflight_policy.py eval/tests/test_parallel_runner.py
56 passed in 0.29s
```

## Critical

None.

## High

1. `runner.perform_bridge_setup=false` is no longer honored in parallel mode. `create_worker_config()` hard-disables the flag for every worker (`eval/aw_bridge/parallel_runner.py:188-193`), but `main()` still unconditionally calls `_build_and_install_bridge_once_per_device()` (`eval/aw_bridge/parallel_runner.py:587-588`). `_build_summary_config()` then rewrites the summary back to `perform_bridge_setup=true` (`eval/aw_bridge/parallel_runner.py:526-540`). The result is a real behavior change: a caller who explicitly disables bridge setup still pays the build/install cost and gets misleading metadata. This needs to be gated on the effective base config, with a regression test that covers `perform_bridge_setup: false`.

2. Parallel runs silently drop the existing `auto_start_emulator=true` behavior. The default config still enables auto-start (`eval/config/default.yaml:17-25`), and the only code path that actually starts a missing emulator lives in `run_android_world_connectivity_preflight()` (`eval/aw_bridge/runner_preflight.py:283-287`). But worker configs now force `auto_start_emulator=false` (`eval/aw_bridge/parallel_runner.py:191-193`), and the new centralized install step happens before any connectivity preflight (`eval/aw_bridge/parallel_runner.py:587-595`). So a config that can boot its emulator in serial mode now fails in parallel mode unless devices were already started out-of-band. Either the supervisor needs to run per-device connectivity/startup before install, or it must preserve worker auto-start until an equivalent supervisor-side path exists. There is also no test covering this regression.

## Medium

1. `summary.json` no longer records the effective CLI-overridden config. Worker configs correctly apply `--suite`, `--n-task-combinations`, and `--task-random-seed` in `create_worker_config()` (`eval/aw_bridge/parallel_runner.py:197-202`), but `_build_summary_config()` only rewrites `output_root` and `perform_bridge_setup` (`eval/aw_bridge/parallel_runner.py:526-540`). That means the summary can disagree with the run that actually executed, which is a debugging/autotune regression even though metrics are unaffected. A small unit test around summary-config generation would catch this.

## Recommendation

CHANGES_REQUESTED

## Post-Debug TODO

1. After the current dual-emulator debug work is complete and `eval_parallel` is confirmed working end-to-end, fold the operational knowledge back into the repo instead of leaving it only in run logs/chat context. Concretely: fix any remaining startup/prep gaps in `scripts/eval_parallel.sh` and `scripts/prepare_baseline.sh`, and update the relevant skill/docs so the documented path reliably boots both emulators, prepares their baselines, and then runs `eval_parallel` without manual recovery steps.
