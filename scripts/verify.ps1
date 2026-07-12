$ErrorActionPreference = "Stop"

Write-Host "Checking Git diff..."
git diff --check

Write-Host "Building project..."
.\gradlew clean build --build-cache

Write-Host "Git status:"
git status --short