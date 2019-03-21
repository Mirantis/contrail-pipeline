/**
 *
 * OpenContrail update test pipeline
 *
 * Expected parameters:
 *
 * MCP_VERSION              MCP version for initial environment deployment
 * CONTEXT_NAME             Name of the context for initial deployment
 * DELETE_STACK_ON_FAILURE  delete stack after failed deployment
 * CP_REFSPEC               ref for contrail-pipeline repository
 */


common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()

String projectName = 'networking-ci-team'
String openstackCredentialsId = env.OPENSTACK_CREDENTIALS_ID ?: 'openstack-devcloud-credentials'
String saltMasterCredentials = env.SALT_MASTER_CREDENTIALS ?: 'salt-qa-credentials'
String saltMasterUrl

// gerrit variables
def gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'
def gerritName = env.GERRIT_NAME ?: 'mcp-jenkins'
def gerritHost = env.GERRIT_HOST ?: 'gerrit.mcp.mirantis.com'
def gerritProtocol = 'https'

// ssh variables
String sshUser = 'mcp-scale-jenkins'
String sshOpt = '-q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'

// test parameters
def stackTestJob = 'ci-contrail-tempest-runner'
def testConcurrency = '2'
def testPassThreshold = '96'
def testConf = '/home/rally/rally_reports/tempest_generated.conf'
def testTarget = 'cfg01*'
def testPattern = 'smoke'
def testResult

def openstackEnvironment = 'internal_cloud_v2_us'
def stackCleanupJob = 'delete-heat-stack-for-mcp-env'

def modelBackports


timeout(time: 8, unit: 'HOURS') {
    node ('python'){
        try {
            stage('Prepare'){
                wrap([$class: 'BuildUser']) {
                    if (env.BUILD_USER_ID) {
                        stackName = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                    } else {
                        stackName = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                    }
                }
                currentBuild.description = "${stackName}"
                contextsRootPath = WORKSPACE + '/' + JOB_NAME

                dir("${workspace}/contrail/contrail-pipeline") {
                    checkout([ $class: 'GitSCM',
                               branches: [ [name: 'FETCH_HEAD'], ],
                               userRemoteConfigs: [
                                       [url: 'ssh://gerrit.mcp.mirantis.com:29418/contrail/contrail-pipeline',
                                        refspec: CP_REFSPEC ?: 'master',
                                        credentialsId: 'gerrit'],
                               ],
                    ])

                    if (fileExists("update/${MCP_VERSION}/model_backports.yaml")) {
                        modelBackports = readYaml file: "update/${MCP_VERSION}/model_backports.yaml"
                    }
                }

            }

            stage('Getting test context'){

                // checkout contrail/contrail-pipeline repository
                dir("${workspace}/contrail/contrail-pipeline") {
                    testContextString = readFile "context/${MCP_VERSION}-${CONTEXT_NAME}-cicd.yaml"
                    testContextYaml = readYaml text: testContextString
                }

                testContextName = "oc${testContextYaml.default_context.opencontrail_version.replaceAll(/\./, '')}-${testContextYaml.default_context.openstack_version}"
                currentBuild.description = "${currentBuild.description}<br>${testContextName}"

                common.infoMsg("Using test context")
                common.infoMsg(testContextString)
            }

            stage('Deploy the environment'){

                build(job: 'create-mcp-env', parameters: [
                        string(name: 'STACK_NAME', value: stackName),
                        string(name: 'OS_AZ', value: "nova"),
                        string(name: 'OS_PROJECT_NAME', value: projectName),
                        textParam(name: 'COOKIECUTTER_EXTRA_CONTEXT', value: "${testContextString}"),
                        booleanParam(name: 'DELETE_STACK', value: false),
                        booleanParam(name: 'RUN_TESTS', value: false),
                        booleanParam(name: 'RUN_CVP', value: false),
                        booleanParam(name: 'COLLECT_LOGS', value: true),
                        textParam(name: 'CLUSTER_MODEL_OVERRIDES', value: ""),
                        string(name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment),
                        string(name: 'HEAT_TEMPLATES_REFSPEC', value: "refs/changes/31/34331/10"),
                        textParam(name: 'HEAT_STACK_CONTEXT', value: ""),
                        textParam(name: 'EXTRA_REPOS', value: ""),
                ],
                        wait: true,
                )
            }

            stage('Get environment information'){

                // checkout mcp-env/pipelines repository
                dir("${workspace}/mcp-env/pipelines") {
                    checkout([ $class: 'GitSCM',
                               branches: [ [name: 'FETCH_HEAD'], ],
                               userRemoteConfigs: [
                                       [url: 'ssh://gerrit.mcp.mirantis.com:29418/mcp-env/pipelines',
                                        refspec: 'master',
                                        credentialsId: 'gerrit'],
                               ],
                    ])
                    mirantisClouds = readYaml(file: 'clouds.yaml')
                    // Configure OpenStack credentials
                    if (mirantisClouds.clouds.containsKey(openstackEnvironment)) {
                        openstackCredentialsId = mirantisClouds.clouds."${openstackEnvironment}".jenkins_credentials_with_user_pass
                    } else {
                        error("There is no configuration for ${openstackEnvironment} underlay OpenStack in clouds.yaml")
                    }
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: openstackCredentialsId,
                                      usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD'], ]) {
                        env.OS_USERNAME = OS_USERNAME
                        env.OS_PASSWORD = OS_PASSWORD
                    }
                    env.OS_PROJECT_NAME = projectName
                    env.OS_CLOUD = openstackEnvironment

                    // create openstack env
                    openstackCmd = 'set +x; venv/bin/openstack '
                    sh 'virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient'
                    // get salt master host ip
                    saltMasterHost = sh(script: "${openstackCmd} stack show -f value -c outputs ${stackName} | jq -r .[0].output_value", returnStdout: true).trim()
                }

                currentBuild.description = "${currentBuild.description}<br>${saltMasterHost}"
                saltMasterUrl = "http://${saltMasterHost}:6969"
                common.infoMsg("Salt API is accessible via ${saltMasterUrl}")

                // get connection parameters for deployed salt master
                saltMaster = salt.connection(saltMasterUrl, saltMasterCredentials)
            }

            if (modelBackports) {
                stage('Apply patches for environment testing and update functionality') {

                    def systemRepo = 'salt-models/reclass-system'
                    def clusterRepo = 'mk/cookiecutter-templates'

                    dir("${workspace}/contrail/contrail-pipeline") {
                        sshagent (credentials: [sshUser]) {
                            sh "scp ${sshOpt} -r update/${MCP_VERSION}/patches ${sshUser}@${saltMasterHost}:/tmp"
                        }
                    }

                    for (change in modelBackports.keySet()) {
                        if (modelBackports[change].source == 'gerrit') {
                            def gerritChange = gerrit.getGerritChange(gerritName, gerritHost, change, gerritCredentials, true)

                            if (modelBackports[change].level == 'system') {
                                println("Change into reclass-system has been defined from gerrit: ${change}")
                                salt.cmdRun(saltMaster, 'cfg01.*', "cd /srv/salt/reclass/classes/system && git fetch ${gerritProtocol}://${gerritHost}/${systemRepo} ${gerritChange.currentPatchSet.ref} && git cherry-pick FETCH_HEAD")
                            } else if (modelBackports[change].level == 'cluster') {
                                println("Change into cluster model has been found and will be applied: #${change}")
                                salt.cmdRun(saltMaster, 'cfg01.*', "cd /srv/salt/reclass/classes/cluster/${stackName} && git fetch ${gerritProtocol}://${gerritHost}/${clusterRepo} ${gerritChange.currentPatchSet.ref} && git cherry-pick FETCH_HEAD")

                            }

                        } else if (modelBackports[change].source == 'patch') {
                            if (modelBackports[change].level == 'system') {
                                println("Change into reclass-system has been defined as patch: ${change}.patch")
                                salt.cmdRun(saltMaster, 'cfg01.*', "cd /srv/salt/reclass/classes/system && git am < /tmp/patches/${change}.patch")
                            } else if (modelBackports[change].level == 'cluster') {
                                println("Change into cluster model has been defined as patch: ${change}.patch")
                                salt.cmdRun(saltMaster, 'cfg01.*', "cd /srv/salt/reclass/classes/cluster/${stackName} && git am < /tmp/patches/${change}.patch")
                            }
                        }

                        if (modelBackports[change].apply == true) {
                            def target = modelBackports[change].single_node ? salt.getFirstMinion(saltMaster, modelBackports[change].target) : modelBackports[change].target
                            salt.syncAll(saltMaster, target)

                            if (modelBackports[change].excludes) {
                                salt.enforceStateWithExclude([saltId: saltMaster, target: "${target}", state: modelBackports[change].state, excludedStates: modelBackports[change].excludes])
                            } else {
                                salt.enforceState([saltId: saltMaster, target: "${target}", state: modelBackports[change].state])
                            }
                        }

                    }

                }

            }

            // Perform smoke tests to fail early
            stage('Run tests'){
                testMilestone = "MCP1.1"
                testModel = "cookied_oc${testContextYaml.default_context.opencontrail_version.replaceAll(/\./, '')}"
                testPlan = "${testMilestone}-Networking-${new Date().format('yyyy-MM-dd')}"
                testBuild = build(job: stackTestJob, parameters: [
                        string(name: 'SALT_MASTER_URL', value: saltMasterUrl),
                        string(name: 'TEST_CONF', value: testConf),
                        string(name: 'TEST_TARGET', value: testTarget),
                        string(name: 'TEST_CONCURRENCY', value: testConcurrency),
                        string(name: 'TEST_PATTERN', value: testPattern),
                        string(name: 'TEST_PASS_THRESHOLD', value: testPassThreshold),
                        booleanParam(name: 'DELETE_STACK', value: false),
                        booleanParam(name: 'TESTRAIL', value: false),
                        string(name: 'TEST_MILESTONE', value: "${testMilestone}"),
                        string(name: 'TEST_MODEL', value: "${testModel}"),
                        string(name: 'TEST_PLAN', value: "${testPlan}"),
                        booleanParam(name: 'FAIL_ON_TESTS', value: true),
                ],
                        wait: true,
                )

                testResult = testBuild.result
            }
        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            if ( (currentBuild.result != 'FAILURE')
                || (testResult == 'SUCCESS')
                || (common.validInputParam('DELETE_STACK_ON_FAILURE') && DELETE_STACK_ON_FAILURE.toBoolean() == true) ) {
                    stage ("Delete stack") {
                        build(job: stackCleanupJob, parameters: [
                                string(name: 'STACK_NAME', value: stackName),
                                string(name: 'OS_PROJECT_NAME', value: projectName),
                                string(name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment),
                                string(name: 'OPENSTACK_API_CREDENTIALS', value: openstackCredentialsId),
                            ],
                            propagate: false,
                            wait: true,
                        )
                    }
            }
        }
    }
}
