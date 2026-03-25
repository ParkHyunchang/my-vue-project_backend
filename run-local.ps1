# .env 파일을 읽어 환경변수로 설정 후 Spring Boot 실행
Get-Content .env |
  Where-Object { $_ -notmatch '^\s*#' -and $_ -match '=' } |
  ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item "env:$($k.Trim())" $v.Trim()
  }

mvn spring-boot:run
