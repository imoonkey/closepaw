## Task 1: VD模式结束导致app中止
see ./screenshot_vd_stop.png。现在在 Virtual Display 模式下，一个task结束之后，比如说我播放一个 YouTube 歌曲，它会在task结束后停止播放。我在打开 YouTube 的时候需要重新播放它。这可能与一些 Task Life Cycle Management 有关，在任务结束的时候，可能会把虚拟屏幕上的进程停止掉。 这个问题有更好的设计来解决吗？


## Task 2: VD模式在主屏幕弹出键盘
See ./screenshot_inputpop.png. 比如在 Virtual Display 模式下，虚拟屏幕上需要进行搜索时，点击输入框就会弹出键盘。然而，这个键盘会弹到我的主屏幕上。对于用户体验来说，这有点 weird。比如在主屏幕上平时平行干别的事，忽然弹出来一个键盘。这个有什么办法可以 fix 吗？ 