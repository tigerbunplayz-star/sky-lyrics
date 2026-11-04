# Sky Lyrics media reader.
# Reads what's playing from Windows' media controls (the same info shown in the
# volume flyout) and prints one JSON line about every 400 ms. Started by the mod;
# exits on its own when Minecraft closes.
#
# Keep this file pure ASCII: Windows PowerShell reads BOM-less scripts in the
# system code page. Non-ASCII text (accents like o-double-acute) is written as
# \uXXXX JSON escapes so it survives any console encoding.
param(
    [string]$Filter = 'spotify',
    [int]$ParentPid = 0,
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false

function Emit([string]$line) {
    try {
        [Console]::Out.WriteLine($line)
        [Console]::Out.Flush()
    } catch {
        exit 0
    }
}

function ToJsonLine($obj) {
    $json = $obj | ConvertTo-Json -Compress
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $json.ToCharArray()) {
        $code = [int]$ch
        if ($code -lt 128) { [void]$sb.Append($ch) } else { [void]$sb.Append(('\u{0:x4}' -f $code)) }
    }
    return $sb.ToString()
}

if ($SelfTest) {
    # "Arvizturo tukorfurogep" with real Hungarian accents, plus an emoji (surrogate pair).
    $sample = -join ([char[]](0xC1, 0x72, 0x76, 0xED, 0x7A, 0x74, 0x171, 0x72, 0x151, 0x20, 0x150, 0x170, 0x20)) + [char]::ConvertFromUtf32(0x1F3B5)
    Emit (ToJsonLine ([ordered]@{ ok = $true; title = $sample; artist = 'Test'; album = ''; pos = 0; dur = 1000; status = 'Playing'; updated = 0; now = 0 }))
    exit 0
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

function Await($op, [Type]$type) {
    $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
    if (-not $task.Wait(3000)) { throw 'WinRT call timed out' }
    return $task.Result
}

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
$mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$mgr = Await ($mgrType::RequestAsync()) $mgrType

$tick = 0
while ($true) {
    $tick++
    if ($ParentPid -gt 0 -and ($tick % 5) -eq 0) {
        if ($null -eq (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit 0 }
    }

    $line = $null
    try {
        $session = $null
        if ($Filter -eq '*') {
            $session = $mgr.GetCurrentSession()
        } else {
            foreach ($s in $mgr.GetSessions()) {
                if ($s.SourceAppUserModelId -like "*$Filter*") { $session = $s; break }
            }
        }

        if ($null -eq $session) {
            $line = '{"ok":false}'
        } else {
            $props = Await ($session.TryGetMediaPropertiesAsync()) $propsType
            $tl = $session.GetTimelineProperties()
            $pb = $session.GetPlaybackInfo()
            $o = [ordered]@{
                ok      = $true
                app     = [string]$session.SourceAppUserModelId
                title   = [string]$props.Title
                artist  = [string]$props.Artist
                album   = [string]$props.AlbumTitle
                pos     = [long]$tl.Position.TotalMilliseconds
                dur     = [long]($tl.EndTime - $tl.StartTime).TotalMilliseconds
                status  = [string]$pb.PlaybackStatus
                updated = [long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()
                now     = [long][DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            }
            $line = ToJsonLine $o
        }
    } catch {
        $msg = ($_.Exception.Message -replace '[\r\n"\\]', ' ')
        $line = '{"ok":false,"error":"' + $msg + '"}'
    }

    Emit $line
    Start-Sleep -Milliseconds 400
}
