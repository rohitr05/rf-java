pipeline {
  agent any
  options {
    timestamps()
    timeout(time: 45, unit: 'MINUTES')
  }
  environment {
    RF_PORT  = '8270'
    RF_HOST  = '127.0.0.1'
    PY       = 'python'   // change to full path if needed
  }

  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        // Build server module (fat jar produced under server\target\*-shaded.jar)
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          # Show where we are and what's inside target (debug-friendly)
          $ws = (Resolve-Path ".").Path
          Write-Host "Workspace: $ws"
          if (Test-Path "server\\target") {
            Write-Host "server\\target contents:"
            Get-ChildItem "server\\target" | ForEach-Object { $_.FullName } | Out-Host
          } else {
            throw "server\\target directory not found under workspace: $ws"
          }

          # Resolve latest shaded jar by glob to avoid hardcoded version
          $jarFile = Get-ChildItem -Path "server\\target" -Filter "*-shaded.jar" -File `
                      | Sort-Object LastWriteTime -Descending `
                      | Select-Object -First 1

          if (-not $jarFile) {
            throw "No *-shaded.jar found in server\\target (build step may have failed or artifact moved)."
          }

          $jarToRun = $jarFile.FullName
          $bindHost = "${env:RF_HOST}"
          $port     = [int]"${env:RF_PORT}"

          Write-Host ("Starting KeywordServer: {0} on {1}:{2}" -f $jarToRun, $bindHost, $port)

          # Start detached and capture PID + logs
          $args = "-Drf.port=$port -Drf.host=$bindHost -jar `"$jarToRun`""
          $proc = Start-Process -FilePath "java" -ArgumentList $args `
                                -RedirectStandardOutput "keywordserver.out" `
                                -RedirectStandardError  "keywordserver.err" `
                                -PassThru -WindowStyle Hidden

          $serverPid = $proc.Id
          Set-Content -Path "server.pid" -Value $serverPid

          # Health-check TCP port (up to ~60s)
          $deadline = (Get-Date).AddSeconds(60)
          do {
            Start-Sleep -Seconds 2
            $ready = Test-NetConnection -ComputerName "127.0.0.1" -Port $port -InformationLevel Quiet
            if ($ready) { break }
          } while ((Get-Date) -lt $deadline)

          if (-not $ready) {
            Write-Host "------ keywordserver.err (tail) ------"
            Get-Content "keywordserver.err" -Tail 200 | Out-Host
            throw "KeywordServer did not open port $port within 60 seconds."
          }

          Write-Host ("KeywordServer UP: http://{0}:{1} (PID={2})" -f $bindHost, $port, $serverPid)
        '''
      }
    }

    stage('Setup Python') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          python -m venv .venv
          .\\.venv\\Scripts\\python -m pip install --upgrade pip
          .\\.venv\\Scripts\\pip install robotframework==7.1.1 robotframework-pabot==2.17.0 `
                                    allure-robotframework==2.13.4 requests==2.*
        '''
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          $outDir    = "reports"
          $allureOut = "allure-results"
          New-Item -ItemType Directory -Force -Path $outDir    | Out-Null
          New-Item -ItemType Directory -Force -Path $allureOut | Out-Null

          # Adjust the trailing path if your .robot files are under ./tests
          .\\.venv\\Scripts\\pabot `
            --processes 4 `
            --outputdir $outDir `
            --listener "allure_robotframework;./$allureOut" `
            --variable BASE:http://httpbin.org `
            .

          if ($LASTEXITCODE -ne 0) { throw "Robot/pabot failed ($LASTEXITCODE)" }
        '''
      }
    }

    stage('Publish Allure Report') {
      steps {
        allure includeProperties: false, jdk: '', results: [[path: 'allure-results']]
      }
    }
  }

  post {
    always {
      powershell '''
        if (Test-Path "server.pid") {
          try {
            $serverPid = Get-Content "server.pid" | Select-Object -First 1
            Write-Host "Stopping KeywordServer PID $serverPid ..."
            Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
          } catch { Write-Host "Stop-Process note: $($_.Exception.Message)" }
          Remove-Item "server.pid" -ErrorAction SilentlyContinue
        }

        if (Test-Path "keywordserver.err") {
          Write-Host "----- KeywordServer ERR (tail) -----"
          Get-Content "keywordserver.err" -Tail 200 | Out-Host
        }
      '''
      archiveArtifacts artifacts: 'reports/*, allure-results/*, keywordserver.*', fingerprint: true
    }
  }
}