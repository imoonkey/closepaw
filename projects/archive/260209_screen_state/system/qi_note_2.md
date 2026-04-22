我的实现是基于 @final_design.md 的。我在想

1. screen-only的时候，可不可以继续采集a11y info，同时加入trace tracking里，这样find accessibility node依然有用。另外debug的时候看trace也有用。
2. screen-only只是在LLM input里的时候把a11y info skip到。
3. screen-unchanged detection逻辑是不是也可以改一下，改成也同时依赖a11y tree info和截图做对比的，pixel-by-pixel可能不太好，容易误报，但是没关系，可以先做一个简单的实现。

帮我分析分析写个plan到 design_stage_2.md 写到跟上面一个folder。 use @.agent/skills/ultra-think 