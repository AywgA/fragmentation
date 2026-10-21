$env:JAVA_HOME='C:\Program Files\Java\jdk-17';
$env:PATH=$env:JAVA_HOME + '\bin;' + $env:PATH;
Set-Location 'C:\mods\Fragmentation_mechanism';
& '.\gradlew.bat' --no-daemon clean build -x test --console=plain 2>&1 | Tee-Object -FilePath 'C:\mods\Fragmentation_mechanism\build_output.log' -Encoding utf8;
Write-Output ('EXITCODE:' + $LASTEXITCODE) | Add-Content -Path 'C:\mods\Fragmentation_mechanism\build_output.log';