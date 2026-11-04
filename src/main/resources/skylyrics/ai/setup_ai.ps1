# Sky Lyrics - one-time setup of the AI lyric maker.
# Installs a private copy of Python plus Demucs (vocal separation) and faster-whisper
# (speech recognition) into the given folder, then downloads their models.
# Nothing is installed system-wide. Keep this file ASCII.
param(
    [Parameter(Mandatory = $true)][string]$Dir,
    [string]$Model = 'small'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Say([string]$line) { [Console]::Out.WriteLine($line); [Console]::Out.Flush() }
function Progress([int]$pct, [string]$text) { Say "PROGRESS $pct $text" }

# Run a program, passing each output line to $onLine. Windows PowerShell 5.1 turns a
# program's stderr into errors, so 'Stop' mode is switched off while it runs.
function RunNative([string]$exe, [string[]]$exeArgs, [scriptblock]$onLine) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $exe @exeArgs 2>&1 | ForEach-Object {
            $l = if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
            & $onLine $l
        }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
}

function ToErr([string]$l) { [Console]::Error.WriteLine($l) }

function Pip([string[]]$pipArgs) {
    $code = RunNative $python (@('-m', 'pip', 'install', '--disable-pip-version-check', '--no-warn-script-location') + $pipArgs) { param($l) ToErr $l }
    if ($code -ne 0) { throw "pip install failed: $($pipArgs -join ' ')" }
}

try {
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $ready = Join-Path $Dir 'ready.txt'
    if (Test-Path $ready) { Remove-Item $ready -Force }

    $pyDir = Join-Path $Dir 'python'
    $python = Join-Path $pyDir 'python.exe'

    if (-not (Test-Path $python)) {
        Progress 2 'downloading Python'
        $ver = '3.12.10'
        $zip = Join-Path $Dir 'python.zip'
        Invoke-WebRequest "https://www.python.org/ftp/python/$ver/python-$ver-embed-amd64.zip" -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $pyDir -Force
        Remove-Item $zip -Force

        # Let the private Python use installed packages.
        $pth = Get-ChildItem $pyDir -Filter 'python*._pth' | Select-Object -First 1
        $lines = Get-Content $pth.FullName | ForEach-Object { $_ -replace '^#\s*import site', 'import site' }
        Set-Content -Path $pth.FullName -Value $lines -Encoding ASCII
    }

    Progress 8 'installing pip'
    $getPip = Join-Path $Dir 'get-pip.py'
    Invoke-WebRequest 'https://bootstrap.pypa.io/get-pip.py' -OutFile $getPip -UseBasicParsing
    $code = RunNative $python @($getPip, '--no-warn-script-location') { param($l) ToErr $l }
    if ($code -ne 0) { throw 'installing pip failed' }
    Remove-Item $getPip -Force

    Progress 15 'installing PyTorch (the biggest download)'
    Pip @('torch==2.5.1', 'torchaudio==2.5.1', '--index-url', 'https://download.pytorch.org/whl/cpu')

    Progress 55 'installing Demucs and Whisper'
    Pip @('numpy<2', 'demucs==4.0.1', 'faster-whisper>=1.1,<2', 'soundcard>=0.4.3', 'soundfile>=0.12')

    Progress 75 'downloading the AI models'
    $script = Join-Path $Dir 'lyric_maker.py'
    $script:warmupError = $null
    $code = RunNative $python @($script, '--warmup', '--models', (Join-Path $Dir 'models'), '--model', $Model) {
        param($l)
        if ($l -match '^PROGRESS (\d+) (.*)$') { Progress (75 + [int]$Matches[1] / 4) $Matches[2] }
        elseif ($l -match '^ERROR (.*)$') { $script:warmupError = $Matches[1] }
        elseif ($l -notmatch '^DONE ') { ToErr $l }
    }
    if ($script:warmupError) { throw $script:warmupError }
    if ($code -ne 0) { throw 'downloading the models failed' }

    Set-Content -Path $ready -Value "installed $(Get-Date -Format s)" -Encoding ASCII
    Progress 100 'ready'
    Say 'DONE setup'
    exit 0
} catch {
    $msg = ($_.Exception.Message -replace '[\r\n]', ' ')
    Say "ERROR $msg"
    exit 1
}
