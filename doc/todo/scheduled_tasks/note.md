# Scheduled Tasks

- 用户设定定时任务：cron 式 schedule + natural language instruction
- 例："每天早上 9 点查看日历并发 summary"、"每周五下午清理下载文件夹"
- 执行引擎：AlarmManager / WorkManager 触发 → 启动 agent session → 执行 → 记录结果
- UI：任务列表、创建/编辑/删除、执行历史
- 失败处理：retry policy、通知用户
