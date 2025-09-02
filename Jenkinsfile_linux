pipeline {
  agent any
  options { timestamps(); ansiColor('xterm'); buildDiscarder(logRotator(numToKeepStr: '20')) }

  environment {
    RF_HOST = '0.0.0.0'
    RF_PORT = '8270'
    BASE    = 'https://httpbin.org'
    ALLURE_RESULTS = 'results/allure'
    ROBOT_RESULTS  = 'results/robot'
  }

  stages {
    stage('Checkout') { steps { checkout scm } }

    stage('Build KeywordServer (Maven)') {
      steps { sh 'mvn -B -DskipTests package' }
    }

    stage('Start KeywordServer') {
      steps {
        sh '''
          set -e
          JAR_PATH="$(ls **/*-shaded.jar 2>/dev/null || true)"
          if [ -z "$JAR_PATH" ]; then JAR_PATH="$(ls target/*-shaded.jar)"; fi
          nohup java -Drf.port="${RF_PORT}" -Drf.host="${RF_HOST}" -jar "$JAR_PATH" > keywordserver.log 2>&1 &
          echo $! > keywordserver.pid

          echo "[INFO] Waiting for /rest..."
          for i in $(seq 1 40); do
            if curl -sf "http://127.0.0.1:${RF_PORT}/rest" >/dev/null; then
              echo "[INFO] Server ready"; exit 0; fi; sleep 1; done
          echo "[ERROR] Server not ready in time"; exit 1
        '''
      }
    }

    stage('Install Python deps') {
      steps {
        sh '''
          python3 -m pip -q install -U pip
          pip3 -q install "robotframework>=6.1" "robotframework-pabot>=3.2" "allure-robotframework>=2.13.5"
        '''
      }
    }

    stage('Run tests with pabot (4)') {
      steps {
        sh '''
          set -e
          mkdir -p "${ALLURE_RESULTS}" "${ROBOT_RESULTS}"

          start=$(date +%s)
          pabot \
            --processes 4 \
            --testlevelsplit \
            --listener "allure_robotframework;${ALLURE_RESULTS}" \
            --outputdir "${ROBOT_RESULTS}" \
            --variable "BASE:${BASE}" \
            api_smoke.robot \
            sql_demo.robot \
            fix_demo.robot
          end=$(date +%s)
          echo "ELAPSED_SECONDS=$((end-start))" | tee results/time.txt
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
        // requires Allure Jenkins plugin
        allure includeProperties: false, jdk: '', results: [[path: "${ALLURE_RESULTS}"]]
      }
    }
  }

  post {
    always {
      sh 'if [ -f keywordserver.pid ]; then kill $(cat keywordserver.pid) 2>/dev/null || true; fi'
    }
  }
}
