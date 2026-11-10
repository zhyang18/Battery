# 项目开发与测试规范

## 单元测试执行规范
- **严禁无差别执行全量单元测试**：修改代码后，严禁直接运行全量单元测试套件（如无参数的 `./gradlew testDebugUnitTest`），杜绝因全量编译与回归导致的漫长等待。
- **强制执行针对性精准单元测试**：每次代码修改后，仅针对本次修改涉及的核心业务类、估算器或变更模块执行定向单元测试。
  - 精准测试示例：
    ```powershell
    $env:JAVA_HOME="C:\soft01\Android\android-studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.battery.analysis.PowerDischargeMetricsViewBinderTest"
    ```
    或针对特定测试方法：
    ```powershell
    $env:JAVA_HOME="C:\soft01\Android\android-studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.battery.analysis.RemainingRuntimeEstimatorTest.testDegradedBatteryHardwareReadingCappedByFcc"
    ```
- **全量测试触发限制**：仅在用户明确发出全量回归走查指令、发版前核验或关键里程碑交付时，方可执行全量测试。
