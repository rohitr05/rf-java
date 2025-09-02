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

          # Find the newest jar (shade may replace the plain jar)
          $jar = Get-ChildItem -Path $targetDir -Filter "*.jar" |
                 Sort-Object LastWriteTime -Descending |
                 Select-Object -First 1

          if (-not $jar) { throw "No JAR found under $targetDir" }

          $stdout = Join-Path $targetDir "keywordserver.out.log"
          $stderr = Join-Path $targetDir "keywordserver.err.log"
          $pidFile = Join-Path $targetDir "keywordserver.pid"

          $java = (Get-Command java).Source
          $args = "-Drf.port=$env:RF_PORT -Drf.host=$env:RF_HOST -jar `"$($jar.FullName)`""

          Write-Host "Starting KeywordServer: $($jar.FullName) on $($env:RF_PORT) (bind $($env:RF_HOST))"

          # NOTE: Do NOT combine -NoNewWindow with -WindowStyle; they are mutually exclusive
          $p = Start-Process -FilePath $java `
              -ArgumentList $args `
              -WorkingDirectory $targetDir `
              -WindowStyle Hidden `
              -RedirectStandardOutput $stdout `
              -RedirectStandardError  $stderr `
              -PassThru

          # Save PID for cleanup
          Set-Content -Path $pidFile -Value $p.Id

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

          # Finish this step so pipeline can proceed to tests
          exit 0
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
      // Keep artifacts and test results
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
        }
      '''
    }
  }
}