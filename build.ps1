#requires -Version 7.4
[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Reconfigure,
    [switch]$StopDaemons,
    [switch]$Clean,
    [switch]$Offline,
    [switch]$Info,
    [string]$Task = 'build'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$PipelineTimer = [System.Diagnostics.Stopwatch]::StartNew()

# ---------------------------------------------------------------------------
# Design- & Farb-Engine (PowerShell 7+ / TrueColor ANSI)
# ---------------------------------------------------------------------------
$C = [PSCustomObject]@{
    Reset    = $PSStyle.Reset
    Bold     = $PSStyle.Bold
    Dim      = $PSStyle.Dim
    Italic   = $PSStyle.Italic
    Primary  = $PSStyle.Foreground.FromRgb(99, 102, 241)   # Indigo 500
    Cyan     = $PSStyle.Foreground.FromRgb(14, 165, 233)   # Sky 500
    Success  = $PSStyle.Foreground.FromRgb(34, 197, 94)    # Emerald 500
    Warning  = $PSStyle.Foreground.FromRgb(245, 158, 11)   # Bernstein 500
    Error    = $PSStyle.Foreground.FromRgb(239, 68, 68)    # Karminrot 500
    Muted    = $PSStyle.Foreground.FromRgb(148, 163, 184)  # Schiefergrau 400
    Surface  = $PSStyle.Foreground.FromRgb(241, 245, 249)  # Helles Schieferweiß
    Accent   = $PSStyle.Foreground.FromRgb(168, 85, 247)   # Violett 500
}

$BoxWidth = 80
$SpinnerChars = @('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')

function Write-Badge {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$FgColor,
        [Parameter(Mandatory)][string]$Message,
        [switch]$NoNewLine
    )
    $badge = "$($C.Bold)$FgColor[$Text]$($C.Reset)"
    Write-Host "$badge $Message" -NoNewline:$NoNewLine
}

function Get-VisibleWidth {
    param([string]$text)
    $stripped = $text -replace "`e\[[0-9;?]*[a-zA-Z]", ''
    $extra = 0
    foreach ($ch in $stripped.ToCharArray()) {
        # ⚡ (U+26A1) belegt im Terminal-Font 2 Spalten (Monospace East Asian / Emoji Width)
        if ([int]$ch -eq 0x26A1) { $extra += 1 }
    }
    return ($stripped.Length + $extra)
}

function Write-BoxLine {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$BorderColor,
        [int]$Width = $BoxWidth
    )
    $vWidth = Get-VisibleWidth $Content
    $pad = $Width - 2 - $vWidth
    if ($pad -lt 0) { $pad = 0 }
    Write-Host "$BorderColor│$($C.Reset)$Content$(' ' * $pad)$BorderColor│$($C.Reset)"
}

function Show-Banner {
    param(
        [string]$ModId,
        [string]$Version,
        [string]$McVersion,
        [string]$LoaderVersion
    )
    $hLine = '─' * ($BoxWidth - 2)
    Write-Host ""
    Write-Host "$($C.Primary)╭$hLine╮$($C.Reset)"
    Write-BoxLine -Content "  $($C.Bold)$($C.Accent)⚡ ONESHOTONEKILL$($C.Reset)  $($C.Muted)•$($C.Reset)  $($C.Surface)Fabric Loom Build- & Deployment-Pipeline$($C.Reset)" -BorderColor $C.Primary
    Write-BoxLine -Content "  $($C.Muted)Minecraft $McVersion (Fabric $LoaderVersion) • Mod '$ModId' v$Version$($C.Reset)" -BorderColor $C.Primary
    Write-Host "$($C.Primary)╰$hLine╯$($C.Reset)"
    Write-Host ""
}

function Get-LockingProcessHint {
    param([string]$TargetPath)
    if ($TargetPath -match 'SERVER') {
        $serverProc = Get-Process -Name java -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $serverProc) {
            return "Server aktiv (java.exe • PID $($serverProc.Id))"
        }
    } else {
        $clientProc = Get-Process -Name javaw -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $clientProc) {
            return "Minecraft Client aktiv (javaw.exe • PID $($clientProc.Id))"
        }
    }
    return "Laufendes Spiel oder Server aktiv"
}

# ---------------------------------------------------------------------------
# Pfad- und Eigenschaftsauflösung
# ---------------------------------------------------------------------------
$ModDir = $PSScriptRoot ?? (Get-Location).Path
Set-Location -LiteralPath $ModDir
$DeployConfigFile = Join-Path $ModDir 'deploy.properties'
$GradlePropertiesFile = Join-Path $ModDir 'gradle.properties'

function Get-PropertiesFile {
    [CmdletBinding()]
    [OutputType([hashtable])]
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return @{}
    }

    $properties = @{}
    Get-Content -LiteralPath $Path -Encoding utf8 | ForEach-Object {
        $trimmed = $_.Trim()
        if ($trimmed -and -not $trimmed.StartsWith('#') -and $trimmed.Contains('=')) {
            $key, $value = $trimmed.Split('=', 2)
            $properties[$key.Trim()] = $value.Trim()
        }
    }
    return $properties
}

$DeployProperties = Get-PropertiesFile -Path $DeployConfigFile
$GradleProperties = Get-PropertiesFile -Path $GradlePropertiesFile

$ServerModsDir = $DeployProperties['server_mods_dir']
$ClientModsDir = $DeployProperties['client_mods_dir']
$ModId         = $GradleProperties['mod_id'] ?? 'oneshotonekill'
$ModVersion    = $GradleProperties['version'] ?? '1.0.0'
$McVersion     = $GradleProperties['minecraft_version'] ?? '26.2'
$LoaderVersion = $GradleProperties['loader_version'] ?? '0.19.5'

Show-Banner -ModId $ModId -Version $ModVersion -McVersion $McVersion -LoaderVersion $LoaderVersion

# ---------------------------------------------------------------------------
# Gradle Daemon-Beendigung
# ---------------------------------------------------------------------------
if ($StopDaemons) {
    Write-Badge -Text ' ⏳ DAEMONS ' -FgColor $C.Warning -Message 'Beende laufende Hintergrund-Gradle-Daemons...'
    .\gradlew.bat --stop
    Write-Badge -Text '  ✔ OK  ' -FgColor $C.Success -Message 'Gradle-Daemons sauber beendet.'
    exit $LASTEXITCODE
}

if ([string]::IsNullOrWhiteSpace($ModId)) {
    Write-Badge -Text ' ✖ FEHLER ' -FgColor $C.Error -Message "Eigenschaft 'mod_id' fehlt in der Datei gradle.properties."
    throw "'mod_id' fehlt in gradle.properties."
}

# ---------------------------------------------------------------------------
# Interaktive Bereitstellungskonfiguration
# ---------------------------------------------------------------------------
if ($Reconfigure -or [string]::IsNullOrWhiteSpace($ServerModsDir) -or [string]::IsNullOrWhiteSpace($ClientModsDir)) {
    Write-Badge -Text ' ⚙ KONFIG ' -FgColor $C.Cyan -Message 'Fabric-Mod-Bereitstellungsziele konfigurieren...'

    $DefaultServer = $ServerModsDir ? $ServerModsDir : (Join-Path (Get-Item (Join-Path $ModDir '..')).FullName 'SERVER\mods')
    $DefaultClient = $ClientModsDir ? $ClientModsDir : (Join-Path $env:APPDATA '.minecraft\mods')

    $InputServer = Read-Host "Pfad zum SERVER-'mods'-Ordner [$DefaultServer]"
    $ServerModsDir = [string]::IsNullOrWhiteSpace($InputServer) ? $DefaultServer : $InputServer

    $InputClient = Read-Host "Pfad zum CLIENT-'mods'-Ordner [$DefaultClient]"
    $ClientModsDir = [string]::IsNullOrWhiteSpace($InputClient) ? $DefaultClient : $InputClient

    New-Item -ItemType Directory -Path $ServerModsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $ClientModsDir -Force | Out-Null

    @"
# Deploy Configuration
server_mods_dir=$ServerModsDir
client_mods_dir=$ClientModsDir
"@ | Set-Content -LiteralPath $DeployConfigFile -Encoding utf8

    Write-Badge -Text '  ✔ OK  ' -FgColor $C.Success -Message 'Konfiguration erfolgreich in deploy.properties gespeichert.'
}

# ---------------------------------------------------------------------------
# Bereinigungsphase (Clean)
# ---------------------------------------------------------------------------
if ($Clean) {
    Write-Badge -Text ' 🧹 CLEAN ' -FgColor $C.Warning -Message 'Führe Gradle-Bereinigung (clean) durch...'
    .\gradlew.bat clean || {
        Write-Badge -Text ' ✖ FEHLER ' -FgColor $C.Error -Message "Gradle clean mit Exit-Code $LASTEXITCODE abgebrochen."
        exit $LASTEXITCODE
    }
    Write-Badge -Text '  ✔ OK  ' -FgColor $C.Success -Message 'Build-Cache erfolgreich bereinigt.'
}

# ---------------------------------------------------------------------------
# Gradle-Ausführungsphase
# ---------------------------------------------------------------------------
$gradleArgs = @($Task)
if ($Offline) { $gradleArgs += '--offline' }
if ($Info)    { $gradleArgs += '--info' }

$displayTask = "$Task" + ($Offline ? ' [offline]' : '') + ($Info ? ' [info]' : '')
Write-Badge -Text ' ⚡ BUILD ' -FgColor $C.Cyan -Message "Führe Gradle-Task '$($C.Bold)$displayTask$($C.Reset)$($C.Cyan)' aus..."
$gradleStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

.\gradlew.bat @gradleArgs || {
    Write-Badge -Text ' ✖ FEHLER ' -FgColor $C.Error -Message "Gradle-Build mit Fehlercode $LASTEXITCODE abgebrochen."
    exit $LASTEXITCODE
}
$gradleStopwatch.Stop()
$gradleSec = [math]::Round($gradleStopwatch.Elapsed.TotalSeconds, 2)
Write-Badge -Text '  ✔ OK  ' -FgColor $C.Success -Message "Gradle erfolgreich abgeschlossen in $($C.Bold)${gradleSec}s$($C.Reset)."

if ($Task -ne 'build') {
    Write-Badge -Text '  ℹ INFO ' -FgColor $C.Cyan -Message "Artefakt-Bereitstellung für Nicht-Build-Task '$Task' übersprungen."
    exit 0
}

# ---------------------------------------------------------------------------
# Artefakt-Erkennung & Checksummen-Verifikation
# ---------------------------------------------------------------------------
$JarDirectory = Join-Path $ModDir 'build\libs'
$ModJar = Get-ChildItem -LiteralPath $JarDirectory -Filter "$ModId-*.jar" -File |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1

if ($null -eq $ModJar) {
    Write-Badge -Text ' ✖ FEHLER ' -FgColor $C.Error -Message "Kein Mod-JAR passend zu '$ModId-*.jar' in '$JarDirectory' gefunden."
    throw "Kein Mod-Jar in '$JarDirectory' gefunden."
}

$JarSizeFormatted = $ModJar.Length -gt 1MB ? "$([math]::Round($ModJar.Length / 1MB, 2)) MB" : "$([math]::Round($ModJar.Length / 1KB, 1)) KB"
$SourceHash = (Get-FileHash -LiteralPath $ModJar.FullName -Algorithm SHA256).Hash
$ShortHash = $SourceHash.Substring(0, 12)

Write-Badge -Text ' 📦 ARTEFAKT ' -FgColor $C.Primary -Message "Gefunden: $($C.Bold)$($ModJar.Name)$($C.Reset) ($JarSizeFormatted • SHA-256: $ShortHash...)"

# ---------------------------------------------------------------------------
# Atomare Bereitstellungs-Pipeline mit PowerShell 7 Lock-Handling
# ---------------------------------------------------------------------------
$TargetDirectories = @($ServerModsDir, $ClientModsDir) | Select-Object -Unique
$DeployedCount = 0

foreach ($TargetDirectory in $TargetDirectories) {
    if (-not (Test-Path -LiteralPath $TargetDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null
    }

    $ResolvedTargetDirectory = (Resolve-Path -LiteralPath $TargetDirectory).Path
    $TargetJar  = Join-Path $ResolvedTargetDirectory $ModJar.Name
    $StagedJar  = Join-Path $ResolvedTargetDirectory (".$($ModJar.Name).deploying")
    $BackupJar  = Join-Path $ResolvedTargetDirectory (".$($ModJar.Name).previous")
    $PendingJar = Join-Path $ResolvedTargetDirectory (".$($ModJar.Name).pending")
    $Copied     = $false
    $Attempt    = 0
    $MaxAttempts = 60

    if (Test-Path -LiteralPath $PendingJar -PathType Leaf) {
        Remove-Item -LiteralPath $PendingJar -Force -ErrorAction SilentlyContinue
    }

    while (-not $Copied) {
        $Attempt++
        try {
            Copy-Item -LiteralPath $ModJar.FullName -Destination $StagedJar -Force -ErrorAction Stop
            $StagedHash = (Get-FileHash -LiteralPath $StagedJar -Algorithm SHA256).Hash
            if ($SourceHash -ne $StagedHash) {
                throw 'Die vorbereitete JAR-Datei stimmt nicht mit dem Build-Artefakt überein.'
            }

            # Atomarer Austausch über .NET API
            if (Test-Path -LiteralPath $TargetJar -PathType Leaf) {
                if (Test-Path -LiteralPath $BackupJar -PathType Leaf) {
                    Remove-Item -LiteralPath $BackupJar -Force -ErrorAction Stop
                }
                [System.IO.File]::Replace($StagedJar, $TargetJar, $BackupJar, $true)
            } else {
                [System.IO.File]::Move($StagedJar, $TargetJar, $true)
            }

            Get-ChildItem -LiteralPath $TargetDirectory -Filter "$ModId-*.jar" -File |
                Where-Object { $_.FullName -ne $TargetJar } |
                Remove-Item -Force -ErrorAction Stop

            if (-not (Test-Path -LiteralPath $TargetJar -PathType Leaf)) {
                throw 'Die Ziel-JAR-Datei wurde nicht erstellt.'
            }

            $TargetHash = (Get-FileHash -LiteralPath $TargetJar -Algorithm SHA256).Hash
            if ($SourceHash -ne $TargetHash) {
                throw 'Die kopierte JAR-Datei stimmt nicht mit dem Build-Artefakt überein.'
            }

            if ($Attempt -gt 1) {
                Write-Host "`r$([char]0x1b)[2K" -NoNewline
            }

            $label = $ResolvedTargetDirectory -match 'SERVER' ? 'SERVER' : 'CLIENT'
            Write-Badge -Text " ✔ $label " -FgColor $C.Success -Message "Bereitgestellt nach $($C.Surface)$ResolvedTargetDirectory$($C.Reset)"
            
            if (Test-Path -LiteralPath $BackupJar -PathType Leaf) {
                Remove-Item -LiteralPath $BackupJar -Force -ErrorAction Stop
            }
            if (Test-Path -LiteralPath $PendingJar -PathType Leaf) {
                Remove-Item -LiteralPath $PendingJar -Force -ErrorAction SilentlyContinue
            }
            $Copied = $true
            $DeployedCount++
        } catch {
            $ErrorMessage = $_.Exception.Message
            $IsLocked = $ErrorMessage -match 'wird bereits von einem anderen Prozess verwendet|being used by another process|Sharing violation|Der Prozess kann nicht auf die Datei zugreifen|Replace'

            if ($IsLocked) {
                $procHint = Get-LockingProcessHint -TargetPath $ResolvedTargetDirectory
                $spinChar = $SpinnerChars[($Attempt - 1) % $SpinnerChars.Length]
                $waitMsg = "`r$($C.Bold)$($C.Warning)[ ⏳ WARTEN ]$($C.Reset) $procHint $($C.Accent)$spinChar$($C.Reset) Warte auf Freigabe... ($Attempt/${MaxAttempts}s) "
                Write-Host -NoNewline $waitMsg

                if ($Attempt -ge $MaxAttempts) {
                    Write-Host "`r$([char]0x1b)[2K" -NoNewline
                    Copy-Item -LiteralPath $ModJar.FullName -Destination $PendingJar -Force -ErrorAction SilentlyContinue
                    if (Test-Path -LiteralPath $StagedJar) {
                        Remove-Item -LiteralPath $StagedJar -Force -ErrorAction SilentlyContinue
                    }
                    Write-Badge -Text ' ⚠ TIMEOUT ' -FgColor $C.Warning -Message "Maximale Wartezeit überschritten ($MaxAttempts s). Als ausstehendes Update (pending) vorgemerkt."
                    break
                }
                Start-Sleep -Seconds 1
            } else {
                if (Test-Path -LiteralPath $StagedJar) {
                    Remove-Item -LiteralPath $StagedJar -Force -ErrorAction SilentlyContinue
                }
                Write-Host "`r$([char]0x1b)[2K" -NoNewline
                Write-Badge -Text ' ✖ FEHLER ' -FgColor $C.Error -Message "Bereitstellung nach '$ResolvedTargetDirectory' fehlgeschlagen: $ErrorMessage"
                throw "Deployment nach '$ResolvedTargetDirectory' ist fehlgeschlagen: $ErrorMessage"
            }
        }
    }
}

# ---------------------------------------------------------------------------
# Zusammenfassungskarte (Pipeline-Summary mit vollständiger Box-Umrandung)
# ---------------------------------------------------------------------------
$PipelineTimer.Stop()
$TotalSeconds = [math]::Round($PipelineTimer.Elapsed.TotalSeconds, 2)
$hLine = '─' * ($BoxWidth - 2)

Write-Host ""
Write-Host "$($C.Success)╭$hLine╮$($C.Reset)"
Write-BoxLine -Content "  $($C.Bold)$($C.Success)✔ PIPELINE ERFOLGREICH ABGESCHLOSSEN$($C.Reset)" -BorderColor $C.Success
Write-Host "$($C.Success)├$hLine┤$($C.Reset)"
Write-BoxLine -Content "  $($C.Surface)Artefakt : $($ModJar.Name) ($JarSizeFormatted)$($C.Reset)" -BorderColor $C.Success
Write-BoxLine -Content "  $($C.Surface)SHA-256  : $SourceHash$($C.Reset)" -BorderColor $C.Success
Write-BoxLine -Content "  $($C.Surface)Ziele    : $DeployedCount Zielverzeichnis(se) synchronisiert$($C.Reset)" -BorderColor $C.Success
Write-BoxLine -Content "  $($C.Surface)Dauer    : ${TotalSeconds}s (Gradle: ${gradleSec}s)$($C.Reset)" -BorderColor $C.Success
Write-Host "$($C.Success)╰$hLine╯$($C.Reset)"
Write-Host ""
