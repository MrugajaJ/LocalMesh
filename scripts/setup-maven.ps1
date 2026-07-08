# setup-maven.ps1
# Downloads and extracts a portable Apache Maven instance inside the project.

$targetDir = Join-Path (Split-Path -Parent $PSScriptRoot) ".maven"
$mavenHome = Join-Path $targetDir "apache-maven-3.9.6"
$zipFile = Join-Path $targetDir "maven.zip"

if (Test-Path $mavenHome) {
    Write-Output "Maven is already configured at $mavenHome"
    exit 0
}

if (-not (Test-Path $targetDir)) {
    New-Item -ItemType Directory -Path $targetDir | Out-Null
}

$url = "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip"
Write-Output "Downloading Maven from $url ..."

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $zipFile -UseBasicParsing
    Write-Output "Extracting Maven..."
    Expand-Archive -Path $zipFile -DestinationPath $targetDir -Force
    Remove-Item $zipFile
    Write-Output "Successfully installed Maven to $mavenHome"
} catch {
    Write-Error "Failed to download/install Maven: $_"
    exit 1
}
