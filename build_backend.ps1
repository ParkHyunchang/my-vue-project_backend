# Backend JAR build and Docker image build PowerShell script

Write-Host "🔨 Starting Maven build..."
$mvnResult = & mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Maven build failed!"
    exit 1
}
Write-Host "✅ Maven build completed!"

Write-Host "🧹 Cleaning up existing containers..."
$dockerDownResult = & docker-compose down
if ($LASTEXITCODE -ne 0) {
    Write-Host "⚠️ Warning: Failed to clean up containers, but continuing..."
}

# Force remove any existing containers with these names
Write-Host "🗑️ Force removing any stuck containers..."
$removeResult = & docker rm -f vue_personal_project-backend vue_personal_project-backend-db 2>$null
Write-Host "✅ Cleanup completed!"

Write-Host "🐳 Starting Docker image build..."
$dockerResult = & docker-compose build --no-cache backend
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Docker image build failed!"
    exit 1
}
Write-Host "✅ Docker image build completed!"

Write-Host "🚀 Starting Docker containers..."
$dockerUpResult = & docker-compose up -d backend
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Docker containers start failed!"
    exit 1
}
Write-Host "✅ Docker containers started!"

Write-Host "🎉 Backend build, image creation, and container start completed!" 