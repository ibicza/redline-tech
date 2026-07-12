param(
    [Parameter(Mandatory = $true)]
    [string]$CommitMessage
)

$ErrorActionPreference = "Stop"

git diff --check
.\gradlew clean build --build-cache

git add -A
git commit -m $CommitMessage
git push origin gpt

$head = git rev-parse HEAD
$remote = git rev-parse origin/gpt

if ($head -ne $remote) {
    throw "Push verification failed: HEAD=$head origin/gpt=$remote"
}

Write-Host "Published successfully: $head"