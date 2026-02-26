# full-extra20 Eval after extra20_after_P12_fix
1. 用eval runner重新跑eval/config/aw_subset_group_1.txt。`eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_group_1.txt`. 注意，过程中间,task之间可能会不知道为什么丢掉accessibility权限,这时候会卡住。如果在一个任务等待卡很久,那你可能需要再去手动给Android的Agent App grant一下accessibility permission，可能甚至需要你手动停止runner process再重跑一遍(这时候不要再重跑已经跑完的任务，缩小你的任务集合)。这个issue应该是已经fix了，letting you know just in case。跑完这20个任务。
- 用 /cog-tune skill分析每一个task的每一个turn。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[new run foldername]/per_task/*_claude.md
- 总结全部的single task analysis，归纳common problems，提出修改建议。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[new run foldername]/*_claude.md。


# eval analysis after run
- 我用eval runner重新跑了eval/config/aw_subset_group_1.txt。结果在eval/results/[TODO: last run foldername]/.
- 用 /cog-tune skill分析每一个task的每一个turn。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/per_task/*_codex.md
- 总结全部的single task analysis，归纳common problems，提出修改建议。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/*_codex.md。
- 过程中不要参考doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/ 下的*_claude.md文件，保持分析的独立性和客观性。

