pipeline {
  agent any

  options {
    // timestamps in console; ansiColor is used as a step below
    timestamps()
    skipDefaultCheckout(true)
  }

  parameters {
    choice(name: 'ENV', choices: ['dev', 'qa', 'sit', 'uat', 'prod'], description: 'Target environment')
    string(name: 'RF_HOST_OVERRIDE', defaultValue: '', description: 'Optional override for RF_HOST')
    string(name: 'RF_PORT_OVERRIDE', defaultValue: '', description: 'Optional override for RF_PORT')
    string(name: 'BASE_OVERRIDE',    defaultValue: '', description: 'Optional override for BASE URL')
    string(name: 'PABOT_PROCESSES',  defaultValue: '4', description: 'Number of pabot processes')
  }

  environment {
    // sensible defaults, POSIX-style subpaths for tools
    RF_HOST = '0.0.0.0'
    RF_PORT = '8270'
    BASE    = 'https://httpbin.org'

    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'
  }

  stages {
    stage('Checkout') {
      steps {
        ansiColor('xterm') {
          checkout scm
        }
      }
    }

    stage('Init Environment') {
      steps {
        ansiColor('xterm') {
          script {
            // Map ENV -> base values (customize as needed)
            def profiles = [
              dev   : [host: '0.0.0.0', port: '8270', base: 'https://httpbin.org'],
              qa    : [host: '0.0.0.0', port: '8270', base: 'https://httpbin.org'],
              sit   : [host: '0.0.0.0', port: '8270', base: 'https://httpbin.org'],
              uat   : [host: '0.0.0.0', port: '8270', base: 'https://httpbin.org'],
              prod  : [host: '0.0.0.0', port: '8270', base: 'https://httpbin.org']
            ]

            def sel = params.ENV?.trim() ?: 'dev'
            echo "Selected ENV: ${sel}"

            def p = profiles[sel] ?: profiles.dev

            // only override if user supplied a non-empty string
            env.RF_HOST = (params.RF_HOST_OVERRIDE?.trim()) ? params.RF_HOST_OVERRIDE.trim() : p.host
            env.RF_PORT = (params.RF_PORT_OVERRIDE?.trim()) ? params.RF_PORT_OVERRIDE.trim() : p.port
            env.BASE    = (params.BASE_OVERRIDE?.trim())    ? params.BASE_OVERRIDE.trim()    : p.base

            // normalize paths (Robot/Allure prefer forward slashes)
            env.ALLURE_RESULTS = 'results/allure'
            env.ROBOT_RESULTS  = 'results/robot'

            echo "RF_HOST=${env.RF_HOST}, RF_PORT=${env.RF_PORT}"
            echo "BASE=${env.BASE}"
            echo "ALLURE_RESULTS=${env.ALLURE_RESULTS}, ROBOT_RESULTS=${env.ROBOT_RESULTS}"
          }
        }
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        ansiColor('xterm') {
          bat 'mvn -B -DskipTests package'
        }
      }
    }

    stage('Start KeywordServer') {
      steps {
        ansiColor('xterm') {
          // Start Java KeywordServer and wait until the port is open
          powershell """
            \$ErrorActionPreference = 'Stop'
            Set-Location -LiteralPath (Join-Path \$env:WORKSPACE 'server')

            # find shaded jar
            \$jar = Get-ChildItem -Path (Join-Path (Resolve-Path 'target') '*-shaded.jar') -ErrorAction Stop | Select-Object -First 1
            if (-not \$jar) { throw 'Shaded jar not found. Ensure maven-shade-plugin is configured.' }

            Write-Host "Starting KeywordServer: \$((\$jar).FullName)"

            # start in background and capture PID
            \$proc = Start-Process -FilePath 'java' `
                     -ArgumentList @('-Dlog4j.configurationFile=log4j2.xml','-jar', \$jar.FullName) `
                     -NoNewWindow -PassThru
            \$proc.Id | Out-File -FilePath (Join-Path \$env:WORKSPACE 'keywordserver.pid') -Encoding ascii -Force

            # wait until the RF_PORT is open (max ~60s)
            \$port = [int]([string]\$env:RF_PORT)
            for (\$i=1; \$i -le 60; \$i++) {
              Start-Sleep -Seconds 1
              try {
                \$ok = Test-NetConnection -ComputerName '127.0.0.1' -Port \$port -InformationLevel Quiet
                if (\$ok) { Write-Host "KeywordServer is ready on port \$port"; break }
              } catch { }
              if (\$i -eq 60) { throw "KeywordServer not ready on port \$env:RF_PORT" }
            }
          """
        }
      }
    }

    stage('Install Python deps') {
      steps {
        ansiColor('xterm') {
          bat 'py -3 -m pip install -U pip'
          bat 'py -3 -m pip install robotframework robotframework-pabot allure-robotframework'
        }
      }
    }

    stage('Run tests with pabot (4)') {
      steps {
        ansiColor('xterm') {
          // time the whole pabot run; forward env vars to Robot tests
          powershell """
            \$ErrorActionPreference = 'Stop'
            New-Item -ItemType Directory -Force -Path \$env:ALLURE_RESULTS | Out-Null
            New-Item -ItemType Directory -Force -Path \$env:ROBOT_RESULTS  | Out-Null

            \$sw = [System.Diagnostics.Stopwatch]::StartNew()

            py -3 -m pabot `
              --processes \$env:PABOT_PROCESSES `
              --testlevelsplit `
              --listener "allure_robotframework;\$env:ALLURE_RESULTS" `
              --variable RF_HOST:\$env:RF_HOST `
              --variable RF_PORT:\$env:RF_PORT `
              --variable BASE:\$env:BASE `
              --outputdir "\$env:ROBOT_RESULTS" `
              api_smoke.robot sql_demo.robot fix_demo.robot

            \$sw.Stop()
            Write-Host ("Pabot wall-clock: {0:c}" -f \$sw.Elapsed)
          """
        }
      }
    }

    stage('Publish Allure Report') {
      steps {
        // Jenkins Allure plugin: set results path to env.ALLURE_RESULTS
        allure results: [[path: "${ALLURE_RESULTS}"]], reportBuildPolicy: 'ALWAYS'
      }
    }
  }

  post {
    always {
      echo 'Stopping Keyword Server (if running).'
      // Use a different variable name (not $PID!) to avoid read-only collision
      powershell """
        \$ErrorActionPreference = 'Continue'
        if (Test-Path (Join-Path \$env:WORKSPACE 'keywordserver.pid')) {
          \$serverPid = Get-Content (Join-Path \$env:WORKSPACE 'keywordserver.pid') | Select-Object -First 1
          if (\$serverPid) {
            try { Stop-Process -Id \$serverPid -Force -ErrorAction SilentlyContinue } catch { }
          }
          Remove-Item (Join-Path \$env:WORKSPACE 'keywordserver.pid') -ErrorAction SilentlyContinue
        }
      """
      archiveArtifacts artifacts: 'server/target/*-shaded.jar, results/robot/**, results/allure/**', fingerprint: true
      junit allowEmptyResults: true, testResults: 'results/robot/*.xml'
    }
  }
}