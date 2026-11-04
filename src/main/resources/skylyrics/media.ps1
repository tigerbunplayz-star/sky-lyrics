# Sky Lyrics media reader.
# Reads what's playing from Windows' media controls (the same info shown in the
# volume flyout) and prints one JSON line about every 400 ms. Started by the mod;
# exits on its own when Minecraft closes.
param(
    [string]$Filter = 'spotify',
    [int]$ParentPid = 0
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

$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
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
            $line = ($o | ConvertTo-Json -Compress)
        }
    } catch {
        $msg = ($_.Exception.Message -replace '[\r\n"\\]', ' ')
        $line = '{"ok":false,"error":"' + $msg + '"}'
    }

    Emit $line
    Start-Sleep -Milliseconds 400
}
