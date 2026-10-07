param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release"
)

$ErrorActionPreference = "Stop"

$distParent = Join-Path $PSScriptRoot "..\composeApp\build\compose\binaries\main\app"
$distRoot = Get-ChildItem -LiteralPath $distParent -Directory | Select-Object -First 1
if (-not $distRoot) {
    throw "Compose desktop app image was not found under $distParent"
}

$outDir = Join-Path $PSScriptRoot "..\dist"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = Join-Path $outDir "Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher-Windows-x64-$Configuration.exe"

$work = Join-Path $env:RUNNER_TEMP "bbs-final-remix-portable-$Configuration"
if (Test-Path $work) {
    Remove-Item -LiteralPath $work -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $work | Out-Null

$appZip = Join-Path $work "app.zip"
Compress-Archive -Path (Join-Path $distRoot.FullName "*") -DestinationPath $appZip -CompressionLevel Optimal -Force

$launcher = @'
@echo off
setlocal
set "DEST=%TEMP%\BBSFinalReMixPatcher-%RANDOM%-%RANDOM%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$dest=$env:DEST; Expand-Archive -LiteralPath '%~dp0app.zip' -DestinationPath $dest -Force; $exe=Get-ChildItem -LiteralPath $dest -Filter '*.exe' -File -Recurse | Select-Object -First 1; if(-not $exe){ exit 2 }; $p=Start-Process -FilePath $exe.FullName -WorkingDirectory $exe.DirectoryName -PassThru; $p.WaitForExit(); $code=$p.ExitCode; Remove-Item -LiteralPath $dest -Recurse -Force -ErrorAction SilentlyContinue; exit $code"
exit /b %ERRORLEVEL%
'@
$launcherPath = Join-Path $work "launch.cmd"
Set-Content -LiteralPath $launcherPath -Value $launcher -Encoding ASCII

$sedPath = Join-Path $work "portable.sed"
$sourceDir = $work.TrimEnd('\') + "\"
$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=%InstallPrompt%
DisplayLicense=%DisplayLicense%
FinishMessage=%FinishMessage%
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[Strings]
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$outFile
FriendlyName=Birth By Sleep - Final ReMix PSP ISO Patcher
AppLaunched=cmd.exe /c launch.cmd
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
FILE0="app.zip"
FILE1="launch.cmd"
[SourceFiles]
SourceFiles0=$sourceDir
[SourceFiles0]
%FILE0%=
%FILE1%=
"@
Set-Content -LiteralPath $sedPath -Value $sed -Encoding ASCII

$iexpress = Join-Path $env:WINDIR "System32\iexpress.exe"
if (-not (Test-Path $iexpress)) {
    throw "IExpress is unavailable on this Windows runner."
}

& $iexpress /N /Q /M $sedPath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $outFile)) {
    Write-Host "IExpress directive file:"
    Get-Content -LiteralPath $sedPath | Write-Host
    throw "IExpress failed to create the portable executable."
}

Write-Host "Created $outFile"
