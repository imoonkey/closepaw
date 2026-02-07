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
    A ... done
    B ... in-progress
    C ... not_started

    Current_subgoal: B ...

{% current turn: %}
    - message (user): [full screen status, with at least one of a11y tree and image]
    input_text: general prompt + a11y tree
    input_image: screenshot image (optionally compressed)

TODO:是不是该保留last n=3 full_screen_state，而不是只保留最后一次？