# Notification Handling

- 监听 NotificationListenerService → 按用户定义的规则触发 agent 响应
- 例："收到 WhatsApp 来自老板的消息时，自动回复'收到'"
- 规则引擎：app filter + keyword filter + sender filter → trigger action
- 安全：用户 explicit opt-in 每条规则，不默认处理所有通知
- 隐私：通知内容仅在设备上处理，发给 LLM 推理遵循用户选择的 API provider
