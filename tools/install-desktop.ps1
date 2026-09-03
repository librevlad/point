# Установка Point на компьютер (#1408).
#
# MSI поверх работающего Point ставится «успешно» с кодом 3010 и ломает установку при следующей
# перезагрузке: снятие старой версии откладывает удаление занятых библиотек до перезагрузки, а
# новая версия одноимённые файлы не переписывает — после перезагрузки Point остаётся без jar и
# без JVM. Поэтому здесь Point сначала останавливается, после установки проверяется очередь
# удалений Windows и версия установленного продукта, и только потом Point запускается снова.
#
#   powershell -ExecutionPolicy Bypass -File tools/install-desktop.ps1
#   powershell -ExecutionPolicy Bypass -File tools/install-desktop.ps1 -Msi desktop\build\compose\binaries\main\msi\Point-0.3.6.msi -NoStart
#   powershell -ExecutionPolicy Bypass -File tools/install-desktop.ps1 -Check   # только проверить установленное
#
# Коды выхода: 0 — установлено и проверено; 1 — MSI не найден; 2 — msiexec отказал (код в выводе);
# 3 — файлы Point ждут удаления при перезагрузке, выкладка провалена; 4 — версия не совпала.

param(
    [string]$Msi,
    [switch]$NoStart,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'

# Сообщения — в консоль (Write-Host): вывод функции целиком становится её результатом, и код
# выхода утонул бы в строках.
function Report-Installed {
    $pending = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager' -Name PendingFileRenameOperations -ErrorAction SilentlyContinue).PendingFileRenameOperations
    $pendingPoint = @($pending | Where-Object { $_ -like '*\Point\*' }).Count
    $installed = Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall' |
        Where-Object { $_.GetValue('DisplayName') -eq 'Point' } |
        ForEach-Object { $_.GetValue('DisplayVersion') } | Select-Object -First 1
    $jars = @(Get-ChildItem (Join-Path $env:ProgramFiles 'Point\app\*.jar') -ErrorAction SilentlyContinue).Count
    $modules = Test-Path (Join-Path $env:ProgramFiles 'Point\runtime\lib\modules')
    Write-Host ("Установлено: Point {0}; библиотек {1}; ядро Java на месте: {2}; файлов Point в очереди удаления: {3}" -f $installed, $jars, $modules, $pendingPoint)
    if ($pendingPoint -gt 0) {
        Write-Host 'После перезагрузки Point не запустится — поставьте MSI ещё раз этим скриптом после перезагрузки.'
        return 3
    }
    return 0
}

if ($Check) { exit (Report-Installed) }

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    # Установка в Program Files требует прав администратора: перезапускаем себя с ними.
    $args = @('-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath)
    if ($Msi) { $args += @('-Msi', $Msi) }
    if ($NoStart) { $args += '-NoStart' }
    $p = Start-Process powershell -Verb RunAs -ArgumentList $args -Wait -PassThru
    exit $p.ExitCode
}

if (-not $Msi) {
    $root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
    $Msi = Get-ChildItem (Join-Path $root 'desktop\build\compose\binaries\main\msi\Point-*.msi') -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Msi -or -not (Test-Path $Msi)) {
    Write-Output 'MSI не найден: соберите его (:desktop:packageMsi, JDK Temurin 17) или укажите -Msi'
    exit 1
}
# msiexec не открывает путь с прямыми косыми (1619 «пакет не открыть», в логе — «путь пуст»):
# путь приводится к полному виду Windows до передачи.
$Msi = (Resolve-Path $Msi).ProviderPath
$wanted = [IO.Path]::GetFileNameWithoutExtension($Msi) -replace '^Point-', ''
Write-Output ("Ставлю Point {0} из {1}" -f $wanted, $Msi)

# 1. Point не должен работать: занятые файлы и есть причина поломки.
$running = Get-Process Point -ErrorAction SilentlyContinue
if ($running) {
    Write-Output ("Останавливаю Point ({0} процесс.)" -f @($running).Count)
    $running | Stop-Process -Force
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Process Point -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if (Get-Process Point -ErrorAction SilentlyContinue) { Write-Output 'Point не остановился'; exit 2 }
}

# 2. Установка. 3010 («нужна перезагрузка») здесь — не успех: значит, файлы были заняты.
$log = Join-Path $env:TEMP 'point-msi.log'
$msiexec = Start-Process msiexec -ArgumentList '/i', ('"' + $Msi + '"'), '/qn', '/norestart', '/l*v', ('"' + $log + '"') -Wait -PassThru
if ($msiexec.ExitCode -ne 0) {
    Write-Output ("msiexec ответил {0} — лог: {1}" -f $msiexec.ExitCode, $log)
    exit 2
}

# 3. Очередь удалений Windows не должна содержать файлов Point; версия — совпасть с MSI.
$state = Report-Installed
if ($state -ne 0) { exit $state }
$installed = Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall' |
    Where-Object { $_.GetValue('DisplayName') -eq 'Point' } |
    ForEach-Object { $_.GetValue('DisplayVersion') } | Select-Object -First 1
if ($installed -ne $wanted) {
    Write-Output ("Установлена версия {0}, ожидалась {1}" -f $installed, $wanted)
    exit 4
}

# 4. Point снова работает.
$exe = Join-Path $env:ProgramFiles 'Point\Point.exe'
if (-not $NoStart -and (Test-Path $exe)) {
    Start-Process $exe
    Write-Output 'Point запущен'
}
exit 0
