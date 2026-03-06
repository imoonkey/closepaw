App 距离能作为产品上架 Google Play Store 或开源 repo 发布，还有一段距离。
代码质量和架构是 ok 的，主要缺的是发布基础设施。

按优先级拆成 5 个子任务：
1. Legal - LICENSE、第三方归集
2. Release Build - 签名、R8、版本管理
3. Privacy - 隐私政策、无障碍服务说明
4. Open Source - README、CONTRIBUTING、CI/CD
5. Play Store - 商店素材、审核材料

两条发布路径：
- 开源发布（补 1 + 4）：~2-3 天
- Play Store 上架（全部）：~1-2 周
