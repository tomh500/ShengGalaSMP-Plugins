param(
    [Parameter(Mandatory)]
    [ValidateSet('GalVoice', 'LuoBackup', 'BwLegacyCombat', 'ShengGalaMetadata', 'Minigames')]
    [string]$Project,
    [Parameter(Mandatory)]
    [string[]]$DependencyJars,
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot "$Project/src/main"
if ($Project -eq 'GalVoice') {
    $source = Join-Path $PSScriptRoot 'GalVoice/spigot/src/main'
    $sources = @(Get-ChildItem "$source/java", "$PSScriptRoot/GalVoice/common/src/main/java" -Recurse -Filter '*.java' |
        ForEach-Object FullName)
} else {
    if ($Test) { throw '-Test is available for GalVoice.' }
    $sources = @(Get-ChildItem "$source/java" -Recurse -Filter '*.java' | ForEach-Object FullName)
}

$separator = [IO.Path]::PathSeparator
$classpath = ($DependencyJars | ForEach-Object { (Resolve-Path -LiteralPath $_).Path }) -join $separator
$release = if ($Project -eq 'ShengGalaMetadata') { 17 } else { 8 }
$output = (New-Item -ItemType Directory -Force "$PSScriptRoot/build").FullName
$classes = (New-Item -ItemType Directory (Join-Path $output ([Guid]::NewGuid().ToString()))).FullName
try {
    & javac --release $release -encoding UTF-8 -classpath $classpath -d $classes @sources
    if ($LASTEXITCODE) { throw "Compilation failed for $Project." }
    if ($Test) {
        $testClasspath = $classes, $classpath -join $separator
        $testClasses = (New-Item -ItemType Directory (Join-Path $classes 'tests')).FullName
        & javac --release $release -encoding UTF-8 -classpath $testClasspath -d $testClasses "$PSScriptRoot/GalVoice/tests/VoiceTest.java"
        if ($LASTEXITCODE) { throw 'GalVoice test compilation failed.' }
        & java -classpath ($testClasses, $testClasspath -join $separator) top.luotiany1.galvoice.VoiceTest
        if ($LASTEXITCODE) { throw 'GalVoice tests failed.' }
    }
    $jar = Join-Path $output "$Project.jar"
    & jar --create --file $jar -C $classes top -C "$source/resources" .
    if ($LASTEXITCODE) { throw "Packaging failed for $Project." }
    Write-Output $jar
} finally {
    if ([IO.Path]::GetDirectoryName((Resolve-Path -LiteralPath $classes).Path) -ne $output) {
        throw 'Unexpected staging directory.'
    }
    Remove-Item -LiteralPath $classes -Recurse -Force
}
