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
    PATH    = "${env.PATH};${env.PY_HOME};${env.PY_HOME}\\Scripts"

    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
  }


  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Pre-flight Diagnostics') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          Write-Host "=== ENV SNAPSHOT ==="
          Write-Host "WORKSPACE: $((Resolve-Path ".").Path)"
          Write-Host "PATH: $env:PATH"
          Write-Host "JAVA_HOME: $env:JAVA_HOME"
          Write-Host "M2_HOME: $env:M2_HOME"
          Write-Host "PYTHON_HOME: $env:PY_HOME"

          Write-Host "`n=== TOOL VERSIONS ==="
          cmd /c "where java" | Out-Host
          cmd /c "where mvn"  | Out-Host
          cmd /c "java -version" 2>&1 | Out-Host
          cmd /c "mvn -v"          | Out-Host

          Write-Host "`n=== PORT 8270 PRE-CHECK ==="
          try {
            $inUse = Test-NetConnection -ComputerName "127.0.0.1" -Port ${env:RF_PORT} -InformationLevel Quiet
            if ($inUse) {
              Write-Host "WARNING: Port ${env:RF_PORT} appears IN USE before start. Showing netstat..."
              cmd /c "netstat -ano | findstr :${env:RF_PORT}" | Out-Host
            } else {
              Write-Host "Port ${env:RF_PORT} free."
            }
          } catch { Write-Host "Note: Test-NetConnection not available or failed: $($_.Exception.Message)" }
        '''
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        // Produces server\target\original-*.jar and server\target\rf-keywords-rbc-*.jar (shaded, replaced)
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

          Write-Host "`n=== server\\target LISTING ==="
          Get-ChildItem "server\\target" | ForEach-Object { $_.FullName } | Out-Host

          # Prefer *-shaded.jar if present; otherwise the replaced artifact rf-keywords-rbc-*.jar (non-original)
          $jarFile = Get-ChildItem -Path "server\\target" -Filter "*-shaded.jar" -File `
                      | Sort-Object LastWriteTime -Descending | Select-Object -First 1
          if (-not $jarFile) {
            $jarFile = Get-ChildItem -Path "server\\target" -Filter "rf-keywords-rbc-*.jar" -File `
                        | Where-Object { -not $_.Name.StartsWith("original-") } `
                        | Sort-Object LastWriteTime -Descending | Select-Object -First 1
          }
          if (-not $jarFile) {
            throw "No runnable JAR found in server\\target (expected rf-keywords-rbc-*.jar)."
          }

          $jarToRun = $jarFile.FullName
          Write-Host "`n=== JAR DETAILS ==="
          $fi = Get-Item $jarToRun
          Write-Host ("Path: {0}" -f $fi.FullName)
          Write-Host ("Size: {0} bytes" -f $fi.Length)
          try {
            $sha = Get-FileHash -Algorithm SHA256 -Path $fi.FullName
            Write-Host ("SHA256: {0}" -f $sha.Hash)
          } catch { Write-Host "SHA256 unavailable: $($_.Exception.Message)" }

          # If previous PID exists, try to stop to avoid port conflict
          if (Test-Path "server.pid") {
            try {
              $old = Get-Content "server.pid" | Select-Object -First 1
              if ($old) {
                Write-Host "Found old server.pid ($old). Attempting to stop..."
                Stop-Process -Id $old -Force -ErrorAction SilentlyContinue
              }
              Remove-Item "server.pid" -ErrorAction SilentlyContinue
            } catch { Write-Host "Stop previous PID note: $($_.Exception.Message)" }
          }

          $bindHost = "${env:RF_HOST}"
          $port     = [int]"${env:RF_PORT}"
          Write-Host ("Starting KeywordServer: {0}" -f $jarToRun)
          Write-Host ("Bind: {0}  Port: {1}" -f $bindHost, $port)

          # Launch detached and capture PID + logs
          $args = "-Drf.port=$port -Drf.host=$bindHost -jar `"$jarToRun`""
          Write-Host ("java {0}" -f $args)
          $proc = Start-Process -FilePath "java" -ArgumentList $args `
                                -RedirectStandardOutput "keywordserver.out" `
                                -RedirectStandardError  "keywordserver.err" `
                                -PassThru -WindowStyle Hidden
          $serverPid = $proc.Id
          Set-Content -Path "server.pid" -Value $serverPid
          Set-Content -Path "jar.path"   -Value $jarToRun

          Write-Host ("Spawned KeywordServer PID={0}" -f $serverPid)

          # Tail first few lines quickly (non-blocking health hint)
          Start-Sleep -Seconds 2
          Write-Host "`n--- keywordserver.out (head) ---"
          if (Test-Path "keywordserver.out") { Get-Content "keywordserver.out" -TotalCount 40 | Out-Host } else { Write-Host "No stdout yet." }
          Write-Host "`n--- keywordserver.err (head) ---"
          if (Test-Path "keywordserver.err") { Get-Content "keywordserver.err" -TotalCount 40 | Out-Host } else { Write-Host "No stderr yet." }

          # Health-check TCP port (up to ~60s)
          $deadline = (Get-Date).AddSeconds(60)
          do {
            Start-Sleep -Seconds 2
            $ready = $false
            try { $ready = Test-NetConnection -ComputerName "127.0.0.1" -Port $port -InformationLevel Quiet } catch { $ready = $false }
            if ($ready) { break }
          } while ((Get-Date) -lt $deadline)

          if (-not $ready) {
            Write-Host "------ keywordserver.err (tail) ------"
            if (Test-Path "keywordserver.err") { Get-Content "keywordserver.err" -Tail 200 | Out-Host }
            Write-Host "------ keywordserver.out (tail) ------"
            if (Test-Path "keywordserver.out") { Get-Content "keywordserver.out" -Tail 200 | Out-Host }
            Write-Host "------ netstat :${env:RF_PORT} ------"
            cmd /c "netstat -ano | findstr :${env:RF_PORT}" | Out-Host
            throw "KeywordServer did not open port $port within 60 seconds."
          }

          Write-Host ("KeywordServer UP at http://{0}:{1}  (PID={2})" -f $bindHost, $port, $serverPid)
        '''
      }
    }

    stage('Setup Python') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          Write-Host "Creating venv..."
          python -m venv .venv

          Write-Host "`nPython version:"
          .\\.venv\\Scripts\\python --version | Out-Host

          Write-Host "Upgrading pip + installing deps..."
          .\\.venv\\Scripts\\python -m pip install --upgrade pip
          .\\.venv\\Scripts\\pip install robotframework==7.1.1 robotframework-pabot==2.17.0 `
                                    allure-robotframework==2.13.4 requests==2.* | Out-Host

          Write-Host "`nInstalled packages (top lines):"
          .\\.venv\\Scripts\\pip list | Select-Object -First 25 | Out-Host

          Write-Host "`nRobot/Pabot versions:"
          .\\.venv\\Scripts\\python -m robot --version | Out-Host
          .\\.venv\\Scripts\\pabot --version | Out-Host
        '''
      }
    }

    stage('Run Robot (pabot from tests/)') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          $outDir    = "reports"
          $allureOut = "allure-results"
          New-Item -ItemType Directory -Force -Path $outDir    | Out-Null
          New-Item -ItemType Directory -Force -Path $allureOut | Out-Null

          Write-Host "=== Test discovery under .\\tests ==="
          if (!(Test-Path "tests")) { throw "tests folder not found at workspace root." }
          $rfFiles = Get-ChildItem -Path "tests" -Filter "*.robot" -Recurse -File
          Write-Host ("Found {0} .robot files" -f $rfFiles.Count)
          $rfFiles | Select-Object -First 20 | ForEach-Object { Write-Host (" - {0}" -f $_.FullName) }
          if ($rfFiles.Count -eq 0) { throw "No .robot files found under tests/" }

          # Choose processes: either env override or CPU count
          $procs = [int]("${env:PABOT_PROCESSES}")
          if ($procs -le 0) { $procs = [Math]::Max(2, (Get-CimInstance Win32_Processor | Measure-Object -Sum NumberOfLogicalProcessors).Sum) }
          Write-Host ("Using pabot processes: {0}" -f $procs)

          Write-Host "`n=== Running pabot ==="
          .\\.venv\\Scripts\\pabot `
            --processes $procs `
            --outputdir $outDir `
            --listener "allure_robotframework;./$allureOut" `
            --variable BASE:https://postman-echo.com `
            tests

          $code = $LASTEXITCODE
          Write-Host ("Pabot exit code: {0}" -f $code)

          Write-Host "`n=== Output artifacts ==="
          Get-ChildItem $outDir | ForEach-Object { $_.FullName } | Out-Host

          Write-Host "`n=== Allure result artifacts ==="
          if (Test-Path $allureOut) {
            $all = Get-ChildItem $allureOut -File
            Write-Host ("Allure files: {0}" -f $all.Count)
            $all | Select-Object -First 10 | ForEach-Object { Write-Host (" - {0}" -f $_.Name) }
          } else {
            Write-Host "Allure folder missing."
          }

          if ($code -ne 0) { throw "Robot/pabot failed ($code)" }
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
        Write-Host "=== Teardown ==="
        if (Test-Path "server.pid") {
          try {
            $serverPid = Get-Content "server.pid" | Select-Object -First 1
            Write-Host "Stopping KeywordServer PID $serverPid ..."
            Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
          } catch { Write-Host "Stop-Process note: $($_.Exception.Message)" }
          Remove-Item "server.pid" -ErrorAction SilentlyContinue
        } else {
          Write-Host "No server.pid present."
        }

        if (Test-Path "keywordserver.err") {
          Write-Host "----- KeywordServer ERR (tail) -----"
          Get-Content "keywordserver.err" -Tail 200 | Out-Host
        } else {
          Write-Host "No keywordserver.err captured."
        }

        if (Test-Path "keywordserver.out") {
          Write-Host "----- KeywordServer OUT (tail) -----"
          Get-Content "keywordserver.out" -Tail 200 | Out-Host
        } else {
          Write-Host "No keywordserver.out captured."
        }
      '''
      archiveArtifacts artifacts: 'reports/*, allure-results/*, keywordserver.*, server.pid, jar.path', fingerprint: true
    }
  }
}
