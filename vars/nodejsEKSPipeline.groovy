def call(Map configMap) {
    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }
        environment {
            PROJECT = configMap.get("project")
            COMPONENT = configMap.get("component")
            APP_VERSION = ""
            AWS_ACCOUNT_ID = "204427113986"
            AWS_REGION = "us-east-1"
        }
        options {
            // disableConcurrentBuilds()
            // cancel pipeline due to timeout
            timeout(
                    time: 15,
                    unit: 'MINUTES'
            )
        }
        parameters {
            booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Deployment Required?')
        }

        stages {
            stage('Read Version') {
                steps {
                    script {
                        echo "reading the version for project ==> : ${configMap.project}"

                        // Read the package.json file into a Groovy object
                        def packageJSON = readJSON file: 'package.json'

                        // Access the version field
                        def packageVersion = packageJSON.version

                        echo "Current Version: ${packageVersion}"

                        // Optionally, set it as a global environment variable
                        APP_VERSION = packageVersion
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    script {
                        sh """
                           npm install
                        """
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    script {
                        sh """
                           npm test
                        """
                    }
                }
            }

            // by reaching this stage, we need to create a github token and add in Jenkins credentials
            stage('Dependabot Alerts Check') {
                steps {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
                        script {
                            def owner = 'maheshbabu22neeli'
                            def repo  = "${COMPONENT}"

                            def response = sh(
                                    script: """
                                curl -s -w "\\n%{http_code}" \\
                                    -H "Authorization: Bearer ${GITHUB_TOKEN}" \\
                                    -H "Accept: application/vnd.github+json" \\
                                    -H "X-GitHub-Api-Version: 2022-11-28" \\
                                    "https://api.github.com/repos/${owner}/${repo}/dependabot/alerts?severity=high,critical&state=open&per_page=100"
                            """,
                                    returnStdout: true
                            ).trim()

                            def parts      = response.tokenize('\n')
                            def httpStatus = parts[-1].trim()
                            def body       = parts[0..-2].join('\n')

                            if (httpStatus != '200') {
                                error "GitHub API call failed with HTTP ${httpStatus}. Check token permissions (security_events scope required).\nResponse: ${body}"
                            }

                            def alerts = readJSON text: body

                            if (alerts.size() == 0) {
                                echo "✅ No HIGH or CRITICAL Dependabot alerts found. Pipeline continues."
                            } else {
                                echo "🚨 Found ${alerts.size()} HIGH/CRITICAL Dependabot alert(s):"
                                alerts.each { alert ->
                                    def pkg      = alert.security_vulnerability?.package?.name ?: 'unknown'
                                    def severity = alert.security_advisory?.severity?.toUpperCase() ?: 'UNKNOWN'
                                    def summary  = alert.security_advisory?.summary ?: 'No summary'
                                    def fixedIn  = alert.security_vulnerability?.first_patched_version?.identifier ?: 'No fix available'
                                    echo "❌ [${severity}] ${pkg} — ${summary} (Fixed in: ${fixedIn})"
                                }
                                error "Pipeline failed: ${alerts.size()} HIGH/CRITICAL Dependabot alert(s) detected."
                            }
                        }
                    }
                }
            }


            stage('Build Docker Image') {
                steps {
                    withAWS(credentials: 'aws-creds', region: "${AWS_REGION}") {
                        sh """
                            docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION} .
                        """
                    }
                }
            }

            stage('Trivy OS Scan') {
                steps {
                    script {
                        // Generate table report
                        sh """
                            trivy image \
                                --scanners vuln \
                                --pkg-types os \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-os-report.txt \
                                --exit-code 0 \
                                ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                        """

                        // Print table to console
                        sh 'cat trivy-os-report.txt'

                        // Fail pipeline if vulnerabilities found
                        def scanResult = sh(
                                script: """
                                    trivy image \
                                        --scanners vuln \
                                        --pkg-types os \
                                        --severity HIGH,MEDIUM \
                                        --format table \
                                        --exit-code 1 \
                                        --quiet \
                                        ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                                """,
                                returnStatus: true
                        )

                        if (scanResult != 0) {
                            error "🚨 Trivy found HIGH/MEDIUM OS vulnerabilities. Pipeline failed."
                        } else {
                            echo "✅ No HIGH or MEDIUM OS vulnerabilities found. Pipeline continues."
                        }
                    }
                }
            }

            stage('Push Image tp ECR') {
                steps {
                    withAWS(credentials: 'aws-creds', region: "${AWS_REGION}") {
                        sh """
                            aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
                            docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                        """
                    }
                }
            }

        }
        post {
            always {
                echo 'I will always says hello world'
                cleanWs()
            }
            success {
                echo 'Job Success'
            }
            failure {
                echo 'Job Failure'
            }
        }
    }
}