param(
    [ValidateSet('init','sign')][string]$Action = 'sign',
    [string]$Request,
    [string]$Out,
    [string]$PrivateHome = (Join-Path $env:USERPROFILE '.photerest-search-auth')
)
$ErrorActionPreference = 'Stop'
$passwordPath = Join-Path $PrivateHome 'passphrase.dpapi.xml'
if ($Action -eq 'init') {
    if (Test-Path -LiteralPath $passwordPath) { throw 'Authority password already exists; refusing to overwrite.' }
    New-Item -ItemType Directory -Path $PrivateHome -Force | Out-Null
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $PrivateHome '/inheritance:r' '/grant:r' "${identity}:(OI)(CI)F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict private directory access.' }
    $random = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random)
    $secure = ConvertTo-SecureString ([Convert]::ToBase64String($random)) -AsPlainText -Force
    $secure | Export-Clixml -LiteralPath $passwordPath
} else {
    if (!$Request -or !$Out) { throw '-Request and -Out are required.' }
    $secure = Import-Clixml -LiteralPath $passwordPath
}
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$previous = $env:PHOTEREST_AUTH_PASSPHRASE
try {
    $env:PHOTEREST_AUTH_PASSPHRASE = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    $toolArgs = @((Join-Path $PSScriptRoot 'auth-tool.mjs'), $Action, '--home', $PrivateHome)
    if ($Action -eq 'sign') { $toolArgs += @('--request', $Request, '--out', $Out) }
    & node @toolArgs
    if ($LASTEXITCODE -ne 0) { throw 'Offline authorization tool failed.' }
} finally {
    $env:PHOTEREST_AUTH_PASSPHRASE = $previous
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
