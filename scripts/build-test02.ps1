param([switch]$RunTests, [switch]$Lint, [string]$TestKeyFile, [string]$TestRequestsFile, [string]$TestWeeksRequestsFile)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $env:LOCALAPPDATA 'CodexAndroid'
if ($TestKeyFile -and -not $RunTests) { throw 'Use -RunTests together with -TestKeyFile.' }
& node (Join-Path $PSScriptRoot 'sync-ai-assets.mjs')
if ($LASTEXITCODE -ne 0) { throw 'AI asset sync failed.' }
# Windows/JDK 17 worker argument files can corrupt a Chinese classpath even after
# the AGP path check is bypassed. Build an exact source mirror under an ASCII path.
$buildRoot = Join-Path $toolRoot 'builds/seu-test02'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
$resolvedBuild = (Resolve-Path -LiteralPath $buildRoot).Path
$allowedBuild = [IO.Path]::GetFullPath((Join-Path $toolRoot 'builds')).TrimEnd('\') + '\'
if (-not $resolvedBuild.StartsWith($allowedBuild, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected build mirror location.' }
foreach ($relative in @('app/src', 'gradle')) {
    $sourceDir = Join-Path $projectRoot $relative
    $destDir = [IO.Path]::GetFullPath((Join-Path $resolvedBuild $relative))
    if (-not $destDir.StartsWith($resolvedBuild.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid mirror target.' }
    & robocopy $sourceDir $destDir /MIR /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Source mirror failed: $relative" }
}
foreach ($relative in @('settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew.bat', 'gradlew', 'app/build.gradle.kts', 'app/proguard-rules.pro')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $relative) -Destination (Join-Path $resolvedBuild $relative) -Force
}
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'java') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $toolRoot 'sdk-build'
    if (Test-Path -LiteralPath (Join-Path $sdk 'platforms/android-35/android.jar')) { $env:ANDROID_HOME = $sdk }
}
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Install JDK 17 and Android SDK 35, then set JAVA_HOME and ANDROID_HOME.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$localGradle = Join-Path $toolRoot 'gradle/gradle-8.9/bin/gradle.bat'
$gradle = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $resolvedBuild 'gradlew.bat' }
$arguments = @(':app:assembleTrialTwo', '--no-daemon', '--max-workers=1', '-Dorg.gradle.jvmargs=-Xmx1200m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.parallel=false', '-Pandroid.overridePathCheck=true', '--console=plain')
if ($RunTests) { $arguments += ':app:testTrialTwoUnitTest' }
if ($Lint) { $arguments += ':app:lintTrialTwo' }
$previousKeyFile = $env:SEU_AI_TEST_KEY_FILE
$previousRequestsFile = $env:SEU_AI_TEST_REQUESTS_FILE
$previousWeeksRequestsFile = $env:SEU_AI_WEEKS_REQUESTS_FILE
if ($TestKeyFile) { $TestKeyFile = (Resolve-Path -LiteralPath $TestKeyFile).Path }
if ($TestRequestsFile) { $TestRequestsFile = (Resolve-Path -LiteralPath $TestRequestsFile).Path }
if ($TestWeeksRequestsFile) { $TestWeeksRequestsFile = (Resolve-Path -LiteralPath $TestWeeksRequestsFile).Path }
Push-Location $resolvedBuild
try {
    if ($TestKeyFile) {
        if (-not (Test-Path -LiteralPath $TestKeyFile -PathType Leaf)) { throw 'Test key file does not exist.' }
        $env:SEU_AI_TEST_KEY_FILE = (Resolve-Path -LiteralPath $TestKeyFile).Path
    }
    if ($TestRequestsFile) {
        if (-not $RunTests -or -not $TestKeyFile) { throw 'Live requests require -RunTests and -TestKeyFile.' }
        $env:SEU_AI_TEST_REQUESTS_FILE = (Resolve-Path -LiteralPath $TestRequestsFile).Path
    }
    if ($TestWeeksRequestsFile) {
        if (-not $RunTests -or -not $TestKeyFile) { throw 'Live week requests require -RunTests and -TestKeyFile.' }
        $env:SEU_AI_WEEKS_REQUESTS_FILE = $TestWeeksRequestsFile
    }
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build or tests failed. No APK was published.' }
    if ($Lint) {
        [xml]$lintReport = Get-Content -LiteralPath (Join-Path $resolvedBuild 'app/build/reports/lint-results-trialTwo.xml') -Raw
        if ($lintReport.issues.issue | Where-Object { $_.severity -in @('Error', 'Fatal') }) { throw 'Android lint has errors. No APK was published.' }
    }
    $output = Join-Path $projectRoot 'releases'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    # Copy bytes, not the build folder's EFS encryption attribute, so the APK can be shared.
    $apkBytes = [IO.File]::ReadAllBytes((Join-Path $resolvedBuild 'app/build/outputs/apk/trialTwo/app-trialTwo.apk'))
    [IO.File]::WriteAllBytes((Join-Path $output 'test02.apk'), $apkBytes)
    Get-FileHash -LiteralPath (Join-Path $output 'test02.apk') -Algorithm SHA256
} finally { Pop-Location; $env:SEU_AI_TEST_KEY_FILE = $previousKeyFile; $env:SEU_AI_TEST_REQUESTS_FILE = $previousRequestsFile; $env:SEU_AI_WEEKS_REQUESTS_FILE = $previousWeeksRequestsFile }
