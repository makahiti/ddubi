param(
    [Parameter(Mandatory=$true)][string]$Python,
    [string]$RoadDependencies
)
# 통합된 로컬 가공 도구의 회귀 검사. 다운로드/적재/운영 활성화는 하지 않는다.
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$previousPythonPath = $env:PYTHONPATH
Push-Location $repoRoot
try {
    if ($RoadDependencies) {
        $env:PYTHONPATH = (Resolve-Path -LiteralPath $RoadDependencies).Path
    }
    $testGroups = @(
        @('infra/scripts/tests', 'test_*.py'),
        @('tests', 'test_subway*.py'),
        @('tests', 'test_road*.py')
    )
    foreach ($group in $testGroups) {
        & $Python -m unittest discover -s $group[0] -p $group[1]
        if ($LASTEXITCODE -ne 0) { throw "Transit test group failed: $($group[0]) / $($group[1])" }
    }
} finally {
    $env:PYTHONPATH = $previousPythonPath
    Pop-Location
}
