# Zabali hru jako samostatnou aplikaci s pribalenou Javou (jpackage).
#
#   powershell -File packaging\package.ps1            -> dist\MinecraftClaude\MinecraftClaude.exe (+ zip)
#
# Potrebuje JDK 17+ s jpackage (JAVA_HOME) a hotovy fat jar (mvn package).
# Typ app-image: slozka s .exe a runtime Javy, bez instalatoru - nepotrebuje
# WiX. Data hry nejsou uvnitr: Launch je dava do %APPDATA%\MinecraftClaude.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem "$root\target\minecraft-base-*.jar" | Where-Object { $_.Name -notlike 'original-*' } | Select-Object -First 1
if (-not $jar) { throw "Chybi fat jar - nejdriv mvn package" }

$version = ($jar.BaseName -replace '^minecraft-base-', '')
$stage = "$root\target\jpackage-input"
$dist = "$root\dist"
Remove-Item -Recurse -Force $stage, "$dist\MinecraftClaude" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage, $dist | Out-Null
Copy-Item $jar.FullName $stage

$jpackage = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\jpackage.exe" } else { "jpackage" }

# java.desktop: AWT pro fonty a ImageIO (PNG); jdk.unsupported: LWJGL (Unsafe).
& $jpackage --type app-image `
    --name MinecraftClaude `
    --app-version $version `
    --input $stage `
    --main-jar $jar.Name `
    --main-class mc.Launch `
    --add-modules java.base,java.desktop,java.logging,jdk.unsupported `
    --java-options '-Djava.awt.headless=true' `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --dest $dist
if ($LASTEXITCODE -ne 0) { throw "jpackage selhal" }

$zip = "$dist\MinecraftClaude-$version-windows.zip"
Remove-Item -Force $zip -ErrorAction SilentlyContinue
Compress-Archive -Path "$dist\MinecraftClaude" -DestinationPath $zip
Write-Host "Hotovo: $dist\MinecraftClaude\MinecraftClaude.exe"
Write-Host "Zip:    $zip"
