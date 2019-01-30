/**
 *
 * contrail nightly pipeline
 *
 * Expected parameters:
 */

import static groovy.json.JsonOutput.toJson

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()

String projectName = 'networking-team'
String gerritCredentialsId = env.GERRIT_CREDENTIALS_ID ?: 'gerrit'
String openstack_credentials_id = env.OPENSTACK_CREDENTIALS_ID ?: 'openstack-devcloud-credentials'
String saltMasterCredentials = env.SALT_MASTER_CREDENTIALS ?: 'salt-qa-credentials'

// test parameters
def stackTestJob = 'ci-opencontrail-tempest-runner'
def testConcurrency = '2'
def testPassThreshold = '96'
def testConf = '/home/rally/rally_reports/tempest_generated.conf'
def testTarget = 'cfg01*'
def testPattern = '^tungsten_tempest_plugin*|smoke'
def openstackEnvironment = 'internal_cloud_v2_us'

//promote parameters
def aptlyPromoteJob = 'aptly-promote-all-nightly-testing'
def mirrorsPromoteJob = 'pkg-promote'

def stackCleanupJob = 'delete-heat-stack-for-mcp-env'


def setContextDefault(contextObject, itemName, itemValue, contextName='default_context'){
    if (!contextObject[contextName].containsKey(itemName)){
      contextObject[contextName][itemName] = itemValue
      common.warningMsg("Setting default value for ${contextName}.${itemName} == ${itemValue}")
    }
}


@SuppressWarnings ('ClosureAsLastMethodParameter')
def merge(Map onto, Map... overrides){
    if (!overrides){
        return onto
    }
    else if (overrides.length == 1) {
        overrides[0]?.each { k, v ->
            if (v in Map && onto[k] in Map){
                merge((Map) onto[k], (Map) v)
            } else {
                onto[k] = v
            }
        }
        return onto
    }
    return overrides.inject(onto, { acc, override -> merge(acc, override ?: [:]) })
}


def getPillarValues(saltMaster, target, pillar) {
    return salt.getReturnValues(salt.getPillar(saltMaster, target, pillar))
}


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
            }

            stage('Getting test context'){
                testContext = readYaml text: TEST_CONTEXT
                testContextName = "oc${env.OPENCONTRAIL_VERSION.replaceAll(/\./, '')}-${testContext.default_context.openstack_version}"
                currentBuild.description = "${currentBuild.description}<br>${testContextName}"


                // stackInstall priority (by descending): testContext, env.STACK_INSTALL, 'core,openstack,contrail'
                //setContextDefault(testContext, 'stack_install', env.STACK_INSTALL ?: 'core,openstack,contrail')

                def testContextYaml = contextsRootPath + '-context.yaml'
                sh "rm -f $testContextYaml"
                writeYaml file: testContextYaml, data: testContext
                contextString = readFile testContextYaml
                common.infoMsg("Using test context")
                common.infoMsg(contextString)

                clusterModelOverrides = [
                "parameters._param.linux_system_repo_opencontrail_url http://mirror.mirantis.com/${OPENCONTRAIL_REPO_VERSION}/opencontrail-${OPENCONTRAIL_VERSION}/ infra/init.yml",
                "parameters._param.linux_system_repo_update_opencontrail_url http://mirror.mirantis.com/update/${OPENCONTRAIL_REPO_VERSION}/opencontrail-${OPENCONTRAIL_VERSION}/ infra/init.yml",
                "parameters._param.linux_system_repo_hotfix_opencontrail_url http://mirror.mirantis.com/hotfix/${OPENCONTRAIL_REPO_VERSION}/opencontrail-${OPENCONTRAIL_VERSION}/ infra/init.yml",
                "parameters._param.opencontrail_image_tag ${OPENCONTRAIL_REPO_VERSION} infra/init.yml",
                ].join('\n')

                common.infoMsg(clusterModelOverrides)
            }

            stage('Deploy the environment'){

                build(job: 'create-mcp-env', parameters: [
                        string(name: 'STACK_INSTALL', value: env.STACK_INSTALL),
                        string(name: 'STACK_NAME', value: stackName),
                        string(name: 'OS_AZ', value: "nova"),
                        string(name: 'OS_PROJECT_NAME', value: projectName),
                        string(name: 'OPENCONTRAIL_VERSION', value: "${OPENCONTRAIL_VERSION}"),
                        textParam(name: 'COOKIECUTTER_EXTRA_CONTEXT', value: "${contextString}"),
                        booleanParam(name: 'DELETE_STACK', value: false),
                        booleanParam(name: 'RUN_TESTS', value: false),
                        booleanParam(name: 'RUN_CVP', value: false),
                        booleanParam(name: 'COLLECT_LOGS', value: true),
                        textParam(name: 'CLUSTER_MODEL_OVERRIDES', value: "${clusterModelOverrides}"),
                        string(name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment),
                        string(name: 'HEAT_TEMPLATES_REFSPEC', value: "refs/changes/31/34331/1"),
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
                        openstack_credentials_id = mirantisClouds.clouds."${openstackEnvironment}".jenkins_credentials_with_user_pass
                    } else {
                        error("There is no configuration for ${openstackEnvironment} underlay OpenStack in clouds.yaml")
                    }
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: openstack_credentials_id,
                        usernameVariable: 'OS_USERNAME', passwordVariable: 'OS_PASSWORD'], ]) {
                            env.OS_USERNAME = OS_USERNAME
                            env.OS_PASSWORD = OS_PASSWORD
                    }
                    env.OS_PROJECT_NAME = projectName
                    env.OS_CLOUD = openstackEnvironment

                    // create openstack env
                    openstack = 'set +x; venv/bin/openstack '
                    sh 'virtualenv venv; venv/bin/pip install python-openstackclient python-heatclient'
                    // get salt master host ip
                    saltMasterHost = sh(script: "$openstack stack show -f value -c outputs ${stackName} | jq -r .[0].output_value", returnStdout: true).trim()
                }

                currentBuild.description = "${currentBuild.description}<br>${saltMasterHost}"
                saltMasterUrl = "http://${saltMasterHost}:6969"
                common.infoMsg("Salt API is accessible via ${saltMasterUrl}")

                saltMaster = salt.connection(saltMasterUrl, saltMasterCredentials)
                opencontrail_version = getPillarValues(saltMaster, 'I@salt:master', '_param:opencontrail_version')
                linux_repo_contrail_component = getPillarValues(saltMaster, 'I@salt:master', '_param:linux_repo_contrail_component')
                linux_system_architecture = getPillarValues(saltMaster, 'I@salt:master', '_param:linux_system_architecture')
                linux_system_codename = getPillarValues(saltMaster, 'I@salt:master', '_param:linux_system_codename')
            }
            // Perform smoke tests to fail early
            stage('Run tests'){
                    build(job: stackTestJob, parameters: [
                            string(name: 'SALT_MASTER_URL', value: saltMasterUrl),
                            string(name: 'TEST_CONF', value: testConf),
                            string(name: 'TEST_TARGET', value: testTarget),
                            string(name: 'TEST_CONCURRENCY', value: testConcurrency),
                            string(name: 'TEST_PATTERN', value: testPattern),
                            string(name: 'TEST_PASS_THRESHOLD', value: testPassThreshold),
                            booleanParam(name: 'DELETE_STACK', value: false),
                            booleanParam(name: 'TESTRAIL', value: true),
                            booleanParam(name: 'FAIL_ON_TESTS', value: true),
                        ],
                        wait: true,
                    )
            }
            // Perform package promotion
            stage('Promote packages'){
                parallel(
                    promote_aptly: {
                        build(job: aptlyPromoteJob, parameters: [
                                string(name: 'COMPONENTS', value: "${linux_repo_contrail_component}"),
                                string(name: 'PACKAGES', value: 'all'),
                            ],
                            wait: true,
                        )
                    },
                    promote_mirrors: {
                        build(job: mirrorsPromoteJob, parameters: [
                                string(name: 'repoName', value: "opencontrail-${opencontrail_version}"),
                                string(name: 'repoDist', value: "${linux_system_codename}"),
                                string(name: 'promoteFrom', value: "nightly"),
                                string(name: 'promoteTo', value: "testing"),
                                string(name: 'packagesToPromote', value: "*"),
                            ],
                            wait: true,
                        )
                    },
                )
            }
        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            if ( (currentBuild.result != 'FAILURE')
                || (common.validInputParam('DELETE_STACK_ON_FAILURE') && DELETE_STACK_ON_FAILURE.toBoolean() == true) ) {
                    stage ("Delete stack") {
                        build(job: stackCleanupJob, parameters: [
                                string(name: 'STACK_NAME', value: stackName),
                                string(name: 'OS_PROJECT_NAME', value: projectName),
                                string(name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment),
                                string(name: 'OPENSTACK_API_CREDENTIALS', value: openstack_credentials_id),
                            ],
                            propagate: false,
                            wait: true,
                        )
                    }
            }
        }
    }
}
