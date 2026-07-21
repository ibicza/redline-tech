#requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$PlanPath,

    [ValidateRange(30, 1800)]
    [int]$StartupTimeoutSeconds = 300,

    [ValidateRange(30, 3600)]
    [int]$ReportTimeoutSeconds = 900,

    [ValidateRange(10, 300)]
    [int]$ShutdownTimeoutSeconds = 60,

    [switch]$ValidateOnly,

    [switch]$VerboseServerOutput
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$moduleRoot = Join-Path $repoRoot "redline-atlas-worldgen"
$runDirectory = Join-Path $moduleRoot "run"
$atlasRoot = Join-Path $runDirectory "config\redline-atlas-worldgen"
$reportDirectory = Join-Path $runDirectory "profile-results"
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

function Get-PropertyValue {
    param(
        [Parameter(Mandatory = $true)] [object]$InputObject,
        [Parameter(Mandatory = $true)] [string]$Name,
        [object]$DefaultValue,
        [switch]$Required
    )

    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        if ($Required) {
            throw "Missing required plan property '$Name'."
        }
        return $DefaultValue
    }
    return $property.Value
}

function Get-AtlasLayerInventory {
    param(
        [Parameter(Mandatory = $true)] [string]$Name,
        [Parameter(Mandatory = $true)] [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "Atlas layer '$Name' is missing: $Path"
    }

    [long]$bytes = 0
    [int]$files = 0
    foreach ($file in [System.IO.Directory]::EnumerateFiles(
            $Path,
            "*",
            [System.IO.SearchOption]::AllDirectories
    )) {
        $info = [System.IO.FileInfo]::new($file)
        $files++
        $bytes += $info.Length
    }
    if ($files -eq 0) {
        throw "Atlas layer '$Name' contains no files: $Path"
    }

    return [pscustomobject]@{
        Name = $Name
        Path = $Path
        Files = $files
        Bytes = $bytes
    }
}

function Add-ServerLine {
    param([string]$Stream, [string]$Line)

    if ($VerboseServerOutput -or $Line -match "Done \(|chunk_profile|Flight Recorder|JFR|recording|BUILD (SUCCESSFUL|FAILED)|\[(WARN|ERROR)\]|Atlas .* index loaded") {
        Write-Host "[$Stream] $Line"
    }
    $script:recentLines.Enqueue("[$Stream] $Line")
    while ($script:recentLines.Count -gt 200) {
        [void]$script:recentLines.Dequeue()
    }
    if ($Line -match "Done \([0-9.,]+s\).*[Ff]or help") {
        $script:serverReady = $true
    }
}

function Pump-ProcessOutput {
    while ($null -ne $script:stdoutTask -and $script:stdoutTask.IsCompleted) {
        $line = $script:stdoutTask.GetAwaiter().GetResult()
        if ($null -eq $line) {
            $script:stdoutTask = $null
            break
        }
        Add-ServerLine -Stream "OUT" -Line $line
        $script:stdoutTask = $script:process.StandardOutput.ReadLineAsync()
    }

    while ($null -ne $script:stderrTask -and $script:stderrTask.IsCompleted) {
        $line = $script:stderrTask.GetAwaiter().GetResult()
        if ($null -eq $line) {
            $script:stderrTask = $null
            break
        }
        Add-ServerLine -Stream "ERR" -Line $line
        $script:stderrTask = $script:process.StandardError.ReadLineAsync()
    }
}

function Get-RecentServerOutput {
    return [string]::Join([Environment]::NewLine, $script:recentLines.ToArray())
}

function Wait-ForServerReady {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        Pump-ProcessOutput
        if ($script:serverReady) {
            return
        }
        if ($script:process.HasExited) {
            Pump-ProcessOutput
            throw "Server exited during startup (code $($script:process.ExitCode)).`n$(Get-RecentServerOutput)"
        }
        Start-Sleep -Milliseconds 100
    }
    throw "Server startup timed out after $StartupTimeoutSeconds seconds.`n$(Get-RecentServerOutput)"
}

function Find-ProfileReport {
    param(
        [Parameter(Mandatory = $true)] [string]$Label,
        [Parameter(Mandatory = $true)] [DateTime]$NotBeforeUtc
    )

    if (-not (Test-Path -LiteralPath $reportDirectory -PathType Container)) {
        return $null
    }
    $threshold = $NotBeforeUtc.AddSeconds(-2)
    $files = Get-ChildItem -LiteralPath $reportDirectory -Filter "*.json" -File |
            Where-Object { $_.LastWriteTimeUtc -ge $threshold } |
            Sort-Object LastWriteTimeUtc -Descending
    foreach ($file in $files) {
        try {
            $report = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
            if ([string]$report.label -eq $Label) {
                return [pscustomobject]@{ File = $file; Report = $report }
            }
        } catch {
            # The writer may still be replacing the file; retry on the next poll.
        }
    }
    return $null
}

function Wait-ForProfileReport {
    param(
        [Parameter(Mandatory = $true)] [string]$Label,
        [Parameter(Mandatory = $true)] [DateTime]$NotBeforeUtc
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($ReportTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        Pump-ProcessOutput
        $result = Find-ProfileReport -Label $Label -NotBeforeUtc $NotBeforeUtc
        if ($null -ne $result) {
            return $result
        }
        if ($script:process.HasExited) {
            Pump-ProcessOutput
            throw "Server exited while profiling '$Label' (code $($script:process.ExitCode)).`n$(Get-RecentServerOutput)"
        }
        Start-Sleep -Milliseconds 250
    }

    throw "Profile '$Label' did not produce a report within $ReportTimeoutSeconds seconds."
}

function Wait-ForRunSummary {
    $deadline = [DateTime]::UtcNow.AddSeconds($ReportTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        Pump-ProcessOutput
        if (Test-Path -LiteralPath $runSummaryPath -PathType Leaf) {
            try {
                $summary = Get-Content -LiteralPath $runSummaryPath -Raw | ConvertFrom-Json
                if ([string]$summary.runId -eq $runId) {
                    return $summary
                }
            } catch {
                # The runner uses an atomic move, but tolerate delayed filesystem visibility.
            }
        }
        if ($script:process.HasExited) {
            Pump-ProcessOutput
            throw "Server exited without run summary '$runSummaryPath'.`n$(Get-RecentServerOutput)"
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Automated run did not produce '$runSummaryPath' within $ReportTimeoutSeconds seconds."
}

function Write-ProfileSummary {
    param([Parameter(Mandatory = $true)] [object]$Result)

    $report = $Result.Report
    $peakDeltaMiB = [double]$report.heap.peakDeltaBytes / 1MB
    Write-Host ("PROFILE {0}: reason={1}, chunks={2}/{3}, duration={4:N1} ms, peakHeapDelta={5:N1} MiB" -f
            $report.label,
            $report.stopReason,
            $report.completedChunks,
            $report.targetChunks,
            [double]$report.durationMillis,
            $peakDeltaMiB)
    foreach ($stage in @($report.chunkStages) |
            Sort-Object { [double]$_.totalMillis } -Descending |
            Select-Object -First 6) {
        Write-Host ("  {0}: count={1}, total={2:N1} ms, avg={3:N1} ms, max={4:N1} ms" -f
                $stage.name,
                $stage.count,
                [double]$stage.totalMillis,
                [double]$stage.averageMillis,
                [double]$stage.maxMillis)
    }
    $metricsProperty = $report.PSObject.Properties["metrics"]
    if ($null -ne $metricsProperty) {
        foreach ($metric in @($metricsProperty.Value) |
                Sort-Object { [long]$_.total } -Descending |
                Select-Object -First 8) {
            Write-Host ("  metric {0}: count={1}, total={2}, avg={3}, max={4}" -f
                    $metric.name,
                    $metric.count,
                    $metric.total,
                    $metric.average,
                    $metric.max)
        }
    }
    Write-Host "  report=$($Result.File.FullName)"
}

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle Wrapper is missing: $gradleWrapper"
}
$resolvedPlanPath = (Resolve-Path -LiteralPath $PlanPath).Path
$plan = Get-Content -LiteralPath $resolvedPlanPath -Raw | ConvertFrom-Json
$pointsProperty = $plan.PSObject.Properties["points"]
if ($null -eq $pointsProperty) {
    throw "Profile plan must contain a 'points' array."
}
$rawPoints = @($pointsProperty.Value)
if ($rawPoints.Count -eq 0 -or $rawPoints.Count -gt 64) {
    throw "Profile plan must contain between 1 and 64 points."
}

$defaultRadius = [int](Get-PropertyValue -InputObject $plan -Name "radiusChunks" -DefaultValue 1)
$defaultTimeout = [int](Get-PropertyValue -InputObject $plan -Name "timeoutTicks" -DefaultValue 12000)
$defaultSettle = [int](Get-PropertyValue -InputObject $plan -Name "settleTicks" -DefaultValue 100)
$labels = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$points = [System.Collections.Generic.List[object]]::new()

foreach ($point in $rawPoints) {
    $label = [string](Get-PropertyValue -InputObject $point -Name "label" -Required)
    if ($label -notmatch "^[A-Za-z0-9._-]{1,48}$") {
        throw "Profile label '$label' must contain 1-48 ASCII letters, digits, '.', '_' or '-'."
    }
    if (-not $labels.Add($label)) {
        throw "Duplicate profile label '$label'."
    }

    [long]$blockX = Get-PropertyValue -InputObject $point -Name "blockX" -Required
    [long]$blockZ = Get-PropertyValue -InputObject $point -Name "blockZ" -Required
    [int]$radius = Get-PropertyValue -InputObject $point -Name "radiusChunks" -DefaultValue $defaultRadius
    [int]$timeout = Get-PropertyValue -InputObject $point -Name "timeoutTicks" -DefaultValue $defaultTimeout
    [int]$settle = Get-PropertyValue -InputObject $point -Name "settleTicks" -DefaultValue $defaultSettle
    $terrainClass = [string](Get-PropertyValue -InputObject $point -Name "terrainClass" -DefaultValue "")
    $terrainClass = $terrainClass.ToLowerInvariant()
    [int]$nearestRiverRadius = Get-PropertyValue -InputObject $point -Name "nearestRiverRadiusBlocks" -DefaultValue 0

    if ($blockX -lt -30000000 -or $blockX -gt 29999999 -or
            $blockZ -lt -30000000 -or $blockZ -gt 29999999) {
        throw "Profile '$label' coordinates are outside Minecraft bounds."
    }
    if ($radius -lt 0 -or $radius -gt 7) {
        throw "Profile '$label' radiusChunks must be between 0 and 7."
    }
    if ($timeout -lt 20 -or $timeout -gt 72000) {
        throw "Profile '$label' timeoutTicks must be between 20 and 72000."
    }
    if ($settle -lt 0 -or $settle -gt 1200 -or $settle -gt $timeout) {
        throw "Profile '$label' settleTicks must be between 0 and min(1200, timeoutTicks)."
    }
    if ($terrainClass -and $terrainClass -notin @("ordinary", "river", "lake", "ocean")) {
        throw "Profile '$label' terrainClass must be ordinary, river, lake or ocean."
    }
    if ($nearestRiverRadius -lt 0 -or $nearestRiverRadius -gt 32768) {
        throw "Profile '$label' nearestRiverRadiusBlocks must be between 0 and 32768."
    }
    if ($nearestRiverRadius -gt 0 -and $terrainClass -ne "river") {
        throw "Profile '$label' nearestRiverRadiusBlocks requires terrainClass=river."
    }

    $points.Add([pscustomobject]@{
            Label = $label
            BlockX = [int]$blockX
            BlockZ = [int]$blockZ
            Radius = $radius
            Timeout = $timeout
            Settle = $settle
            TerrainClass = $terrainClass
            NearestRiverRadius = $nearestRiverRadius
        })
}

$inventories = @(
    Get-AtlasLayerInventory -Name "heightmaps" -Path (Join-Path $atlasRoot "heightmaps")
    Get-AtlasLayerInventory -Name "landcover" -Path (Join-Path $atlasRoot "landcover")
    Get-AtlasLayerInventory -Name "ocean_bathymetry" -Path (Join-Path $atlasRoot "ocean_bathymetry")
    Get-AtlasLayerInventory -Name "rivers" -Path (Join-Path $atlasRoot "rivers")
)
Write-Host "Atlas inventory:"
foreach ($inventory in $inventories) {
    Write-Host ("  {0}: {1} files, {2:N1} MiB, {3}" -f
            $inventory.Name, $inventory.Files, ($inventory.Bytes / 1MB), $inventory.Path)
}

if ($ValidateOnly) {
    Write-Host "Profile plan is valid: $($points.Count) point(s)."
    return
}

[void][System.IO.Directory]::CreateDirectory($reportDirectory)
$runStartedUtc = [DateTime]::UtcNow
$runId = "rla-$($runStartedUtc.ToString('yyyyMMdd-HHmmss-fff'))-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$runSummaryPath = Join-Path $reportDirectory "$runId.run.json"
$cancelPath = Join-Path $reportDirectory "$runId.cancel"
$gradleArguments = [System.Collections.Generic.List[string]]::new()
$gradleArguments.Add(":redline-atlas-worldgen:runServer")
$gradleArguments.Add("--console=plain")
$gradleArguments.Add("--no-daemon")

$manifestCandidates = @(
    (Join-Path (Split-Path -Parent $repoRoot) ".gradle\caches\neoformruntime\artifacts\minecraft_launcher_manifest.json"),
    (Join-Path $env:USERPROFILE ".gradle\caches\neoformruntime\artifacts\minecraft_launcher_manifest.json")
)
$manifest = $manifestCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ($null -ne $manifest) {
    $manifestUri = [Uri]::new((Resolve-Path -LiteralPath $manifest).Path).AbsoluteUri
    $gradleArguments.Add("-PneoForge.neoFormRuntime.launcherManifestUrl=$manifestUri")
    $gradleArguments.Add("--offline")
    Write-Host "Using cached launcher manifest: $manifest"
}

$processInfo = [System.Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = $env:ComSpec
$processInfo.Arguments = '/d /s /c ""' + $gradleWrapper + '" ' + [string]::Join(' ', $gradleArguments) + '"'
$processInfo.WorkingDirectory = $repoRoot
$processInfo.UseShellExecute = $false
$processInfo.CreateNoWindow = $true
$processInfo.RedirectStandardInput = $false
$processInfo.RedirectStandardOutput = $true
$processInfo.RedirectStandardError = $true
$processInfo.EnvironmentVariables["RLA_CHUNK_PROFILE_PLAN"] = $resolvedPlanPath
$processInfo.EnvironmentVariables["RLA_CHUNK_PROFILE_RUN_ID"] = $runId
$processInfo.EnvironmentVariables["RLA_CHUNK_PROFILE_AUTO_STOP"] = "true"

$script:process = [System.Diagnostics.Process]::new()
$script:process.StartInfo = $processInfo
$script:recentLines = [System.Collections.Generic.Queue[string]]::new()
$script:serverReady = $false
$script:stdoutTask = $null
$script:stderrTask = $null
$completedNormally = $false
$processStarted = $false
$processExitCode = $null
$results = [System.Collections.Generic.List[object]]::new()
$runSummary = $null

try {
    if (-not $script:process.Start()) {
        throw "Failed to start Gradle server process."
    }
    $processStarted = $true
    $script:stdoutTask = $script:process.StandardOutput.ReadLineAsync()
    $script:stderrTask = $script:process.StandardError.ReadLineAsync()

    Wait-ForServerReady

    foreach ($point in $points) {
        $result = Wait-ForProfileReport -Label $point.Label -NotBeforeUtc $runStartedUtc
        $results.Add($result)
        Write-ProfileSummary -Result $result
    }

    $runSummary = Wait-ForRunSummary
    $classificationsProperty = $runSummary.PSObject.Properties["classifications"]
    if ($null -ne $classificationsProperty) {
        foreach ($classification in @($classificationsProperty.Value)) {
            $expectedProperty = $classification.PSObject.Properties["expectedTerrainClass"]
            $expected = if ($null -eq $expectedProperty -or $null -eq $expectedProperty.Value) {
                "unspecified"
            } else {
                [string]$expectedProperty.Value
            }
            Write-Host ("CLASSIFY {0}: expected={1}, actual={2}, block={3},{4}" -f
                    $classification.label,
                    $expected,
                    $classification.terrainClass,
                    $classification.blockX,
                    $classification.blockZ)
        }
    }
    if (-not [bool]$runSummary.success) {
        throw "Automated profile run failed: $($runSummary.error)"
    }
    $completedNormally = $true
} finally {
    if ($processStarted -and -not $script:process.HasExited) {
        if (-not $completedNormally) {
            [System.IO.File]::WriteAllText($cancelPath, "cancelled by profile-atlas-chunks.ps1")
            Write-Warning "Requested cancellation through $cancelPath"
        }

        $shutdownDeadline = [DateTime]::UtcNow.AddSeconds($ShutdownTimeoutSeconds)
        while (-not $script:process.HasExited -and [DateTime]::UtcNow -lt $shutdownDeadline) {
            Pump-ProcessOutput
            Start-Sleep -Milliseconds 100
        }
        if (-not $script:process.HasExited) {
            Write-Warning "Server did not stop gracefully; terminating spawned process tree."
            & taskkill.exe /PID $script:process.Id /T /F | Out-Host
            [void]$script:process.WaitForExit(5000)
        }
    }

    if ($processStarted) {
        Pump-ProcessOutput
        if ($script:process.HasExited) {
            $processExitCode = $script:process.ExitCode
        }
    }
    Remove-Item -LiteralPath $cancelPath -Force -ErrorAction SilentlyContinue
    $script:process.Dispose()
}

if ($completedNormally) {
    if ($null -eq $processExitCode -or $processExitCode -ne 0) {
        throw "Gradle server process exited with code $processExitCode."
    }
    Write-Host "Completed $($results.Count) chunk profile(s). JSON/CSV reports: $reportDirectory"
    Write-Host "Minecraft JFR: $($runSummary.jfrPath)"
    Write-Host "Run summary: $runSummaryPath"
}
