param(
    [string]$KeystorePath = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\keystores\ddubi-release.jks'),
    [string]$EnvironmentFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$ApiBaseUrl = 'https://j15e202.p.ssafy.io/api/v1'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $KeystorePath)) {
    throw "Release keystore not found: $KeystorePath"
}
$KeystorePath = (Resolve-Path -LiteralPath $KeystorePath).Path
if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw "Release keystore is not a file: $KeystorePath"
}
if (-not (Test-Path -LiteralPath $EnvironmentFile)) {
    throw "Client environment file not found: $EnvironmentFile"
}

$keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
$keytoolPath = if ($keytoolCommand) {
    $keytoolCommand.Source
} else {
    @(
        'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
        'C:\Program Files\Java\jdk-17\bin\keytool.exe'
    ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $keytoolPath) {
    throw 'keytool.exe was not found. Install or configure a JDK before building.'
}

$secureStorePassword = Read-Host 'DDUBI release keystore password' -AsSecureString
$storePasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureStorePassword)
$secureKeyPassword = Read-Host 'DDUBI key password (press Enter to reuse the keystore password)' -AsSecureString
$keyPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKeyPassword)

try {
    $plainStorePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($storePasswordPointer)
    $plainKeyPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPasswordPointer)
    if ([string]::IsNullOrEmpty($plainKeyPassword)) {
        $plainKeyPassword = $plainStorePassword
    }

    $env:DDUBI_KEYSTORE_PATH = $KeystorePath
    $env:DDUBI_KEYSTORE_PASSWORD = $plainStorePassword
    $env:DDUBI_KEY_PASSWORD = $plainKeyPassword
    $env:DDUBI_KEY_ALIAS = 'ddubi'

    & $keytoolPath -list -keystore $KeystorePath `
        -storepass:env DDUBI_KEYSTORE_PASSWORD -alias $env:DDUBI_KEY_ALIAS | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not open the release keystore. Check the keystore password.'
    }

    Push-Location (Join-Path $PSScriptRoot '..')
    try {
        & flutter build apk --release `
            "--dart-define-from-file=$EnvironmentFile" `
            "--dart-define=API_BASE_URL=$ApiBaseUrl"
        if ($LASTEXITCODE -ne 0) {
            throw "Flutter release build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:DDUBI_KEYSTORE_PATH = $null
    $env:DDUBI_KEYSTORE_PASSWORD = $null
    $env:DDUBI_KEY_PASSWORD = $null
    $env:DDUBI_KEY_ALIAS = $null
    $plainStorePassword = $null
    $plainKeyPassword = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storePasswordPointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPasswordPointer)
}
