# Backend JAR build and Docker image build PowerShell script

Write-Host "🔨 Starting Maven build..."
$mvnResult = & mvn clean package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Maven build failed!"
    exit 1
}
Write-Host "✅ Maven build completed!"

Write-Host "🐳 Starting Docker image build..."
$dockerResult = & docker-compose build --no-cache
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Docker image build failed!"
    exit 1
}
Write-Host "✅ Docker image build completed!"

Write-Host "🎉 Backend build and image creation completed!" 