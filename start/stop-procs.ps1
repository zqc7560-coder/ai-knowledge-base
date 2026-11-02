# 按命令行特征精确停止本项目进程（技能纪律：绝不按进程名杀，防误伤其它 java/node）。
# 匹配规则：
#   java.exe   命令行含 kb-app.jar
#   node.exe   命令行含 ai-knowledge-base 且含 frontend（vite dev server）
#   python.exe/python3.exe 命令行含 relay_proxy.py 或 kb_relay.py（开发期代理中继）
$killed = @()
$targets = @(
    @{ Proc = "java.exe";   Match = "kb-app\.jar" },
    @{ Proc = "node.exe";   Match = "ai-knowledge-base.*frontend" },
    @{ Proc = "python.exe"; Match = "relay_proxy\.py|kb_relay\.py" },
    @{ Proc = "python3.exe"; Match = "relay_proxy\.py|kb_relay\.py" }
)
foreach ($t in $targets) {
    Get-CimInstance Win32_Process -Filter "Name='$($t.Proc)'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match $t.Match } |
        ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            $killed += "{0} pid={1}" -f $t.Proc, $_.ProcessId
        }
}
if ($killed.Count -gt 0) { "killed: " + ($killed -join "; ") } else { "no matching processes" }
# 端口探针：8080/5173 应拒连
foreach ($port in 8080, 5173) {
    $c = Test-NetConnection -ComputerName 127.0.0.1 -Port $port -WarningAction SilentlyContinue
    "port $port listening: " + $c.TcpTestSucceeded
}
