# 拆分
我想把mobile_action里的非screen operation的action拆分出来，比如wait和system button。
- 这样mobile_action里包含(或许可以改名叫screen_action)的都是需要操作屏幕的actions，这类需要targeting/grounding capability。
- 另外一边都是确定性的actions，比如wait、system button。

如图所示，这是我画的示意图，具体参数列表和名称以实际代码为准，这里是illustrative purpose
```mermaid
flowchart TD
  %% ========== Screen Actions ==========
  subgraph SA[mobile_action]
    direction TB

    subgraph Tap[点击类]
      direction TB
      click[click]
      dclick[double-click]
      lpress[long-press]
    end

    subgraph Input[输入类]
      direction TB
      type[type]
    end

    subgraph Gesture[滑动类]
      direction TB
      swipe[swipe]
    end
  end

  %% ========== Non-screen Actions ==========
  wait[wait]
  sysbtn[system button]

  %% ========== Target (UI element locator) ==========
  subgraph T[Target]
    direction TB
    elementIndex["element_index<br/>(a11y-reliant?)"]
    resid["resource_id<br/>(a11y-reliant?)"]
    coord["coordinate [x, y]"]
    bound["bound [[x1,y1],[x2,y2]]"]
    txt["text"]
    txtidx["text_index"]
  end

  %% ========== Notes ==========
  noteReliability{{这两个靠谱吗？考虑一下是否拿掉}}
  elementIndex --- noteReliability
  resid --- noteReliability

  %% ========== Param blocks ==========
  typeParams["input_text<br/>clear?"]

  swipeStartEnd["[start, end]"]
  swipeDirDist["direction<br/>distance?"]
  swipeDur["duration?"]

  %% ========== Links ==========
  click --> T
  dclick --> T
  lpress --> T

  type --> T
  type --> typeParams

  swipe --> swipeStartEnd
  swipe --> swipeDirDist
  swipe --> swipeDur
  swipe -. optional .-> T
```

# targeting
现在的targeting是挺复杂，但是我也搞不清好不好用。你可以分析一下 @debug-output下面的raw a11y tree 和 processed a11y tree，然后告诉我有些field是不是可以删掉。比如 resource_id如果几乎不出现，那可以删掉。