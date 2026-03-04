# group 4 round 3 fix and run

1. 我们当前关注的任务是：
eval/config/aw_subset_group_4_round1_reeval.txt里失败的任务。分析在doc/todo/0.01_eval_tune/group4/round2/eval_analysis_20260227_222506/common_problems_claude.md。你帮我fix这些issue。 follow sop/code_work.md。
    - 把 doc/todo/0.01_eval_tune/group4/round2/eval_analysis_20260227_222506/common_problems_claude.md中failed tasks写到eval/config/aw_subset_group_234_round3.txt，过程中exclude掉eval/config/cannot_handle_group.txt中的任务(这是已知当前无法完成的任务列表，没必要浪费资源去跑)。

2. 用eval runner重新跑eval/config/aw_subset_group_234_round3.txt。`eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_group_234_round3.txt`. 
    - 注意，过程中间,task之间可能会产生stall。比如不知道为什么丢掉accessibility权限,这时候会卡住。如果在一个任务等待卡很久,那你可能需要再去手动给Android的Agent App grant一下accessibility permission，可能甚至需要你手动停止runner process再重跑一遍(这时候不要再重跑已经跑完的任务，缩小你的任务集合，跑剩余任务)。make sure跑完全部任务。
    - 这个issue应该是已经fix了，letting you know just in case。
3. 在eval runner跑的过程中，针对跑完的任务，用 /cog-tune skill分析每一个task的每一个turn。写到doc/todo/0.01_eval_tune/group4/round3/eval_analysis_[new run foldername]/per_task/*_claude.md
- 总结全部的single task analysis，归纳common problems，提出修改建议。写到doc/todo/0.01_eval_tune/group4/round3/eval_analysis_[new run foldername]/*_claude.md。

# eval analysis after run
- 我用eval runner重新跑了eval/config/aw_subset_group_1.txt。结果在eval/results/[TODO: last run foldername]/.
- 用 /cog-tune skill分析每一个task的每一个turn。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/per_task/*_codex.md
- 总结全部的single task analysis，归纳common problems，提出修改建议。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/*_codex.md。
- 过程中不要参考doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/ 下的*_claude.md文件，保持分析的独立性和客观性。

