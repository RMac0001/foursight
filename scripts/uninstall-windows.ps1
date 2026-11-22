# Removes what scripts\install-windows.ps1 added for the current Windows user:
#
#   powershell -ExecutionPolicy Bypass -File scripts\uninstall-windows.ps1
#
# Deletes the scheduled tasks, the PATH entry, the FourSight shortcuts, and %USERPROFILE%\FourSight\app and \bin. Keeps the
# database, the logs and the Credential Manager entries; pass -RemoveData to delete the database and
# logs too (Credential Manager entries are always left for Roger to remove himself).
param([switch]$RemoveData)
$ErrorActionPreference = "Stop"

$root = Join-Path $env:USERPROFILE "FourSight"
$bin = Join-Path $root "bin"

foreach ($name in "Daily collection", "Day-7 check") {
    if (Get-ScheduledTask -TaskPath "\FourSight\" -TaskName $name -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskPath "\FourSight\" -TaskName $name -Confirm:$false
        Write-Host "Removed scheduled task: $name"
    }
}

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$parts = @($userPath -split ";" | Where-Object { $_ -ne "" -and $_ -ne $bin })
[Environment]::SetEnvironmentVariable("Path", ($parts -join ";"), "User")

foreach ($folder in [Environment]::GetFolderPath("Desktop"), [Environment]::GetFolderPath("Programs")) {
    $link = Join-Path $folder "FourSight.lnk"
    if (Test-Path $link) { Remove-Item $link -Force; Write-Host "Removed $link" }
}

foreach ($dir in (Join-Path $root "app"), $bin) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force; Write-Host "Removed $dir" }
}
if ($RemoveData) {
    foreach ($item in (Join-Path $root "foursight.db"), (Join-Path $root "logs")) {
        if (Test-Path $item) { Remove-Item $item -Recurse -Force; Write-Host "Removed $item" }
    }
}
Write-Host "Done."
