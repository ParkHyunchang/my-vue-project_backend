# Backend JAR build and package PowerShell script

Write-Host "🔨 Starting Maven build..."
$mvnResult = & mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Maven build failed!"
    exit 1
}
Write-Host "✅ Maven build completed!"

# Create deployment directory
Write-Host "📁 Creating deployment package..."
$deployDir = "deployment"
if (Test-Path $deployDir) {
    Remove-Item -Recurse -Force $deployDir
}
New-Item -ItemType Directory -Path $deployDir | Out-Null

# Copy necessary files
Write-Host "📋 Copying deployment files..."
Copy-Item "target/todo-api-0.0.1-SNAPSHOT.jar" "$deployDir/app.jar"
Copy-Item "docker-compose.yml" "$deployDir/"
Copy-Item "Dockerfile" "$deployDir/"
Copy-Item "init.sql" "$deployDir/"
Copy-Item "vue_personal_project_backend_deploy.sh" "$deployDir/" -ErrorAction SilentlyContinue

# 배포 스크립트 권한 설정 (Linux 환경에서 실행될 때를 위해)
Write-Host "🔧 Setting deployment script permissions..."
$deployScript = "$deployDir/vue_personal_project_backend_deploy.sh"
if (Test-Path $deployScript) {
    # PowerShell에서는 직접 chmod를 사용할 수 없으므로, 스크립트 내에서 처리하도록 함
    Write-Host "✅ Deploy script copied - permissions will be set during deployment"
}

# Create tar file
Write-Host "📦 Creating tar package..."
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$tarFileName = "backend_deployment_$timestamp.tar.gz"

# Use tar command (available in Windows 10/11)
& tar -czf $tarFileName -C $deployDir .

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Tar creation failed!"
    exit 1
}

Write-Host "✅ Tar package created: $tarFileName"

# Clean up deployment directory
Remove-Item -Recurse -Force $deployDir

Write-Host "🎉 Build and package completed!"
Write-Host "📤 Upload $tarFileName to your NAS and extract it in the deployment directory"