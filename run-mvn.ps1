# 使用 JDK 25 运行 Maven
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "JAVA_HOME = $env:JAVA_HOME"
java -version
Write-Host "---"
mvn $args
