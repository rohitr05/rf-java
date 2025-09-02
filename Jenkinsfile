pipeline {
  agent any

  options {
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '20'))
    // ansiColor isn't always supported as a Declarative option on older Jenkins cores.
    // We'll wrap the heavy output stage with ansiColor instead.
  }

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

  stages {

    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        // Build just the server module and its dependencies
        bat 'mvn -B -DskipTests -pl server -am clean package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        powershell '''
          New-Item -ItemType Directory -Force -Path "$env:WORKSPACE/$env:ALLURE_RESULTS" | Out-Null
          New-Item -ItemType Directory -Force -Path "$env:WORKSPACE/$env:ROBOT_RESULTS"  | Out-Null
          $workspace = $env:WORKSPACE
          $targetDir = Join-Path $workspace "server\\target"
		  
          # Prefer shaded JAR if present, otherwise the newest jar
          $jar = Get-ChildItem -Path $targetDir -Filter "rf-keywords-rbc-1.0.0.jar","*.jar" |
                 Sort-Object LastWriteTime -Descending | Select-Object -First 1

          if (-not $jar) { throw "Shaded JAR not found under $targetDir" }

          $stdout = Join-Path $targetDir "keywordserver.out.log"
          $stderr = Join-Path $targetDir "keywordserver.err.log"

          $java = (Get-Command java).Source
          $args = "-Drf.port=$env:RF_PORT -Drf.host=$env:RF_HOST -jar `"$($jar.FullName)`""

          Write-Host "Starting KeywordServer: $($jar.FullName) on $($env:RF_PORT) (bind $($env:RF_HOST))"

          # keep your Start-Process model; just ensure separate stdout/stderr
          $p = Start-Process -FilePath $java `
              -ArgumentList $args `
              -WorkingDirectory $targetDir `
              -WindowStyle Hidden `
              -RedirectStandardOutput $stdout `
              -RedirectStandardError  $stderr `
              -PassThru

          # *** ONLY CHANGE MADE ***
          # Probe localhost instead of 0.0.0.0 (non-routable)
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

    stage('Install Python deps') {
      steps {
        bat '''
        if exist requirements.txt (
          python -m pip install -r requirements.txt
        ) else (
          python -m pip install --upgrade pip
          python -m pip install robotframework==6.1.1 robotframework-pabot==2.16.0 robotframework-requests==0.9.7 allure-robotframework==2.9.0
        )
        '''
      }
    }

    stage('Run tests with pabot (parallel)') {
      steps {
        ansiColor('xterm') {
          powershell '''
            $start = Get-Date
            $allureDir = Join-Path $env:WORKSPACE $env:ALLURE_RESULTS
            $robotDir  = Join-Path $env:WORKSPACE $env:ROBOT_RESULTS

            $listener = "allure_robotframework;$allureDir"

            # Build a Windows-safe command line for pabot
            $cmd = @(
              'pabot',
              '--processes',  '${env:PROCESSES}',
              '--outputdir',  "`"$robotDir`"",
              '--listener',   "`"$listener`"",
              '--variable',   "BASE:`"$env:BASE`"",
              '--variable',   "RF_HOST:`"$env:RF_HOST`"",
              '--variable',   "RF_PORT:`"$env:RF_PORT`"",
              '.'
            ) -join ' '

            Write-Host "Running: $cmd"
            cmd /c $cmd

            $elapsed = (Get-Date) - $start
            "Total parallel time: $($elapsed.ToString())" |
              Tee-Object -FilePath (Join-Path $robotDir 'duration.txt')
          '''
        }
      }
    }

    stage('Publish Allure Report') {
      steps {
        allure([
          includeProperties: false,
          jdk: '',
          results: [[path: "${env.ALLURE_RESULTS}"]]
        ])
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