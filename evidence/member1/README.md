# 成员 1 的验证材料

2026-11-06 保存的真实终端截图、生成日志、JUnit 结果和链上测试回执。

- `screenshots/`：12 张截图，包含 4A-1、4A-2、4A-4 的 Service 部分，以及验证记录。
- `records/01-wrappers.log`：实际 Java 封装生成输出。
- `records/05-tests-and-build.log`：通过的测试与构建日志。
- `records/TEST-*.xml`：JUnit 原始结果，6 个单元测试、1 个本地链集成测试。
- `records/06-local-integration.json`：本地链 31337 的账户公开地址、交易哈希和最终状态。
- `records/08-test-summary.txt`：由上述原始结果生成的摘要。
- `records/09-potos-readonly-summary.json`：实验 3 历史合约与代币的只读核验摘要。

本地测试由单一进程模拟三种角色，不能代替三位同学在 POTOS 上的正式协作验收。
4A-4 尚需成员 2 的完整 Controller；4A-3 和 4A-5～4A-8 待实际测试网操作后补齐。

截图由浏览器直接截取本机 ttyd 终端，未拼接或修改图片内容。它们记录的是截图当时的状态；
后续文档更新不会改写这些历史截图。源码和运行时密钥分离，材料中没有私钥。

新后端账户的公开信息及余额核验见 [backend-account.json](../../docs/backend-account.json)。
