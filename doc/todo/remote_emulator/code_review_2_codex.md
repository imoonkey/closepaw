status: draft

# Remote Emulator Eval Code Review 2 (Codex)

Date: 2026-03-11
Scope: `eval/` remote worker hardening after `947fee6` and `76ee8ba`

## Summary

Current `eval/` changes are not especially hacky. The recent `adb_path` and `OPENAI_BASE_URL` fixes are pragmatic hardening with reasonable boundaries and focused test coverage.

This does not need an immediate broad refactor. The main technical debt is that preflight responsibilities are now too concentrated in one module, so the next time eval infra changes, that file should be split deliberately instead of accepting more growth.

## High

1. `eval/aw_bridge/runner_preflight.py` has become a kitchen-sink module.

It is now about 786 lines and mixes several distinct responsibilities:

- snapshot verification and repair
- task package detection and install fallback
- adb command wrappers
- emulator startup and readiness checks
- bridge APK build/install

That is the strongest signal that refactor is warranted, but it is a structural follow-up task, not an emergency caused by the latest patch.

Recommended split:

- `preflight_snapshots.py`
- `preflight_packages.py`
- `adb_utils.py`
- `emulator_preflight.py`
- `bridge_install.py`

## Medium

1. `eval/aw_bridge/runner.py:_validate_required_api_key()` now mixes credential validation with runtime connectivity checks.

The added `_validate_openai_base_url()` logic is useful and justified for remote worker reliability, but the function name is no longer precise. It currently does both:

- resolve required provider keys from `llm_models.json`
- validate whether `OPENAI_BASE_URL` is reachable from the eval host

This is still acceptable at current scale, but if more provider-specific runtime checks are added, it should be split into:

- `validate_model_credentials(...)`
- `validate_runtime_connectivity(...)`

## Low

1. `scripts/remote/provision.sh` is script-heavy, but that is acceptable.

The pinned emulator install is an ops workaround for Ubuntu 18.04 compatibility, not a major code-structure problem. It is somewhat hacky, but in the right layer, and not worth abstracting unless the project starts supporting multiple worker OS versions or multiple pinned emulator variants.

## Positive Notes

1. `adb_path` handling is placed in the right layers.

- config parsing expands the path in `eval/aw_bridge/runner.py`
- adb execution resolves through shared helpers in `eval/aw_bridge/runner_preflight.py`

That is a clean enough boundary for this fix.

2. The remote hardening has focused test coverage in `eval/tests/test_runner.py`.

The current tests cover:

- configured adb binary usage
- config path expansion for `adb_path`
- provider-key resolution by model provider
- `OPENAI_BASE_URL` reachability failure
- `10.0.2.2` alias normalization

That lowers the risk that these changes are just ad hoc patches with no guardrails.

## Recommendation

Recommendation: APPROVE AS-IS for now.

Do not open a standalone refactor just for cleanliness. Keep momentum on remote eval unless more infra work is already planned.

When the next eval infra change happens, do a targeted refactor first:

1. Split `runner_preflight.py` by responsibility.
2. Rename and separate credential validation from runtime connectivity validation in `runner.py`.

## Verification Notes

This review was based on static inspection of:

- `eval/aw_bridge/runner.py`
- `eval/aw_bridge/runner_preflight.py`
- `eval/tests/test_runner.py`
- `scripts/remote/provision.sh`
- `eval/config/remote.yaml`

No additional test or smoke eval was run as part of this review.
