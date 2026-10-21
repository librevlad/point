# Установщик сам закрывает работающий Point (#1408).
#
# jpackage снимает старую версию в самом начале установки (RemoveExistingProducts стоит перед
# CostInitialize), и при открытом Point Windows откладывала удаление занятых jar и самой Java
# до перезагрузки: после неё Point не запускался. Свой шаблон WiX через Compose 1.7 не
# подложить — он сам передаёт jpackage папку шаблонов и чистит её перед запуском. Поэтому в
# готовый MSI дописываются два шага прямо перед снятием старой версии: путь к taskkill.exe и
# сам taskkill по Point.exe. Point не запущен — taskkill отвечает ошибкой, установка её не ждёт.
#
#   powershell -ExecutionPolicy Bypass -File desktop/msi/close-point-before-upgrade.ps1 -Msi <путь к .msi>

param([Parameter(Mandatory = $true)][string]$Msi)

$ErrorActionPreference = 'Stop'

$installer = New-Object -ComObject WindowsInstaller.Installer
$path = (Resolve-Path $Msi).ProviderPath

function Invoke($target, $name, $arguments) {
    $target.GetType().InvokeMember($name, 'InvokeMethod', $null, $target, $arguments)
}

function Get-Prop($target, $name, $arguments) {
    $target.GetType().InvokeMember($name, 'GetProperty', $null, $target, $arguments)
}

function Exec($db, $sql) {
    $view = Invoke $db 'OpenView' @($sql)
    Invoke $view 'Execute' $null | Out-Null
    Invoke $view 'Close' $null | Out-Null
}

function Run($db, $sql) {
    $view = Invoke $db 'OpenView' @($sql)
    Invoke $view 'Execute' $null | Out-Null
    $rows = @()
    while ($true) {
        $record = Invoke $view 'Fetch' $null
        if ($null -eq $record) { break }
        $rows += , (Get-Prop $record 'IntegerData' @(1))
    }
    Invoke $view 'Close' $null | Out-Null
    return , $rows
}

# 1 = msiOpenDatabaseModeTransact: правки ложатся только на Commit.
$db = Invoke $installer 'OpenDatabase' @($path, 1)

if ((Run $db "SELECT ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Action`` = 'PointClose'").Count -gt 0) {
    Write-Output "Point уже закрывается этим установщиком: $path"
    exit 0
}

$remove = (Run $db "SELECT ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Action`` = 'RemoveExistingProducts'")[0]
if ($null -eq $remove) { throw "В $path нет RemoveExistingProducts — шаблон jpackage сменился, проверьте порядок" }

# Два свободных номера прямо перед снятием старой версии.
$taken = Run $db "SELECT ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Sequence`` >= $($remove - 2) AND ``Sequence`` < $remove"
if ($taken.Count -gt 0) { throw "Номера $($remove - 2)..$($remove - 1) перед RemoveExistingProducts заняты" }

# 51 — задать свойство; 50 + 64 — запустить exe из свойства и не ждать успеха.
Exec $db "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('PointTaskkillPath', 51, 'POINT_TASKKILL', '[SystemFolder]taskkill.exe')" | Out-Null
Exec $db "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('PointClose', 114, 'POINT_TASKKILL', '/F /T /IM Point.exe')" | Out-Null
Exec $db "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Sequence``) VALUES ('PointTaskkillPath', $($remove - 2))" | Out-Null
Exec $db "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Sequence``) VALUES ('PointClose', $($remove - 1))" | Out-Null

Invoke $db 'Commit' $null | Out-Null
[System.Runtime.InteropServices.Marshal]::ReleaseComObject($db) | Out-Null
Write-Output "Установщик закрывает Point перед заменой файлов: $path"
