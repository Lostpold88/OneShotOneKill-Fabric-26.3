[CmdletBinding()]
param(
    [switch]$Reconfigure,
    [switch]$StopDaemons,
    [switch]$Clean,
    [string]$Task = "build"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ModDir = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
Set-Location $ModDir
$DeployConfigFile = Join-Path $ModDir "deploy.properties"
$GradlePropertiesFile = Join-Path $ModDir "gradle.properties"

if ($StopDaemons) {
    Write-Host "[WAIT] Beende Gradle-Daemons..." -ForegroundColor Yellow
    .\gradlew.bat --stop
    exit $LASTEXITCODE
}

function Read-PropertiesFile([string]$FilePath) {
    $Properties = @{}

    if (Test-Path -LiteralPath $FilePath) {
        Get-Content -LiteralPath $FilePath -Encoding UTF8 | ForEach-Object {
            $Line = $_.Trim()
            if ($Line -and -not $Line.StartsWith("#") -and $Line.Contains("=")) {
                $Parts = $Line.Split("=", 2)
                $Properties[$Parts[0].Trim()] = $Parts[1].Trim()
            }
        }
    }

    return $Properties
}

$DeployProperties = Read-PropertiesFile $DeployConfigFile
$GradleProperties = Read-PropertiesFile $GradlePropertiesFile
$ServerModsDir = $DeployProperties["server_mods_dir"]
$ClientModsDir = $DeployProperties["client_mods_dir"]
$ModId = $GradleProperties["mod_id"]

if ([string]::IsNullOrWhiteSpace($ModId)) {
    throw "'mod_id' fehlt in gradle.properties."
}

if ($Reconfigure -or [string]::IsNullOrWhiteSpace($ServerModsDir) -or [string]::IsNullOrWhiteSpace($ClientModsDir)) {
    Write-Host "=== Fabric-Mod-Deployment konfigurieren ===" -ForegroundColor Cyan

    $DefaultServer = if ($ServerModsDir) {
        $ServerModsDir
    } else {
        Join-Path (Get-Item (Join-Path $ModDir "..")).FullName "Server\mods"
    }
    $DefaultClient = if ($ClientModsDir) { $ClientModsDir } else { Join-Path $env:APPDATA ".minecraft\mods" }

    $InputServer = Read-Host "Pfad zum SERVER-'mods'-Ordner [$DefaultServer]"
    $ServerModsDir = if ([string]::IsNullOrWhiteSpace($InputServer)) { $DefaultServer } else { $InputServer }

    $InputClient = Read-Host "Pfad zum CLIENT-'mods'-Ordner [$DefaultClient]"
    $ClientModsDir = if ([string]::IsNullOrWhiteSpace($InputClient)) { $DefaultClient } else { $InputClient }

    New-Item -ItemType Directory -Path $ServerModsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $ClientModsDir -Force | Out-Null

    @"
# Deploy Configuration
server_mods_dir=$ServerModsDir
client_mods_dir=$ClientModsDir
"@ | Set-Content -LiteralPath $DeployConfigFile -Encoding UTF8

    Write-Host "[OK] Konfiguration in deploy.properties gespeichert." -ForegroundColor Green
}

if ($Clean) {
    Write-Host "[WAIT] Fuehre Gradle Clean aus..." -ForegroundColor Yellow
    .\gradlew.bat clean
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "[BUILD] Fuehre Gradle-Task '$Task' aus..." -ForegroundColor Cyan
.\gradlew.bat $Task
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Build ist mit Fehler $LASTEXITCODE abgebrochen." -ForegroundColor Red
    exit $LASTEXITCODE
}

if ($Task -ne "build") {
    Write-Host "[INFO] Hinweis: Deployment erfolgt nur beim Task 'build'." -ForegroundColor Yellow
    exit 0
}

$JarDirectory = Join-Path $ModDir "build\libs"
$ModJar = Get-ChildItem -LiteralPath $JarDirectory -Filter "$ModId-*.jar" -File |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1

if ($null -eq $ModJar) {
    throw "Kein Mod-Jar in '$JarDirectory' gefunden."
}

foreach ($TargetDirectory in @($ServerModsDir, $ClientModsDir) | Select-Object -Unique) {
    if (-not (Test-Path -LiteralPath $TargetDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null
    }

    $ResolvedTargetDirectory = (Resolve-Path -LiteralPath $TargetDirectory).Path
    $TargetJar = Join-Path $ResolvedTargetDirectory $ModJar.Name
    $StagedJar = Join-Path $ResolvedTargetDirectory (".$($ModJar.Name).deploying")
    $BackupJar = Join-Path $ResolvedTargetDirectory (".$($ModJar.Name).previous")
    $PendingJar = Join-Path $ResolvedTargetDirectory (".$($ModJar.Name).pending")
    $Copied = $false
    $Attempt = 0
    $MaxAttempts = 60 # Wartet bis zu 60 Sekunden darauf, dass Minecraft/Server geschlossen wird

    # Vorab: Falls eine alte .pending existiert und die Ziel-Datei frei ist, bereinigen
    if (Test-Path -LiteralPath $PendingJar -PathType Leaf) {
        Remove-Item -LiteralPath $PendingJar -Force -ErrorAction SilentlyContinue
    }

    while (-not $Copied) {
        $Attempt++
        try {
            # Zuerst in separate Datei schreiben und SHA-256 verifizieren
            Copy-Item -LiteralPath $ModJar.FullName -Destination $StagedJar -Force -ErrorAction Stop
            $SourceHash = (Get-FileHash -LiteralPath $ModJar.FullName -Algorithm SHA256).Hash
            $StagedHash = (Get-FileHash -LiteralPath $StagedJar -Algorithm SHA256).Hash
            if ($SourceHash -ne $StagedHash) { throw "Die vorbereitete JAR stimmt nicht mit dem Build-Artefakt ueberein." }

            # Atomarer Austausch
            if (Test-Path -LiteralPath $TargetJar -PathType Leaf) {
                if (Test-Path -LiteralPath $BackupJar -PathType Leaf) {
                    Remove-Item -LiteralPath $BackupJar -Force -ErrorAction Stop
                }
                [System.IO.File]::Replace($StagedJar, $TargetJar, $BackupJar)
            } else {
                [System.IO.File]::Move($StagedJar, $TargetJar)
            }

            Get-ChildItem -LiteralPath $TargetDirectory -Filter "$ModId-*.jar" -File |
                Where-Object { $_.FullName -ne $TargetJar } |
                Remove-Item -Force -ErrorAction Stop

            if (-not (Test-Path -LiteralPath $TargetJar -PathType Leaf)) {
                throw "Die Ziel-JAR wurde nicht erstellt."
            }

            $TargetHash = (Get-FileHash -LiteralPath $TargetJar -Algorithm SHA256).Hash
            if ($SourceHash -ne $TargetHash) {
                throw "Die kopierte JAR stimmt nicht mit dem Build-Artefakt ueberein."
            }

            Write-Host "[OK] Mod-Jar wurde zwingend nach '$ResolvedTargetDirectory' ueberschrieben." -ForegroundColor Green
            if (Test-Path -LiteralPath $BackupJar -PathType Leaf) {
                Remove-Item -LiteralPath $BackupJar -Force -ErrorAction Stop
            }
            if (Test-Path -LiteralPath $PendingJar -PathType Leaf) {
                Remove-Item -LiteralPath $PendingJar -Force -ErrorAction SilentlyContinue
            }
            $Copied = $true
        } catch {
            $ErrorMessage = $_.Exception.Message
            $IsLocked = $ErrorMessage -match "wird bereits von einem anderen Prozess verwendet|being used by another process|Sharing violation|Der Prozess kann nicht auf die Datei zugreifen|Replace"

            if ($IsLocked) {
                if ($Attempt -eq 1) {
                    Write-Host "[WAIT] '$ResolvedTargetDirectory': Datei durch laufendes Spiel/Server gesperrt. Warte auf Beenden..." -ForegroundColor Yellow
                }

                if ($Attempt -ge $MaxAttempts) {
                    # Als Pending ablegen, damit kein Build abbricht
                    Copy-Item -LiteralPath $ModJar.FullName -Destination $PendingJar -Force -ErrorAction SilentlyContinue
                    if (Test-Path -LiteralPath $StagedJar) { Remove-Item -LiteralPath $StagedJar -Force -ErrorAction SilentlyContinue }
                    Write-Host "[WARN] Timeout ($MaxAttempts s): Neue Version als Pending gestaged. Wird beim naechsten Start geladen." -ForegroundColor Yellow
                    break
                }

                Start-Sleep -Seconds 1
            } else {
                if (Test-Path -LiteralPath $StagedJar) { Remove-Item -LiteralPath $StagedJar -Force -ErrorAction SilentlyContinue }
                throw "Deployment nach '$ResolvedTargetDirectory' ist fehlgeschlagen: $ErrorMessage"
            }
        }
    }
}

Write-Host "[OK] Build, Bibliotheks-Entpacken und Deployment erfolgreich beendet!" -ForegroundColor Green

