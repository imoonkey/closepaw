HistoryManager.compress()需要改进。

在之前的一次 debug 中，发现了 history manager 在 compress 的时候会 drop user input，从而导致了一些 bug。这个行为需要修复一下。

我现在的 history compress 可能不太智能。你帮我看一下 dot reference/ 这个文件夹底下的 coding agent 和 mobile agent 都是怎么处理 history compress 的 （参考sop/adhoc/reference_analysis.md），然后总结一下给我提一些建议。 