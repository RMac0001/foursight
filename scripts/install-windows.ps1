# Installs the FourSight command and its scheduled tasks for the current Windows user.
#
#   powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1
#
# What it changes (all per-user; nothing system-wide, no admin rights):
#   %USERPROFILE%\FourSight\app    the built app plus a Java runtime for the command (replaced on reinstall)
#   %USERPROFILE%\FourSight\bin    foursight.cmd, added to the user PATH
#   %USERPROFILE%\FourSight\logs   output of the scheduled runs (no tokens or secrets)
#   Task Scheduler \FourSight\      "Daily collection" at -DailyAt, and "Day-7 check" every hour
# The database (%USERPROFILE%\FourSight\foursight.db) and Credential Manager entries are untouched.
# Not under AppData: Windows hides a packaged app's AppData writes (the Claude app's) from other programs.
# scripts\uninstall-windows.ps1 undoes all of it.
param(
    [string]$DailyAt = "6:00AM",
    [switch]$NoTasks
)
$ErrorActionPreference = "Stop"

$repo = Split-Path $PSScriptRoot -Parent
$root = Join-Path $env:USERPROFILE "FourSight"
$app = Join-Path $root "app"
$bin = Join-Path $root "bin"
$logs = Join-Path $root "logs"
$main = "io.github.rmac0001.foursight.desktop.MainKt"

Write-Host "Building FourSight..."
& (Join-Path $repo "gradlew.bat") -p $repo ":desktopApp:createDistributable" ":desktopApp:cliRuntime" --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "The build failed." }
$built = Join-Path $repo "desktopApp\build\compose\binaries\main\app\FourSight"
$runtime = Join-Path $repo "desktopApp\build\cli-runtime\image"
if (-not (Test-Path (Join-Path $runtime "bin\java.exe"))) { throw "The build made no command-line Java runtime at $runtime." }

Write-Host "Installing to $app..."
# A running FourSight (the MCP server the Claude app starts, or a scheduled run) holds the old jars
# open, and removing the folder would fail halfway. Stop only processes running from this install;
# the Claude app reconnects after it's restarted.
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { $_.CommandLine -like "*$app\*" }
foreach ($p in $running) {
    Stop-Process -Id $p.ProcessId -Force -Confirm:$false
    Write-Host "Stopped a running FourSight (process $($p.ProcessId)). Restart the Claude app to reconnect it."
}
if (Test-Path $app) { Remove-Item $app -Recurse -Force }
New-Item -ItemType Directory -Force $root, $bin, $logs | Out-Null
Copy-Item $built $app -Recurse
Copy-Item $runtime (Join-Path $app "cli-runtime") -Recurse

$java = Join-Path $app "cli-runtime\bin\java.exe"
$javaw = Join-Path $app "cli-runtime\bin\javaw.exe"
$classpath = Join-Path $app "app\*"
$cmd = "@echo off`r`n`"$java`" -cp `"$classpath`" $main %*`r`n"
[System.IO.File]::WriteAllText((Join-Path $bin "foursight.cmd"), $cmd, [System.Text.Encoding]::ASCII)

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$parts = @($userPath -split ";" | Where-Object { $_ -ne "" })
if ($parts -notcontains $bin) {
    [Environment]::SetEnvironmentVariable("Path", (($parts + $bin) -join ";"), "User")
    Write-Host "Added $bin to your user PATH (new terminals pick it up)."
}

if (-not $NoTasks) {
    # Windowless (javaw) so no console flashes up; output goes to the log.
    function New-Run([string]$cliArgs, [string]$logName) {
        New-ScheduledTaskAction -Execute $javaw `
            -Argument "-cp `"$classpath`" $main $cliArgs --log `"$(Join-Path $logs $logName)`""
    }
    # Runs only while Roger is signed in (Credential Manager needs his session); catches up after the
    # PC was off; never two at once.
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

    Register-ScheduledTask -TaskPath "\FourSight\" -TaskName "Daily collection" -Force `
        -Action (New-Run "collect" "collect.log") -Trigger (New-ScheduledTaskTrigger -Daily -At $DailyAt) `
        -Settings $settings -Principal $principal | Out-Null

    $nextHour = (Get-Date).Date.AddHours((Get-Date).Hour + 1)
    Register-ScheduledTask -TaskPath "\FourSight\" -TaskName "Day-7 check" -Force `
        -Action (New-Run "collect --day7-due" "day7.log") `
        -Trigger (New-ScheduledTaskTrigger -Once -At $nextHour -RepetitionInterval (New-TimeSpan -Hours 1)) `
        -Settings $settings -Principal $principal | Out-Null
    Write-Host "Scheduled: daily collection at $DailyAt, day-7 check every hour."
}
Write-Host "Done. Open a new terminal and run: foursight help"
