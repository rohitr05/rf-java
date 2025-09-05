pipeline {
  agent any
  options {
    timestamps()
    timeout(time: 45, unit: 'MINUTES')
  }
  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
    // Windows Python root (so it can be changed per agent without editing the file)
    string(name: 'PY_HOME', defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313', description: 'Root folder containing python.exe and Scripts\\')
  	string(name: 'JAVA_HOME', defaultValue: 'C:\\Program Files\\Java\\jdk-17', description: 'java-home')
    string(name: 'M2_HOME', defaultValue: 'C:\\Users\\Anjaly\\OneDrive\\Documents\\Rohit\\apache-maven-3.9.11', description: 'maven-home')
  }

  environment {
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"
    JAVA_HOME = "${params.JAVA_HOME}"
    M2_HOME = "${params.M2_HOME}"
    PY_HOME = "${params.PY_HOME}"
    // Set the PATH for the current pipeline run
    PATH    = "${env.PY_HOME};${env.PY_HOME}\\Scripts;${env.JAVA_HOME}\\bin;${env.M2_HOME}\\bin;${env.PATH}"
  }


  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Pre-flight Diagnostics') {
      steps {
        // This stage now runs without JAVA_TOOL_OPTIONS, preventing the stderr issue.
        powershell '''
          $ErrorActionPreference = "Stop"
          Write-Host "=== ENV SNAPSHOT ==="
          Write-Host "WORKSPACE: $((Resolve-Path ".").Path)"
          Write-Host "PATH: $env:PATH"
          Write-Host "JAVA_HOME: $env:JAVA_HOME"
          Write-Host "M2_HOME: $env:M2_HOME"
          Write-Host "PYTHON_HOME: $env:PY_HOME"

          Write-Host "`n=== TOOL VERSIONS ==="
          where.exe java
          where.exe mvn
          where.exe python
          java -version
          mvn -v
          python --version

          Write-Host "`n=== PORT ${env:RF_PORT} PRE-CHECK ==="
          try {
            $inUse = Test-NetConnection -ComputerName "127.0.0.1" -Port ${env:RF_PORT} -InformationLevel Quiet
            if ($inUse) {
              Write-Host "WARNING: Port ${env:RF_PORT} appears IN USE before start. Showing netstat..."
              netstat -ano | findstr ":${env:RF_PORT}"
            } else {
              Write-Host "Port ${env:RF_PORT} free."
            }
          } catch { Write-Host "Note: Test-NetConnection not available or failed: $($_.Exception.Message)" }
        '''
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        // Using 'bat' is fine for Maven on Windows
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          $ws = (Resolve-Path ".").Path
          Write-Host "Workspace: $ws"

          if (!(Test-Path "server\\target")) {
            throw "server\\target not found in $ws"
          }

          $jarFile = Get-ChildItem -Path "server\\target" -Filter "rf-keywords-rbc-1.0.0.jar" -File `
                      | Sort-Object LastWriteTime -Descending | Select-Object -First 1
          if (-not $jarFile) {
            throw "No runnable *-shaded.jar found in server\\target."
          }

          $jarToRun = $jarFile.FullName
          Write-Host "`n=== Starting KeywordServer from: $jarToRun ==="

          # If previous PID exists, try to stop to avoid port conflict
          if (Test-Path "server.pid") {
            try {
              $old = Get-Content "server.pid" | Select-Object -First 1
              if ($old) {
                Write-Host "Found old server.pid ($old). Attempting to stop..."
                Stop-Process -Id $old -Force -ErrorAction SilentlyContinue
              }
            } catch { Write-Host "Stop previous PID note: $($_.Exception.Message)" }
            finally { Remove-Item "server.pid" -ErrorAction SilentlyContinue }
          }

          $bindHost = "${env:RF_HOST}"
          $port     = [int]"${env:RF_PORT}"
          
          # **FIX**: Apply file.encoding only to this specific Java process
          $args = "-Dfile.encoding=UTF-8 -Drf.port=$port -Drf.host=$bindHost -jar `"$jarToRun`""
          Write-Host ("java " + $args)

          # Launch detached and capture PID + logs
          $proc = Start-Process -FilePath "java" -ArgumentList $args `
                                -RedirectStandardOutput "keywordserver.out" `
                                -RedirectStandardError  "keywordserver.err" `
                                -PassThru -WindowStyle Hidden
          $serverPid = $proc.Id
          Set-Content -Path "server.pid" -Value $serverPid
          
          Write-Host ("Spawned KeywordServer PID=$serverPid. Waiting for port $port to open...")

          # Health-check TCP port (up to ~60s)
          $deadline = (Get-Date).AddSeconds(60)
          $ready = $false
          while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            try { 
              $conn = Test-NetConnection -ComputerName "127.0.0.1" -Port $port -InformationLevel Quiet
              if ($conn) {
                $ready = $true
                break 
              }
            } catch { /* ignore connection refused */ }
            Write-Host "." -NoNewline
          }
          Write-Host "" # Newline after dots

          if (-not $ready) {
            Write-Host "------ KEYWORD SERVER FAILED TO START ------"
            if (Test-Path "keywordserver.err") { Get-Content "keywordserver.err" -Tail 50 | Out-Host }
            if (Test-Path "keywordserver.out") { Get-Content "keywordserver.out" -Tail 50 | Out-Host }
            throw "KeywordServer did not open port $port within 60 seconds."
          }

          Write-Host ("SUCCESS: KeywordServer is UP at http://127.0.0.1:${port}")
        '''
      }
    }

    stage('Setup Python venv') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          if (Test-Path ".venv") {
            Write-Host "Virtual environment .venv already exists. Skipping creation."
          } else {
            Write-Host "Creating Python virtual environment..."
            python -m venv .venv
          }
          
          Write-Host "`nActivating venv and installing/upgrading dependencies..."
          .\\.venv\\Scripts\\python -m pip install --upgrade pip
          .\\.venv\\Scripts\\pip install -r requirements.txt | Out-Host

          Write-Host "`n--- Tool Versions from venv ---"
          .\\.venv\\Scripts\\python --version
          .\\.venv\\Scripts\\robot --version
          .\\.venv\\Scripts\\pabot --version
        '''
      }
    }

    stage('Run Robot Framework Tests') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          $outDir    = "results/robot"
          $allureOut = "results/allure"
          
          # Clean previous results
          if (Test-Path "results") { Remove-Item -Recurse -Force "results" }
          New-Item -ItemType Directory -Force -Path $outDir    | Out-Null
          New-Item -ItemType Directory -Force -Path $allureOut | Out-Null
          
          $procs = [int]("${env:PROCESSES}")
          Write-Host "Using pabot with $procs processes."

          Write-Host "`n=== Running pabot against tests/ folder ==="
          
          # **FIX**: Use the BASE parameter from the environment for the test run
          .\\.venv\\Scripts\\pabot `
            --processes $procs `
            --outputdir $outDir `
            --listener "allure_robotframework;$allureOut" `
            --variable BASE:"${env:BASE}" `
            tests

          $code = $LASTEXITCODE
          Write-Host ("Pabot finished with exit code: $code")
          if ($code -ne 0) { throw "Robot/pabot tests failed with exit code $code." }
        '''
      }
    }

    stage('Publish Allure Report') {
      steps {
        // This step correctly points to the allure results path
        allure includeProperties: false, jdk: '', results: [[path: 'results/allure']]
      }
    }
  }

  post {
    always {
      script {
        // Archive all relevant logs and reports for debugging and review
        archiveArtifacts artifacts: 'results/**/*, keywordserver.*, server.pid', fingerprint: true
      }
      
      powershell '''
        Write-Host "=== Teardown: Stopping KeywordServer ==="
        if (Test-Path "server.pid") {
          try {
            $serverPid = Get-Content "server.pid" | Select-Object -First 1
            if ($serverPid -and (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
              Write-Host "Stopping KeywordServer PID $serverPid ..."
              Stop-Process -Id $serverPid -Force
            } else {
              Write-Host "Server PID $serverPid not found or already stopped."
            }
          } catch { Write-Host "Could not stop process: $($_.Exception.Message)" }
          finally { Remove-Item "server.pid" -ErrorAction SilentlyContinue }
        } else {
          Write-Host "No server.pid found to stop."
        }
      '''
    }
  }
}