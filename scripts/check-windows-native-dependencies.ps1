param(
  [Parameter(Mandatory = $true)]
  [string]$BackendPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $BackendPath)) {
  Write-Error "Backend executable does not exist: $BackendPath"
}

function Find-Dumpbin {
  $command = Get-Command dumpbin.exe -ErrorAction SilentlyContinue
  if ($command) {
    return $command.Source
  }

  $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
  if (Test-Path -LiteralPath $vswhere) {
    $installPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if ($installPath) {
      $candidate = Get-ChildItem -Path (Join-Path $installPath "VC\Tools\MSVC\*\bin\Hostx64\x64\dumpbin.exe") -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
      if ($candidate) {
        return $candidate.FullName
      }
    }
  }

  Write-Error "dumpbin.exe was not found. Install Visual Studio Build Tools with C++ tools."
}

$dumpbin = Find-Dumpbin
Write-Host "Using dumpbin: $dumpbin"

$dependencies = & $dumpbin /dependents $BackendPath 2>&1
$dependencies | Write-Host

$forbidden = @(
  "VCRUNTIME140.dll",
  "VCRUNTIME140_1.dll",
  "MSVCP140.dll"
)

foreach ($dll in $forbidden) {
  if ($dependencies -match [regex]::Escape($dll)) {
    Write-Error "Windows native backend still depends on $dll. Build must statically link the MSVC runtime."
  }
}
