# Note 1
1. LLMClient该是哪个层次的config？是不是可以作为agent runtime的一部分？就是agent config会指定model name之类的，然后runtime的时候会initialize对应的client。不需要centralized registry和管理？这样合理吗？ 
    - prefer：我想让一个agent可以有一个自己的model。
2. LLMClient内部API层面，有一些开源模型的serving平台，提供openai api，但是我不确定他们是只支持旧的ChatCompletion API，还是也支持新的Response API (我现在的OpenAI Client实现)。这个可能要再开始design前测一测，再决定下面怎么走。
    - prefer： 好的，我们先只支持openai compatible apis，实现两个client（一个已有的response api版本的，一个旧的chatcompletion版本的）。

# Note 2
我已经决定了，不用LangChain4j或者其他第三方library了。简化处理。
1. support other general LLM: qwen, kimi, glm etc. This can be done through OpenRouter or other providers that provide openai compatible api. Then I only need to change base_url and api_key of my existing OpenAI client.
2. support some LLMs that are not available on OpenRouter. Some vLLM self-serving or other serving platform only provide openai chat completion api, but not the response api. So the easiest way is to implement a new client that supports chat completion api using the OpenAI SDK.
3. 我想要一个config system, based on a json file.
Configuration should be injectable and distinct for different agent roles.

### `llm_model_config.json` / Environment
key是model name, value是model config. key在我的系统里面是unique的。
```json
{
  "gpt-4o": {
    "display_name": "GPT-4o",
    "provider": "OPENAI", 
    "model": "gpt-4o",
    ... (other parameters)
  },
  "glm-4.7": {
    "display_name": "GLM-4.7",
    "provider": "OPENROUTER", // 根据provider去找对应的api_key和base_url，或者base_url在model config里面(you decide)
    "model": "z-ai/glm-4.7",
    "base_url": "https://openrouter.ai/api/v1",
    ... (other parameters)
  },
}
```


4. 前端和debug-run.sh
- 支持setup：主agent模型(standalone模式的唯一agent，和planner-executor模式的planner)，和executor-agent模型（仅planner-executor模式有意义）。
