pipeline {
  agent any
  options {
    timestamps()
    timeout(time: 45, unit: 'MINUTES')
  }
  environment {
    RF_PORT   = '8270'
    RF_HOST   = '127.0.0.1'     // Robot tests point to http://127.0.0.1:8270/...
    JAR_PATH  = 'server\\target\\rf-keywords-rbc-1.0.0-shaded.jar'
    PY       = 'python'         // or full path if you prefer
  }

  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        // Build only the server module and its deps; skip tests to speed up.
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          $jarToRun = "${env:JAR_PATH}"
          $bindHost = "${env:RF_HOST}"
          $port     = [int]"${env:RF_PORT}"

          if (!(Test-Path $jarToRun)) {
            throw "Jar not found: $jarToRun"
          }

          Write-Host ("Starting KeywordServer: {0} on {1}:{2}" -f $jarToRun, $bindHost, $port)

          # Start server detached and capture PID + logs
          $args = "-Drf.port=$port -Drf.host=$bindHost -jar `"$jarToRun`""
          $proc = Start-Process -FilePath "java" -ArgumentList $args `
                                -RedirectStandardOutput "keywordserver.out" `
                                -RedirectStandardError  "keywordserver.err" `
                                -PassThru -WindowStyle Hidden

          $serverPid = $proc.Id
          Set-Content -Path "server.pid" -Value $serverPid

          # Wait for port 8270 to be reachable (max ~60s)
          $deadline = (Get-Date).AddSeconds(60)
          do {
            Start-Sleep -Seconds 2
            $ready = Test-NetConnection -ComputerName "127.0.0.1" -Port $port -InformationLevel Quiet
            if ($ready) { break }
          } while ((Get-Date) -lt $deadline)

          if (-not $ready) {
            Get-Content -Path "keywordserver.err" -ErrorAction SilentlyContinue | Out-Host
            throw "KeywordServer did not open port $port within 60 seconds."
          }

          Write-Host "KeywordServer is UP at $($bindHost):$port (PID=$serverPid)"
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

          $outDir = "reports"
          $allureOut = "allure-results"
          New-Item -ItemType Directory -Force -Path $outDir      | Out-Null
          New-Item -ItemType Directory -Force -Path $allureOut   | Out-Null

          # Run all .robot at repo root and subfolders; adjust if you keep them under ./tests
          .\\.venv\\Scripts\\pabot `
            --processes 3 `
            --outputdir $outDir `
            --listener "allure_robotframework;./$allureOut" `
            --variable BASE:http://httpbin.org `
            ./tests

          if ($LASTEXITCODE -ne 0) { throw "Robot/pabot failed with code $LASTEXITCODE" }
        '''
      }
    }

    stage('Publish Allure Report') {
      steps {
        // Requires "Allure Jenkins" plugin
        allure includeProperties: false, jdk: '', results: [[path: 'allure-results']]
      }
    }
  }

  post {
    always {
      // Always try to stop the KeywordServer to keep the agent clean
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