# Tuning Principles

Every change during autotune must pass these gates:

## 1. Anti-overfit

Does this help real users, not just eval tasks?

- Core prompt changes must be generally applicable across apps and tasks.
- App skill changes should cover more than the single eval task that triggered them.
- If a proposal only helps one narrow eval case, do not add it.

## 2. Token Minimalism

Is every token earning its keep?

- Core prompt target: about 80-100 lines.
- App skills target: under 20 lines when possible because they load every turn in the foreground app.
- Say the same thing in fewer words whenever clarity stays intact.

## 3. Generalization

Eval tasks are training data, not the final target.

- Ask whether the change would also help an unseen real-user task in the same app or workflow family.
- Prefer stable workflow rules and grounding rules over task-specific patches.
- If the proposal improves the benchmark but weakens real-user behavior, reject it.
