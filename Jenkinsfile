// Jenkinsfile (Windows-native: AnsiColor + KeywordServer + pabot + Allure)
pipeline {
  agent any

  options {
    timestamps()
    ansiColor('xterm')                    // pretty colors in Jenkins console
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  environment {
    RF_HOST = '0.0.0.0'
    RF_PORT = '8270'
    BASE    = 'https://httpbin.org'

    // POSIX-style for listeners/tools that prefer forward slashes
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'
  }

  stages {
    stage('Checkout') { steps { checkout scm } }

    stage('Build KeywordServer (Maven)') {
      steps {
        bat 'mvn -B -DskipTests package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          New-Item -ItemType Directory -Force -Path results | Out-Null

          # Find shaded jar created by server/pom.xml (main = com.example.rf.KeywordServer)
          $jar = Get-ChildItem -Recurse -Filter *-shaded.jar | Select-Object -First 1
          if (-not $jar) { throw "Shaded jar not found. Check Maven Shade plugin config." }
          Write-Host "Using JAR: $($jar.FullName)"

          # Start server in background and store PID
          $args = "-Drf.port=$env:RF_PORT -Drf.host=$env:RF_HOST -jar `"$($jar.FullName)`""
          $p = Start-Process -FilePath "java" -ArgumentList $args -PassThru -WindowStyle Hidden
          $p.Id | Out-File -FilePath "keywordserver.pid" -Encoding ascii

          # Wait up to 40s for /rest readiness
          $ready = $false
          for ($i=0; $i -lt 40; $i++) {
            try {
              $r = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/rest" -f $env:RF_PORT) -TimeoutSec 2
              if ($r.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            Start-Sleep -Seconds 1
          }
          if (-not $ready) { throw "KeywordServer did not become ready in time." }
          Write-Host "KeywordServer is ready."
        '''
      }
    }

    stage('Install Python deps') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          if (Get-Command py -ErrorAction SilentlyContinue) {
            py -3 -m pip install --upgrade pip
            py -3 -m pip install robotframework==6.1 robotframework-pabot==3.2 allure-robotframework==2.13.5
          } elseif (Get-Command python -ErrorAction SilentlyContinue) {
            python -m pip install --upgrade pip
            python -m pip install robotframework==6.1 robotframework-pabot==3.2 allure-robotframework==2.13.5
          } else {
            throw "Python 3.x not found on agent."
          }
        '''
      }
    }

    stage('Run tests with pabot (4) — colored console') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          New-Item -ItemType Directory -Force -Path $env:ALLURE_RESULTS | Out-Null
          New-Item -ItemType Directory -Force -Path $env:ROBOT_RESULTS  | Out-Null

          $start = Get-Date
          $pabotArgs = @(
            "--processes","4",
            "--testlevelsplit",
            "--consolecolors","on",
            "--consolemarkers","on",
            "--listener","allure_robotframework;$env:ALLURE_RESULTS",
            "--outputdir","$env:ROBOT_RESULTS",
            "--variable","BASE:$env:BASE",
            "api_smoke.robot","sql_demo.robot","fix_demo.robot"
          )

          if (Get-Command py -ErrorAction SilentlyContinue) { py -3 -m pabot $pabotArgs } else { pabot $pabotArgs }

          $elapsed = [int]((Get-Date) - $start).TotalSeconds
          "ELAPSED_SECONDS=$elapsed" | Out-File -FilePath "results\\time.txt" -Encoding ascii
          Write-Host "Parallel run finished in $elapsed s"
        '''
      }
      post {
        always {
          archiveArtifacts artifacts: 'results/**,keywordserver.log,keywordserver.pid', fingerprint: true
        }
      }
    }

    stage('Publish Allure Report') {
      when { expression { return fileExists(env.ALLURE_RESULTS) } }
      steps {
        // Enable if the Allure Jenkins plugin is installed
        allure includeProperties: false, jdk: '', results: [[path: "${ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      echo 'Stopping Keyword Server (if running)...'
      powershell '''
        if (Test-Path "keywordserver.pid") {
          $pid = Get-Content "keywordserver.pid" | Select-Object -First 1
          try { Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue } catch {}
        }
      '''
    }
  }
}