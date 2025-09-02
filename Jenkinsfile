pipeline {
  agent any

  options {
    timestamps()
    ansiColor('xterm')
    buildDiscarder(logRotator(numToKeepStr: '20'))
    skipDefaultCheckout(false)
  }

  parameters {
    choice(name: 'ENV', choices: ['dev','qa','uat','prod'], description: 'Target environment')
    string(name: 'PABOT_PROCESSES', defaultValue: '4', description: 'Parallel workers for pabot')
  }

  // Safe defaults; can be overridden later if you want
  environment {
    RF_HOST = '0.0.0.0'
    RF_PORT = '8270'
    BASE    = 'https://httpbin.org'
    // POSIX-style subpaths for tools
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'
  }

  stages {

    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Init Environment') {
      steps {
        script {
          echo "Selected ENV: ${params.ENV}"

          // TODO: If you have different endpoints per env, switch here
          switch (params.ENV) {
            case 'dev':
              env.RF_HOST = env.RF_HOST ?: '0.0.0.0'
              env.RF_PORT = env.RF_PORT ?: '8270'
              env.BASE    = env.BASE    ?: 'https://httpbin.org'
              break
            case 'qa':
              // env.RF_HOST = '...'; env.RF_PORT = '...'; env.BASE = '...'
              break
            case 'uat':
              // ...
              break
            case 'prod':
              // ...
              break
          }

          // Echo so we never miss a null again (your previous log showed nulls)
          echo "RF_HOST=${env.RF_HOST}, RF_PORT=${env.RF_PORT}"
          echo "BASE=${env.BASE}"
          echo "ALLURE_RESULTS=${env.ALLURE_RESULTS}, ROBOT_RESULTS=${env.ROBOT_RESULTS}"
        }
      }
    }

    stage('Build KeywordServer (Maven)') {
      steps {
        bat 'mvn -B -DskipTests package'
      }
    }

    stage('Start KeywordServer') {
      steps {
        // SINGLE-QUOTED so PowerShell $... stays intact (no Groovy interpolation)
        powershell '''
          $ErrorActionPreference = "Stop"

          # locate shaded jar
          $jar = Get-ChildItem -Path "server\\target" -Filter "*-shaded.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
          if (-not $jar) { throw "Shaded jar not found. Ensure maven-shade-plugin produced it." }

          Write-Host "Starting KeywordServer: $($jar.FullName)"

          # Start the server (adjust args if your server expects -h/-p or system props)
          $args = @("-jar", $jar.FullName)
          $p = Start-Process -FilePath "java" -ArgumentList $args -PassThru -WindowStyle Hidden
          $serverPid = $p.Id
          Set-Content -Path "keywordserver.pid" -Value $serverPid

          # Wait until port is listening (~60s max)
          $deadline = (Get-Date).AddSeconds(60)
          do {
            Start-Sleep -Seconds 2
            $listening = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
                         Where-Object { $_.LocalPort -eq [int]$env:RF_PORT }
          } while (-not $listening -and (Get-Date) -lt $deadline)

          if (-not $listening) {
            throw "KeywordServer not ready on port $env:RF_PORT"
          }
          Write-Host "KeywordServer is ready on port $env:RF_PORT (PID=$serverPid)"
        '''
      }
    }

    stage('Install Python deps') {
      steps {
        powershell '''
          $ErrorActionPreference = "Stop"
          py -3 -m pip install -U pip
          py -3 -m pip install robotframework robotframework-pabot allure-robotframework
        '''
      }
    }

    stage('Run tests with pabot') {
      steps {
        // We need Groovy interpolation for ${params.PABOT_PROCESSES},
        // so keep double quotes and ESCAPE all PowerShell $ with backslash: \$env:...
        script {
          def procs = params.PABOT_PROCESSES ?: '4'
          powershell """
            \$ErrorActionPreference = "Stop"
            if (Test-Path "${env.ALLURE_RESULTS}") { Remove-Item -Recurse -Force "${env.ALLURE_RESULTS}" }
            if (Test-Path "${env.ROBOT_RESULTS}")  { Remove-Item -Recurse -Force "${env.ROBOT_RESULTS}"  }
            New-Item -ItemType Directory -Force -Path "${env.ALLURE_RESULTS}" | Out-Null
            New-Item -ItemType Directory -Force -Path "${env.ROBOT_RESULTS}"  | Out-Null

            py -3 -m pabot `
              --processes ${procs} `
              --testlevelsplit `
              --listener 'allure_robotframework;${env.ALLURE_RESULTS}' `
              --outputdir ${env.ROBOT_RESULTS} `
              --variable BASE:${env.BASE} `
              --variable RF_HOST:${env.RF_HOST} `
              --variable RF_PORT:${env.RF_PORT} `
              api_smoke.robot sql_demo.robot fix_demo.robot
          """
        }
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
      echo 'Stopping Keyword Server (if running)...'
      // SINGLE-QUOTED -> PowerShell $ variables untouched
      powershell '''
        $ErrorActionPreference = "Continue"
        if (Test-Path "keywordserver.pid") {
          $serverPid = (Get-Content "keywordserver.pid" | Select-Object -First 1)
          if ($serverPid -and (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
            Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
            Write-Host "Stopped KeywordServer PID=$serverPid"
          }
          Remove-Item "keywordserver.pid" -Force -ErrorAction SilentlyContinue
        }
      '''
      archiveArtifacts artifacts: "${env.ROBOT_RESULTS}/**", allowEmptyArchive: true
      junit allowEmptyResults: true, testResults: "${env.ROBOT_RESULTS}/*.xml"
    }
  }
}
