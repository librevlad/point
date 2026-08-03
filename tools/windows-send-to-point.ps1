# «Отправить в Point» в контекстном меню проводника (#252).
#
# Регистрирует пункт для ЛЮБОГО файла в ветке текущего пользователя (HKCU) — без прав
# администратора и без единого системного изменения: удаляется тем же скриптом с -Remove.
#
# Point на компьютере обычно уже открыт, поэтому пункт меню не поднимает второе окно: exe
# передаёт файл живому экземпляру по его же локальному порту, а если тот не отвечает —
# открывается сам с этим файлом.
#
#   powershell -ExecutionPolicy Bypass -File tools/windows-send-to-point.ps1 -Exe "C:\Program Files\Point\Point.exe"
#   powershell -ExecutionPolicy Bypass -File tools/windows-send-to-point.ps1 -Remove

param(
    [string]$Exe,
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$key = 'HKCU:\Software\Classes\*\shell\SendToPoint'

if ($Remove) {
    if (Test-Path $key) {
        Remove-Item $key -Recurse -Force
        Write-Output 'Пункт «Отправить в Point» убран из меню'
    } else {
        Write-Output 'Пункта в меню и не было'
    }
    return
}

if (-not $Exe) {
    # Ищем там, куда кладёт установщик MSI (:desktop packageMsi).
    $guesses = @(
        "$env:LOCALAPPDATA\Point\Point.exe",
        "$env:ProgramFiles\Point\Point.exe",
        "${env:ProgramFiles(x86)}\Point\Point.exe"
    )
    $Exe = $guesses | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $Exe -or -not (Test-Path $Exe)) {
    Write-Error @'
Не нашёл Point.exe. Укажите путь явно:
  powershell -ExecutionPolicy Bypass -File tools/windows-send-to-point.ps1 -Exe "C:\путь\Point.exe"
Собрать установщик: ./gradlew :desktop:packageMsi
'@
    exit 1
}

New-Item -Path $key -Force | Out-Null
New-ItemProperty -Path $key -Name '(default)' -Value 'Отправить в Point' -PropertyType String -Force | Out-Null
# Иконка пункта — сам Point, чтобы в длинном меню его находили глазом, а не чтением.
New-ItemProperty -Path $key -Name 'Icon' -Value "`"$Exe`",0" -PropertyType String -Force | Out-Null

$commandKey = Join-Path $key 'command'
New-Item -Path $commandKey -Force | Out-Null
New-ItemProperty -Path $commandKey -Name '(default)' -Value "`"$Exe`" `"%1`"" -PropertyType String -Force | Out-Null

Write-Output "Готово: правый клик по любому файлу → «Отправить в Point» ($Exe)"
