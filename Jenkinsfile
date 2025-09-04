pipeline {
  agent any
  options { timestamps() }

  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'staging', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST', defaultValue: '0.0.0.0', description: 'KeywordServer bind host')
    string(name: 'RF_PORT', defaultValue: '8270', description: 'KeywordServer port')
    string(name: 'BASE',    defaultValue: 'https://httpbin.org', description: 'Base API URL for tests')
    string(name: 'PROCESSES', defaultValue: '4', description: 'pabot parallel processes')
    string(name: 'PY_HOME', defaultValue: 'C:\\Users\\Anjaly\\AppData\\Local\\Programs\\Python\\Python313', description: 'Python home (folder with python.exe and Scripts)')
  }

  environment {
    RF_HOST = "${params.RF_HOST}"
    RF_PORT = "${params.RF_PORT}"
    BASE    = "${params.BASE}"

    // Windows Python (configurable per agent)
    PY_HOME     = "${params.PY_HOME}"
    PYTHON_EXE  = "${env.PY_HOME}\\python.exe"
    PY_SCRIPTS  = "${env.PY_HOME}\\Scripts"

    // POSIX-style result dirs (kept as-is)
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
  }

  stages {
    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Build KeywordServer (Maven)') {
      steps { bat 'mvn -B -DskipTests -pl server -am clean package' }
    }

    stage('Start KeywordServer') {
      steps {
        retry(2) {
          powershell '''
            $ErrorActionPreference = "Stop"

            $targetDir = Join-Path $env:WORKSPACE "server\\target"
            $stdout    = Join-Path $targetDir "keywordserver.out.log"
            $stderr    = Join-Path $targetDir "keywordserver.err.log"
            $pidFile   = Join-Path $targetDir "keywordserver.pid"

            # Stop previous server if PID file exists
            if (Test-Path $pidFile) {
              try {
                $serverPid = Get-Content $pidFile | Select-Object -First 1
                if ($serverPid) { Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue }
              } catch { }
              Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
            }

            # Free the port if something is already listening
            try {
              $conn = Get-NetTCPConnection -State Listen -LocalPort ([int]$env:RF_PORT) -ErrorAction Stop | Select-Object -First 1
              if ($conn) {
                try { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue } catch { }
                Start-Sleep -Seconds 2
              }
            } catch {
              $line = netstat -ano | Select-String -Pattern "LISTENING.*:$($env:RF_PORT)\\s"
              if ($line) {
                $parts = ($line.ToString() -split "\\s+") | Where-Object { $_ -ne "" }
                $pidCol = $parts[-1]
                if ($pidCol -match '^\\d+$') {
                  try { Stop-Process -Id [int]$pidCol -Force -ErrorAction SilentlyContinue } catch { }
                  Start-Sleep -Seconds 2
                }
              }
            }

            # Pick newest JAR
            $jar = Get-ChildItem -Path $targetDir -Filter "rf-keywords-rbc-*.jar" |
                   Sort-Object LastWriteTime -Descending | Select-Object -First 1
            if (-not $jar) { throw "KeywordServer jar not found under $targetDir" }

            Write-Host "Starting KeywordServer: $($jar.FullName) on $($env:RF_PORT) (bind $($env:RF_HOST))"

            # Start detached (cmd /c start /B) with redirection
            $cmd = "cmd.exe"
            $cmdArgs = '/c start "RF KeywordServer" /B java -Drf.port={0} -Drf.host={1} -jar "{2}" 1>"{3}" 2>"{4}"' `
                       -f $env:RF_PORT, $env:RF_HOST, $jar.FullName, $stdout, $stderr
            Start-Process -FilePath $cmd -ArgumentList $cmdArgs -WorkingDirectory $targetDir

            # Try to capture PID (non-fatal if it fails)
            Start-Sleep -Seconds 2
            try {
              $javaProcs = Get-CimInstance -ClassName Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
                           Select-Object ProcessId, CommandLine
            } catch {
              $javaProcs = Get-WmiObject -Class Win32_Process -Filter "Name='java.exe'" |
                           Select-Object ProcessId, CommandLine
            }
            $match = $javaProcs | Where-Object {
              $_.CommandLine -like "*-Drf.port=$($env:RF_PORT)*" -and $_.CommandLine -like "*$($jar.Name)*"
            } | Select-Object -First 1
            if ($null -ne $match) { Set-Content -Path $pidFile -Value $match.ProcessId }

            # Probe localhost up to 120s
            $deadline = (Get-Date).AddSeconds(120)
            $ok = $false
            do {
              Start-Sleep -Seconds 2
              try { $ok = Test-NetConnection -ComputerName 127.0.0.1 -Port ([int]$env:RF_PORT) -InformationLevel Quiet }
              catch { $ok = $false }
            } until ($ok -or (Get-Date) -gt $deadline)

            if (-not $ok) {
              Write-Warning "KeywordServer not ready on port $($env:RF_PORT). Recent stdout/stderr follow."
              if (Test-Path $stdout) { Write-Host "---- STDOUT tail ----"; Get-Content $stdout -Tail 80 | Write-Host }
              if (Test-Path $stderr) { Write-Host "---- STDERR tail ----"; Get-Content $stderr -Tail 80 | Write-Host }
              throw "KeywordServer not ready on port $($env:RF_PORT)"
            }

            Write-Host "KeywordServer is accepting connections on 127.0.0.1:$($env:RF_PORT)"
          '''
        }
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        bat 'if not exist "%PYTHON_EXE%" ( echo ERROR: Python not found at "%PYTHON_EXE%" & exit /b 1 )'
        bat '"%PYTHON_EXE%" -m pip install -U pip'
        bat '"%PYTHON_EXE%" -m pip install robotframework robotframework-pabot allure-robotframework'
        bat 'if not exist "%ALLURE_RESULTS%" mkdir "%ALLURE_RESULTS%"'
        bat 'if not exist "%ROBOT_RESULTS%"  mkdir "%ROBOT_RESULTS%"'
        // *** FIXED: use %PROCESSES% so Windows expands the Jenkins parameter ***
        bat '"%PY_SCRIPTS%\\pabot.exe" --processes %PROCESSES% --testlevelsplit --listener "allure_robotframework;%ALLURE_RESULTS%" --outputdir "%ROBOT_RESULTS%" api_smoke.robot sql_demo.robot fix_demo.robot'
      }
    }

    stage('Publish Allure Report') {
      steps {
        allure includeProperties: false, jdk: '', reportBuildPolicy: 'ALWAYS',
               results: [[path: "${env.ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'server/target/*.jar, server/target/keywordserver.*.log, results/**, **/server.pid', fingerprint: true
      junit allowEmptyResults: true, testResults: 'results/robot/output*.xml'

      // Clean shutdown of KeywordServer
      powershell '''
        $ErrorActionPreference = "SilentlyContinue"
        $pidFile   = Join-Path $env:WORKSPACE "server\\target\\keywordserver.pid"
        $serverPid = $null
        if (Test-Path $pidFile) {
          $serverPid = Get-Content $pidFile | Select-Object -First 1
          if ($serverPid) { Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue }
          Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
        } else {
          $targetJar = (Get-ChildItem -Path (Join-Path $env:WORKSPACE "server\\target") -Filter "rf-keywords-rbc-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
          if ($targetJar) {
            try {
              $procs = Get-CimInstance -ClassName Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
                       Select-Object ProcessId, CommandLine
            } catch {
              $procs = Get-WmiObject -Class Win32_Process -Filter "Name='java.exe'" |
                       Select-Object ProcessId, CommandLine
            }
            $procs | Where-Object {
              $_.CommandLine -like "*-Drf.port=$($env:RF_PORT)*" -and $_.CommandLine -like "*$targetJar*"
            } | ForEach-Object {
              Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            }
          }
        }
      '''
    }
  }
}