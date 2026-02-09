## Prompt Construction

Following OpenAI Response API call convention, not strictly, illustrative purpose only

# Instruction (can also be first )
system prompt for each agent

# Tools
allowed tools spec

# Inputs
{% history %}
  {% turn 1: %}
    - message (user):[real user request] and [screen status summary]
    - message (assistant): xxx
    - tool_call: 
    - tool_call_result
  
  {% turn 2: %}
    - message (user): [screen status summary]
    - message (assistant): xxx
    - tool_call
    - tool_call_result

  ...
  {% turn N (last turn): %}
    - message (user): [screen status summary]
    - message (assistant): xxx
    - tool_call
    - tool_call_result


{% memory %}
    {% scratchpad, as a user message %}
    Scratchpad general prompt
    keys: ....

    {% todo list, as a user message %}
    Todo list general prompt
    1. ... done
    2. ... in-progress
    3. ... not_started

    Current_subgoal: 3 ...

{% current turn: %}
    message (user): 
    - [full screen status, with at least one of a11y tree and image]
    - input_text: general prompt + a11y tree
    - input_image: screenshot image (optionally compressed)

# Task

0. [prompt] 我想让我的每个turn的prompt结构像上面一样。当然你可以critisize我提出更好的建议。
1. [code] 我的prompt building现在有很多spaghetti code，乱的要死，都是屎山。给我重新按上面的顺序要实现一下。这是一个kind-of orthogonal to agent prompt improvement的任务，但是可能code复杂度也跟我现在的prompt组织本身logic比较乱有关。我想我的prompt building code简单明了，层数尽可能少，follow KISS principles! (Keep It Simple Stupid!!!)

2. [prompt] system reminder 要删一删， only keep minimal if needed。这个设计本身就很反KISS principle，。
3. [prompt/code] tool不用返回screen state了，每个turn开始capture一下，放到user message就行了。tool return它本来就要return的一些meta的东西，不再做屏幕观测。tool observation里面也有screen state，这个以后是不是不用返回，就不需要了？
4. [prompt] 是不是该保留last N(现在可以先hardcode到3)个turn的full_screen_state，而不是只保留最后一次。
