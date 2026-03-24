# Security Hardening

- API key 存储从明文 SharedPreferences 迁移到 EncryptedSharedPreferences
- 关闭 cleartext traffic（`usesCleartextTraffic="false"`）
- InsecureSslConfig 门控为 debug-only（生产环境不跳过证书验证）
- `allowBackup=false` + 配置 `dataExtractionRules`
- `.env` 不进 repo（.gitignore + 清理历史如有必要）
