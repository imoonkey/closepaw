
# mem
我想给我的 Android agent 设计一套记忆系统。你帮我看看我的 .reference/mem/ 底下的这些不同的 memory system 都是怎么设计的。不需要 get into details，只需要 analyze 每一个的对于 agent memory 的 high-level 理解。它们是怎么设计他们的架构的。给我挨个梳理一下。

还有就是包括 .reference/other/openclaw 底下，看看 openclaw memory system 是怎么设计的，这些都给我总结一下。先不着急设计我的 memory system，先把这些好的 memory system 都总结一下。 写到doc/todo/0.5_memory/ref_analysis/。


repo:
- openclaw 自己
- 针对openclaw的：memu，值得研究。文件即memory？

general memory:
- mem0：好像更传统？
- supermemory: 这个fact/preference/...的分类很好。