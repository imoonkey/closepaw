# Security Hardening

- API key 存储从明文 SharedPreferences 迁移到 EncryptedSharedPreferences
> SGTM

- 关闭 cleartext traffic（`usesCleartextTraffic="false"`）
> cleartext traffic是因为我之前用本地llm proxy，不支持https。
> 我现在有个localhost支持https的方法。看~/workspace/workflow，我可以用https://laptop.tail6bd948.ts.net/。通过tailscale实现的。为了让androidagent的llm proxy也可以，可能要配合~/workspace/cproxy的修改和tailscale的设置。同时我想让workflow的上面继续可以work，不知道这里会不会有冲突(e.g., they all want port 80)。

>> **[回复]** 看了 cproxy——它是 HTTP proxy 跑在 `127.0.0.1:18080`。Tailscale 的 HTTPS 方案（`laptop.tail6bd948.ts.net`）是 Tailscale 自动签 TLS cert 后反代到本地端口，不占 80/443。所以不冲突：workflow 和 cproxy 可以各自映射到不同的 Tailscale hostname 或同一 hostname 的不同 path。具体做法：(1) cproxy 继续监听 18080，(2) Tailscale serve 把 `https://laptop.tail6bd948.ts.net/cproxy/` 反代到 `localhost:18080`，(3) workflow server 用另一个 path 或端口。实现时在 `tailscale serve` 配置里加一条就行。这个改动属于 infra，不影响 androidagent 代码，只需要把 androidagent 里 LLM endpoint 配置从 `http://...` 改成 `https://laptop.tail6bd948.ts.net/...`。可以在 security hardening 实现时一起做。
>> 注意参考~/workspace/REMOTE_SERVER_ACCESS.md，可能有有用信息

- InsecureSslConfig 门控为 debug-only（生产环境不跳过证书验证）
> SGTM

- `allowBackup=false` + 配置 `dataExtractionRules`
> 这是啥？

>> **[回复]** Android 默认会把 SharedPreferences 等 app 数据自动备份到 Google Drive（`allowBackup="true"`）。现在 Manifest 里是 `true`，意味着用户的 API key（即使迁移到 EncryptedSharedPreferences 后）和 session history 都会被备份到 Google 云端。`allowBackup=false` 关掉整个自动备份。`dataExtractionRules` 是更精细的方案（Android 12+）：用 XML 声明哪些文件可以备份、哪些排除（比如排除 `shared_prefs/` 但允许备份 session history）。建议直接 `allowBackup=false`，简单安全，我们的 app 没有用户需要云备份的数据。

> ok, 可以关掉云备份。

- `.env` 不进 repo（.gitignore + 清理历史如有必要）
> SGTM. only .env.example should get in. 现在.env在repo吗？应该不在吧？在的话把它从git trace移除掉。

>> **[回复]** 确认 `.env` 不在 git 里（`.gitignore` 已有 `.env` 规则，`git ls-files .env` 返回空）。不需要清理历史。实现时加一个 `.env.example` 就行。

- 上面是一些基础的。还有一些关于agent security的。就是什么app默认不能用(or 每一步都要问user），比如涉及到钱的银行、券商、crypto交易app。什么app。我现在code里的policy等等，ask user etc. 这里也需要认真重读一下代码，然后从第一性原理思考，harden一下。this is bigger than the above simple security stuff。需要/double-design。

>> **[回复]** 同意这是个独立的、更大的设计课题。基础 security hardening（上面 5 项）是工程实现，直接做。Agent security policy 是产品/安全设计问题——需要定义 risk taxonomy、app 分类、default policy、用户 override 机制。已启动 `/double-design`，输出到 `doc/todo/security_hardening/agent_security/`。
