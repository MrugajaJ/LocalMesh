# LocalMesh PowerShell build script
Write-Host "Building all LocalMesh Java modules..." -ForegroundColor Cyan

$modules = @("controller", "sidecar", "cli", "api")

foreach ($module in $modules) {
    Write-Host "Building $module..." -ForegroundColor Yellow
    mvn -f "$module/pom.xml" package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Failed to build $module" -ForegroundColor Red
        exit 1
    }
}

Write-Host "✓ All Java modules built successfully!" -ForegroundColor Green
