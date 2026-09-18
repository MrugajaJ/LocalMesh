@echo off
echo Building all LocalMesh Java modules...

mvn -f controller/pom.xml package -DskipTests
if %errorlevel% neq 0 exit /b %errorlevel%

mvn -f sidecar/pom.xml package -DskipTests
if %errorlevel% neq 0 exit /b %errorlevel%

mvn -f cli/pom.xml package -DskipTests
if %errorlevel% neq 0 exit /b %errorlevel%

mvn -f api/pom.xml package -DskipTests
if %errorlevel% neq 0 exit /b %errorlevel%

echo ✓ All Java modules built successfully!
