using System.Runtime.Versioning;
using Xunit.Sdk;
using Xunit.v3;

// Windows 專用；非 Windows 平台上全部測試會 Skip。
[assembly: SupportedOSPlatform("windows")]

// 集成測試會修改 WFP 全局設置（網絡事件收集）並共享系統防火牆狀態，必須串行執行。
[assembly: Parallelization(Mode = ParallelMode.None)]
