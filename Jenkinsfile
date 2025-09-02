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
        // unchanged
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $workspace = $env:WORKSPACE
          $targetDir = Join-Path $workspace "server\\target"

          # MINIMAL FIX: find latest *.jar (your build produces rf-keywords-rbc-1.0.0.jar)
          $jar = Get-ChildItem -Path $targetDir -Filter "*.jar" |
                 Sort-Object LastWriteTime -Descending |
                 Select-Object -First 1

          if (-not $jar) { throw "No JAR found under $targetDir" }

          $stdout = Join-Path $targetDir "keywordserver.out.log"
          $stderr = Join-Path $targetDir "keywordserver.err.log"

          $java = (Get-Command java).Source
          $args = "-Drf.port=$env:RF_PORT -Drf.host=$env:RF_HOST -jar `"$($jar.FullName)`""

          Write-Host "Starting KeywordServer: $($jar.FullName) on $($env:RF_PORT) (bind $($env:RF_HOST))"

          # keep Start-Process; ensure separate stdout/stderr
          $p = Start-Process -FilePath $java `
              -ArgumentList $args `
              -WorkingDirectory $targetDir `
              -WindowStyle Hidden `
              -RedirectStandardOutput $stdout `
              -RedirectStandardError  $stderr `
              -PassThru

          # MINIMAL FIX: probe localhost instead of 0.0.0.0 (non-routable)
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
        '''
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        // unchanged: install CLI deps & execute tests in parallel
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
        // unchanged
        allure includeProperties: false, jdk: '', results: [[path: "${env.ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      echo 'Stopping Keyword Server (if running).'
      powershell '''
        $pidFile = Join-Path $env:WORKSPACE "keywordserver.pid"
        if (Test-Path $pidFile) {
          $ksPid = Get-Content $pidFile | Select-Object -First 1
          if ($ksPid) {
            Write-Host "Stopping KeywordServer PID=$ksPid"
            try { Stop-Process -Id ([int]$ksPid) -Force -ErrorAction Stop } catch { Write-Warning "Stop-Process failed: $_" }
          }
          Remove-Item $pidFile -Force
        } else {
          Write-Host "No PID file; nothing to stop."
        }
      '''
      archiveArtifacts artifacts: "${env.ROBOT_RESULTS}/**, ${env.ALLURE_RESULTS}/**", allowEmptyArchive: true
    }
  }
}