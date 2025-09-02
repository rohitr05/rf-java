pipeline {
  agent any

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
    PY_HOME = "${params.PY_HOME}"
    PYTHON_EXE = "${env.PY_HOME}\\python.exe"

    // POSIX-style (kept as in your original)
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'

    JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
  }

  options { timestamps() }

  stages {

    stage('Checkout') {
      steps { checkout scm }
    }

    stage('Build KeywordServer (Maven)') {
      steps { bat 'mvn -B -DskipTests -pl server -am clean package' }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"

          $workspace = $env:WORKSPACE
          $targetDir = Join-Path $workspace "server\\target"

          # Pick newest JAR (shade may replace the plain jar)
          $jar = Get-ChildItem -Path $targetDir -Filter "*.jar" |
                 Sort-Object LastWriteTime -Descending |
                 Select-Object -First 1
          if (-not $jar) { throw "No JAR found under $targetDir" }

          $stdout  = Join-Path $targetDir "keywordserver.out.log"
          $stderr  = Join-Path $targetDir "keywordserver.err.log"
          $pidFile = Join-Path $targetDir "keywordserver.pid"

          Write-Host "Starting KeywordServer: $($jar.FullName) on $($env:RF_PORT) (bind $($env:RF_HOST))"

          # Launch fully detached via cmd start /B and redirect output
          $cmd = "cmd.exe"
          $cmdArgs = '/c start "RF KeywordServer" /B java -Drf.port={0} -Drf.host={1} -jar "{2}" 1>"{3}" 2>"{4}"' -f $env:RF_PORT, $env:RF_HOST, $jar.FullName, $stdout, $stderr
          Start-Process -FilePath $cmd -ArgumentList $cmdArgs -WorkingDirectory $targetDir

          # Try to capture PID by matching commandline (jar name + port). Non-fatal if lookup fails.
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

          if ($null -ne $match) {
            Set-Content -Path $pidFile -Value $match.ProcessId
          } else {
            Write-Warning "Could not determine KeywordServer PID (continuing)."
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
        '''
      }
    }

    stage('Run Robot (pabot)') {
      steps {
        // Clear, one-line commands so failures are obvious in the log
        bat 'if not exist "%PYTHON_EXE%" ( echo ERROR: Python not found at "%PYTHON_EXE%" & exit /b 1 )'
        bat '"%PYTHON_EXE%" -m pip install -U pip'
        bat '"%PYTHON_EXE%" -m pip install robotframework robotframework-pabot allure-robotframework'
        bat 'if not exist "%ALLURE_RESULTS%" mkdir "%ALLURE_RESULTS%"'
        bat 'if not exist "%ROBOT_RESULTS%"  mkdir "%ROBOT_RESULTS%"'
        bat '"%PYTHON_EXE%" -m pabot --processes ${params.PROCESSES} --testlevelsplit --listener "allure_robotframework;%ALLURE_RESULTS%" --outputdir "%ROBOT_RESULTS%" api_smoke.robot sql_demo.robot fix_demo.robot'
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
      // Note: Robot's output.xml is not JUnit format; leaving as-is to match your existing setup.
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
          try {
            $targetJar = (Get-ChildItem -Path (Join-Path $env:WORKSPACE "server\\target") -Filter "*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
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
          } catch { }
        }
      '''
    }
  }
}
