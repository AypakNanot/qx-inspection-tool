# 停止巡检工具：按命令行匹配 jar 名，避免误杀其它 Java 进程
# 注意 CommandLine 形如 java -jar "D:\...\xxx.jar"（结尾有引号），模式必须以 * 收尾
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { $_.CommandLine -like '*mstp-inspect*.jar*' }

if (-not $procs) {
    Write-Host '未找到运行中的巡检工具进程'
    exit 0
}

foreach ($p in @($procs)) {
    Stop-Process -Id $p.ProcessId -Force
    Write-Host "已停止 PID $($p.ProcessId)"
}
