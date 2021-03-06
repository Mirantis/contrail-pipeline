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

String projectName
String openstackCredentialsId = env.OPENSTACK_CREDENTIALS_ID ?: 'openstack-devcloud-credentials'
String saltMasterCredentials = env.SALT_MASTER_CREDENTIALS ?: 'salt-qa-credentials'
String saltMasterUrl

def cpRefSpec = env.CP_REFSPEC ?: 'master'

// gerrit variables
gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'
gerritName = env.GERRIT_NAME ?: 'mcp-jenkins'
gerritHost = env.GERRIT_HOST ?: 'gerrit.mcp.mirantis.com'
gerritProtocol = 'https'

// ssh variables
String sshUser = 'mcp-scale-jenkins'
String sshOpt = '-q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'

// test parameters
def stackTestJob = 'ci-contrail-tempest-runner'
def testConcurrency = '2'
def testPassThreshold = '80'
def testConf = '/home/rally/rally_reports/tempest_generated.conf'
def testTarget = 'cfg01*'
def testResult

def openstackEnvironment = 'internal_cloud_v2_us'
def stackCleanupJob = 'delete-heat-stack-for-mcp-env'

def modelBackports
def pipelineChangesMap = ['2018.4.0': ['mk/mk-pipelines': ['37681']]]
def updateChanges
def ocSaltFormulaPkg = 'salt-formula-opencontrail'
def reviewReposBaseUrl = "http://seed01-bud.infra.mirantis.net/mcp/review"
def saltFormulaReview = '38159'


def applyModelChanges(saltMaster, stackName, changesInfo, patchDir='/tmp/patches') {

    String systemRepo = 'salt-models/reclass-system'

    for (change in changesInfo.keySet()) {
        if (changesInfo[change].source == 'gerrit') {
            def gerritChange = gerrit.getGerritChange(gerritName, gerritHost, change, gerritCredentials, true)

            if (changesInfo[change].level == 'system') {
                println("Change into reclass-system has been defined from gerrit: ${change}")
                salt.cmdRun(saltMaster, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git fetch ${gerritProtocol}://${gerritHost}/${systemRepo} ${gerritChange.currentPatchSet.ref} && git cherry-pick FETCH_HEAD")
            } else {
                println("WARNING: gerrit change can be applied only for system model level ! Ignoring ${change} change")
            }

        } else if (changesInfo[change].source == 'patch') {
            if (changesInfo[change].level == 'system') {
                println("Change into reclass-system has been defined as patch: ${change}.patch")
                salt.cmdRun(saltMaster, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git am ${patchDir}/${change}.patch")
            } else if (changesInfo[change].level == 'cluster') {
                println("Change into cluster model has been defined as patch: ${change}.patch")
                salt.cmdRun(saltMaster, 'I@salt:master', "cd /srv/salt/reclass/ && git am --directory classes/cluster/${stackName} ${patchDir}/${change}.patch")
            }
        }

        if (changesInfo[change].apply == true) {
            def target = changesInfo[change].single_node ? salt.getFirstMinion(saltMaster, changesInfo[change].target) : changesInfo[change].target
            salt.syncAll(saltMaster, target)

            if (changesInfo[change].excludes) {
                salt.enforceStateWithExclude([saltId: saltMaster, target: "${target}", state: changesInfo[change].state, excludedStates: changesInfo[change].excludes])
            } else {
                salt.enforceState([saltId: saltMaster, target: "${target}", state: changesInfo[change].state])
            }
        }

    }
}


timeout(time: 8, unit: 'HOURS') {
    node ('python'){
        try {
            stage('Prepare'){
                wrap([$class: 'BuildUser']) {
                    if (!['scmChange', 'timer'].contains(env.BUILD_USER_ID)) {
                        stackName = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                        projectName = 'networking-team'
                    } else {
                        stackName = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                        projectName = 'networking-ci-team'
                    }
                }
                currentBuild.description = "${stackName}"
                contextsRootPath = WORKSPACE + '/' + JOB_NAME

                dir("${workspace}/contrail/contrail-pipeline") {
                    checkout([ $class: 'GitSCM',
                               branches: [ [name: 'FETCH_HEAD'], ],
                               userRemoteConfigs: [
                                       [url: 'ssh://gerrit.mcp.mirantis.com:29418/contrail/contrail-pipeline',
                                        refspec: cpRefSpec,
                                        credentialsId: 'gerrit'],
                               ],
                    ])

                    if (fileExists("update/${MCP_VERSION}/model_backports.yaml")) {
                        modelBackports = readYaml file: "update/${MCP_VERSION}/model_backports.yaml"
                    }

                    // update changes are required for update procedure
                    if (fileExists("update/${MCP_VERSION}/update_changes.yaml")) {
                        updateChanges = readYaml file: "update/${MCP_VERSION}/update_changes.yaml"
                    } else {
                        throw new RuntimeException("Update changes file is missing: update/${MCP_VERSION}/update_changes.yaml")
                    }
                }

            }

            stage('Getting test context') {

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

            stage('Deploy the environment') {

                heatTemplatesChange = gerrit.getGerritChange(gerritName, gerritHost, '34331', gerritCredentials, true)
                pipelineLibChanges = gerrit.getGerritChange(gerritName, gerritHost, '37411', gerritCredentials, true)
                build(job: 'create-mcp-env', parameters: [
                        string(name: 'STACK_NAME', value: stackName),
                        string(name: 'OS_AZ', value: "nova"),
                        string(name: 'OS_PROJECT_NAME', value: projectName),
                        textParam(name: 'COOKIECUTTER_EXTRA_CONTEXT', value: "${testContextString}"),
                        booleanParam(name: 'DELETE_STACK', value: false),
                        booleanParam(name: 'RUN_TESTS', value: false),
                        booleanParam(name: 'RUN_CVP', value: false),
                        booleanParam(name: 'COLLECT_LOGS', value: true),
                        textParam(name: 'CLUSTER_MODEL_OVERRIDES', value: env.CLUSTER_MODEL_OVERRIDES ?: "parameters.jenkins.client.lib.pipeline-library.branch e7476fe3bfeb8b927c56f23c9b6f6c7e1409f071 infra/config.yml"),
                        string(name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment),
                        string(name: 'HEAT_TEMPLATES_REFSPEC', value: "${heatTemplatesChange.currentPatchSet.ref}"),
                        textParam(name: 'HEAT_STACK_CONTEXT', value: ""),
                        textParam(name: 'EXTRA_REPOS', value: ""),
                ],
                        wait: true,
                )
            }

            stage('Get environment information') {

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

            stage('Upload data to env') {
                // Upload patches on salt master node
                dir("${workspace}/contrail/contrail-pipeline") {
                    sshagent (credentials: [sshUser]) {
                        sh "scp ${sshOpt} -r update/${MCP_VERSION}/patches ${sshUser}@${saltMasterHost}:/tmp"
                        sh "scp ${sshOpt} scripts/create-workloads.sh heat-templates/update-test.yml ${sshUser}@${saltMasterHost}:/tmp"
                    }
                }
            }

            if (modelBackports) {
                stage('Apply patches for environment testing and update functionality') {

                    // Update salt-formula pkg if we have needed backports on review
                    if (saltFormulaReview) {

                        def saltFormulaRepoUrl = "${reviewReposBaseUrl}/${saltFormulaReview}/salt-formulas/xenial/"
                        salt.cmdRun(saltMaster, 'I@salt:master',
                                "echo \"deb [arch=amd64] ${saltFormulaRepoUrl} xenial main\" > /etc/apt/sources.list.d/salt_formula_review.list " +
                                        "&& apt-key adv --fetch-keys ${saltFormulaRepoUrl}/archive-salt-formulas.key")

                        salt.cmdRun(saltMaster, 'I@salt:master', "echo \"Package: *\nPin: release l=salt-formulas-nightly\nPin-Priority: 1100\" > " +
                                "/etc/apt/preferences.d/salt_formula_review")

                        salt.cmdRun(saltMaster, 'I@salt:master', "apt-get update && apt-get --only-upgrade install ${ocSaltFormulaPkg}")
                    }

                    applyModelChanges(saltMaster, stackName, modelBackports)

                    try {
                        println("INFO: Verification of cluster model with model backports ...")
                        salt.cmdRun(saltMaster, 'I@salt:master', "reclass -iy")
                    } catch (Exception e) {
                        common.errorMsg('Can not validate current Reclass cluster model with applied model backports')
                        throw e
                    }
                }

            }

            stage('Apply required fixes') {
                println("INFO: Apply fix for PROD-28573")
                salt.cmdRun(saltMaster, 'I@salt:master', "sed -i 's/'host-passthrough'/'custom'/g' " +
                        "/srv/salt/env/prd/nova/files/ocata/nova-compute.conf.Debian")
                salt.cmdRun(saltMaster, 'I@salt:master', "sed -i 's/#cpu_model=<None>/cpu_model=IvyBridge/g' " +
                        "/srv/salt/env/prd/nova/files/ocata/nova-compute.conf.Debian")
                salt.enforceState(saltMaster, 'I@nova:compute', 'nova.compute')
            }

            def testMilestone = "MCP1.1"
            def testPlan = "${testMilestone}-Networking-${new Date().format('yyyy-MM-dd')}"
            def testBuildParams = [
                    string(name: 'SALT_MASTER_URL', value: saltMasterUrl),
                    string(name: 'TEST_CONF', value: testConf),
                    string(name: 'TEST_TARGET', value: testTarget),
                    string(name: 'TEST_CONCURRENCY', value: testConcurrency),
                    string(name: 'TEST_PASS_THRESHOLD', value: testPassThreshold),
                    booleanParam(name: 'DELETE_STACK', value: false),
                    booleanParam(name: 'TESTRAIL', value: true),
                    string(name: 'TEST_MILESTONE', value: "${testMilestone}"),
                    string(name: 'TEST_PLAN', value: "${testPlan}"),
                    booleanParam(name: 'FAIL_ON_TESTS', value: true),
            ]

            stage('Configure routing'){
                // Configure OpenStack credentials
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

                    // Add routes for env router
                    openstack = 'set +x; venv/bin/openstack '
                    sh 'virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient'
                    routerName = sh(script: "$openstack stack resource show $stackName mcp_router -f json -c attributes | jq -r '.attributes.router_name'", returnStdout: true).trim()
                    sh(script: "$openstack router set --route destination=10.130.128.0/17,gateway=10.10.0.131 $routerName")
                }
                // Add route to Public net (floating ips):
                salt.cmdRun(saltMaster, 'сfg*', 'route add -net 10.130.128.0/17 gw 10.10.0.131 || true')
            }

            stage('Run tests against initial env') {
                def testModel = "initial_cookied_oc${testContextYaml.default_context.opencontrail_version.replaceAll(/\./, '')}_tempest"
                def testPattern = '^tungsten_tempest_plugin*|^heat_tempest_plugin.tests*|^tempest.api.image*|^tempest_horizon*' +
                        '|^tempest.api.identity*|^tempest.api.network*|^tempest.api.compute*|^tempest.api.volume*|^tempest.scenario*' +
                        '|^tempest.api.object_storage*|^tungsten_tempest_plugin*'
                testBuild = build(job: stackTestJob, parameters: testBuildParams +
                        string(name: 'TEST_MODEL', value: "${testModel}") +
                        string(name: 'TEST_PATTERN', value: "${testPattern}"),
                        wait: true,
                )
                testResult = testBuild.result
            }

            stage('Creating workloads') {
                salt.cmdRun(saltMaster, 'cfg01.*', " salt-cp 'ctl01.*' /tmp/create-workloads.sh /tmp/update-test.yml /root/")
                salt.cmdRun(saltMaster, 'I@nova:controller and *01.*', "cd /root && chmod +x create-workloads.sh && ./create-workloads.sh")
            }

            stage('Prepare environment for update') {


                applyModelChanges(saltMaster, stackName, updateChanges)

                try {
                    println("INFO: Verification of cluster model with update changes ...")
                    salt.cmdRun(saltMaster, 'I@salt:master', "reclass -iy")
                } catch (Exception e) {
                    common.errorMsg('Can not validate current Reclass cluster model with applied update changes')
                    throw e
                }
            }

            stage('Update deployed environment') {

                def pipelineChanges = pipelineChangesMap.get(MCP_VERSION)

                def cicdGerritUrl = salt.getReturnValues(salt.getPillar(saltMaster, "I@gerrit:client", "_param:jenkins_gerrit_url"))

                if (pipelineChanges) {
                    for (repo in pipelineChanges.keySet()) {
                        if (pipelineChanges.get(repo)) {
                            salt.cmdRun(saltMaster, 'I@salt:master', "rm -rf /tmp/pipeline_repo && mkdir /tmp/pipeline_repo && cd /tmp/pipeline_repo && " +
                                    "git init && git remote add origin ${cicdGerritUrl}/${repo} && " +
                                    "GIT_SSH_COMMAND=\"ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no\" " +
                                    "git fetch && git checkout release/${MCP_VERSION}")

                            for (change in pipelineChanges.get(repo)) {
                                def pipelineChange = gerrit.getGerritChange(gerritName, gerritHost, change, gerritCredentials, true)
                                salt.cmdRun(saltMaster, 'I@salt:master', "cd /tmp/pipeline_repo && git fetch ${gerritProtocol}://${gerritHost}/${repo} " +
                                        "${pipelineChange.currentPatchSet.ref} && git cherry-pick FETCH_HEAD && " +
                                        "GIT_SSH_COMMAND=\"ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no\" git push origin release/${MCP_VERSION}")
                            }
                        }
                    }
                }

                // trigger update pipeline against OpenContrail VCP (control & analytic nodes)
                build(job: 'run-job-on-cluster-jenkins', parameters: [
                        string(name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment),
                        string(name: 'OS_PROJECT_NAME', value: projectName),
                        string(name: 'REFSPEC', value: 'stable'),
                        string(name: 'STACK_NAME', value: stackName),
                        string(name: 'JOB_NAME', value: 'deploy-upgrade-opencontrail'),
                        booleanParam(name: 'SWITCH_TO_MASTER', value: false),
                        string(name: 'JOB_PARAMS', value: "STAGE_CONTROLLERS_UPGRADE=true,STAGE_ANALYTICS_UPGRADE=true,STAGE_COMPUTES_UPGRADE=true," +
                                "STAGE_CONTROLLERS_ROLLBACK=false,STAGE_ANALYTICS_ROLLBACK=false,STAGE_COMPUTES_ROLLBACK=false,ASK_CONFIRMATION=false")
                ])
            }

            stage('Run tests against updated env') {
                def testModel = "updated_cookied_oc${testContextYaml.default_context.opencontrail_version.replaceAll(/\./, '')}_tempest"
                def testPattern = '^tungsten_tempest_plugin*|^heat_tempest_plugin.tests*|^tempest.api.image*|^tempest_horizon*' +
                        '|^tempest.api.identity*|^tempest.api.network*|^tempest.api.compute*|^tempest.api.volume*|^tempest.scenario*' +
                        '|^tempest.api.object_storage*|^tungsten_tempest_plugin*'
                testBuild = build(job: stackTestJob, parameters: testBuildParams +
                        string(name: 'TEST_MODEL', value: "${testModel}") +
                        string(name: 'TEST_PATTERN', value: "${testPattern}"),
                        wait: true,
                )
                testResult = testBuild.result
            }

        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            stage ("Delete stack") {
                if (!['scmChange', 'timer'].contains(env.BUILD_USER_ID)) {
                    common.warningMsg("Skip environment deletion because of manual job run")
                } else {
                    if ( (currentBuild.result != 'FAILURE')
                        || (testResult == 'SUCCESS')
                        || (common.validInputParam('DELETE_STACK_ON_FAILURE') && DELETE_STACK_ON_FAILURE.toBoolean() == true) ) {
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
}
