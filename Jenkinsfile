pipeline {
  agent any

  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
  }

  environment {
    // Defaults; can be overridden by parameters above
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"

    // POSIX-style for tools that prefer forward slashes
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
  }

  options {
    timestamps()
  }

  stages {

    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          $workspace = $env:WORKSPACE
          $targetDir = Join-Path $workspace "server\\target"

          # Find newest jar (shade may replace rf-keywords-rbc-1.0.0.jar)
          $jar = Get-ChildItem -Path $targetDir -Filter "*.jar" |
                 Sort-Object LastWriteTime -Descending |
                 Select-Object -First 1
          if (-not $jar) { throw "No JAR found under $targetDir" }

          $stdout = Join-Path $targetDir "keywordserver.out.log"
          $stderr = Join-Path $targetDir "keywordserver.err.log"
          $pidFile = Join-Path $targetDir "keywordserver.pid"

          Write-Host "Starting KeywordServer: $($jar.FullName) on $($env:RF_PORT) (bind $($env:RF_HOST))"

          # ---- LAUNCH FULLY DETACHED via cmd.exe /c start /B ----
          # Use cmd redirection to write logs to files.
          $cmd = "cmd.exe"
          $cmdArgs = '/c start "RF KeywordServer" /B java -Drf.port={0} -Drf.host={1} -jar "{2}" 1>"{3}" 2>"{4}"' -f $env:RF_PORT, $env:RF_HOST, $jar.FullName, $stdout, $stderr
          Start-Process -FilePath $cmd -ArgumentList $cmdArgs -WorkingDirectory $targetDir

          # Give the process a moment to spawn, then capture its PID by command line (jar + port)
          Start-Sleep -Seconds 2
          $javaProcs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
            [PSCustomObject]@{ Id = $_.ProcessId; Cmd = $_.CommandLine }
          }
          $match = $javaProcs | Where-Object {
            $_.Cmd -like "*-Drf.port=$($env:RF_PORT)*" -and $_.Cmd -like "*$($jar.Name)*"
          } | Select-Object -First 1

          if ($null -ne $match) {
            Set-Content -Path $pidFile -Value $match.Id
          } else {
            Write-Warning "Could not determine KeywordServer PID. Cleanup will be best-effort."
          }

          # Probe localhost (127.0.0.1), not 0.0.0.0
          $deadline = (Get-Date).AddSeconds(90)
          $ok = $false
          do {
            Start-Sleep -Seconds 2
            $ok = Test-NetConnection -ComputerName 127.0.0.1 -Port $env:RF_PORT -InformationLevel Quiet
          } until ($ok -or (Get-Date) -gt $deadline)

          if (-not $ok) {
            Write-Warning "KeywordServer not ready on port $($env:RF_PORT). Recent stdout/stderr follow."
            if (Test-Path $stdout) { Get-Content $stdout -Tail 80 | Write-Host }
            if (Test-Path $stderr) { Get-Content $stderr -Tail 80 | Write-Host }
            throw "KeywordServer not ready on port $($env:RF_PORT)"
          }

          Write-Host "KeywordServer is accepting connections on 127.0.0.1:$($env:RF_PORT)"
          # PowerShell step ends here (server keeps running)
        '''
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat 'py -3 -m pip install -U pip'
        bat 'py -3 -m pip install robotframework robotframework-pabot allure-robotframework'
        bat """
          py -3 -m pabot --processes ${params.PROCESSES} --testlevelsplit ^
            --listener "allure_robotframework;${env.ALLURE_RESULTS}" ^
            --outputdir ${env.ROBOT_RESULTS} ^
            api_smoke.robot sql_demo.robot fix_demo.robot
        """
      }
    }

    stage('Publish Allure Report') {
      steps {
        allure includeProperties: false, jdk: '', results: [[path: "${env.ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'server/target/*.jar, server/target/keywordserver.*.log, results/**', fingerprint: true
      junit allowEmptyResults: true, testResults: "${env.ROBOT_RESULTS}/output.xml"

      // Clean shutdown of KeywordServer (if started)
      powershell '''
        $pidFile = Join-Path $env:WORKSPACE "server\\target\\keywordserver.pid"
        if (Test-Path $pidFile) {
          try {
            $pid = Get-Content $pidFile | Select-Object -First 1
            if ($pid) { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue }
          } catch { }
        } else {
          # Best-effort cleanup: stop any java with our jar+port in its command line
          try {
            $target = (Get-ChildItem -Path (Join-Path $env:WORKSPACE "server\\target") -Filter "*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
            if ($target) {
              $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
                [PSCustomObject]@{ Id=$_.ProcessId; Cmd=$_.CommandLine }
              } | Where-Object {
                $_.Cmd -like "*-Drf.port=$($env:RF_PORT)*" -and $_.Cmd -like "*$target*"
              }
              $procs | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
            }
          } catch { }
        }
      '''
    }
  }
}