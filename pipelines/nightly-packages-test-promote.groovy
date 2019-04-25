/**
 *
 * contrail nightly pipeline
 *
 * Expected parameters:
 */

import static groovy.json.JsonOutput.toJson
import java.text.SimpleDateFormat


common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
openstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()
mirror = new com.mirantis.mk.Mirror()
mcpCommon = new com.mirantis.mcp.Common()

String projectName
String contrailRepoName
String openstackCredentialsId = env.OPENSTACK_CREDENTIALS_ID ?: 'openstack-devcloud-credentials'
String saltMasterCredentials = env.SALT_MASTER_CREDENTIALS ?: 'salt-qa-credentials'

// gerrit variables
gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'
gerritName = env.GERRIT_NAME ?: 'mcp-jenkins'
gerritHost = env.GERRIT_HOST ?: 'gerrit.mcp.mirantis.com'
gerritProtocol = 'https'

// test parameters
def stackTestJob = 'ci-contrail-tempest-runner'
def testConcurrency = '2'
def testPassThreshold = '90'
def testConf = '/home/rally/rally_reports/tempest_generated.conf'
def testTarget = 'cfg01*'
def openstackEnvironment = 'internal_cloud_v2_us'
def testResult

//promote parameters
def mirrorsPromoteJob = 'pkg-promote-from-snapshot'
def pepperEnv = "pepperEnv"
def stackCleanupJob = 'delete-heat-stack-for-mcp-env'

def arch = 'amd64'
def distribution = 'xenial'
def repoComponent = 'main'
def packageToPromoteList = []

// env parameters
def extraReposString = ''

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

def getSnapshotMeta(repoUrl) {

    common.warningMsg("Getting snapshot meta")
    def snapshotHistory = sh(script: "curl -sSfL ${repoUrl.replaceAll(/\/+$/, '')}.target.txt", returnStdout: true)
    def snapshotRelativePath = snapshotHistory.split("\n")[0]

    def meta = [:]
    meta.repoUrl = "${repoUrl}/../${snapshotRelativePath}"
    meta.repoName = snapshotRelativePath.tokenize('/').last().trim()
    meta.timestamp = ("${meta.repoUrl}" =~ /\d{4}-\d{2}-\d{2}-\d{6}$/).collect { match -> return match }[0]

    return meta
}


timeout(time: 8, unit: 'HOURS') {
    node ('python'){
        try {
            stage('Prepare'){
                wrap([$class: 'BuildUser']) {
                    if (env.BUILD_USER_ID) {
                        stackName = "${env.BUILD_USER_ID}-${JOB_NAME}-${BUILD_NUMBER}"
                        projectName = 'networking-team'
                        contrailRepoName = "${env.BUILD_USER_ID}-${JOB_NAME}"
                    } else {
                        stackName = "jenkins-${JOB_NAME}-${BUILD_NUMBER}"
                        projectName = 'networking-ci-team'
                        contrailRepoName = "jenkins-${JOB_NAME}"
                    }
                }
                currentBuild.description = "${stackName}"
                contextsRootPath = WORKSPACE + '/' + JOB_NAME
            }

            stage('Getting test context'){
                testContext = readYaml text: TEST_CONTEXT

                // opencontrail_version priority (by descending): testContext, env.OPENCONTRAIL_VERSION, 'core,openstack,contrail'
                setContextDefault(testContext, 'opencontrail_version', env.OPENCONTRAIL_VERSION ?: '4.1')
                setContextDefault(testContext, 'mcp_version', env.MCP_VERSION ?: 'testing')
                setContextDefault(testContext, 'openstack_enabled', (env.OPENSTACK_ENABLED == 'true' ? 'True' : 'False') ?: 'True')
                setContextDefault(testContext, 'openstack_version', env.OPENSTACK_VERSION ?: 'queens')

                testContextName = "oc${testContext.default_context.opencontrail_version.replaceAll(/\./, '')}-${testContext.default_context.openstack_version}"
                currentBuild.description = "${currentBuild.description}<br>${testContextName}"

                def testContextYaml = contextsRootPath + '-context.yaml'
                sh "rm -f $testContextYaml"
                writeYaml file: testContextYaml, data: testContext
                contextString = readFile testContextYaml
                common.infoMsg("Using test context")
                common.infoMsg(contextString)

                if (env.EXTRA_REPOS){
                    extraRepos = readYaml text: EXTRA_REPOS
                    extraReposString = mcpCommon.dumpYAML(extraRepos)
                }
            }

            stage("Creating snapshot from ${OPENCONTRAIL_REPO_VERSION} repo"){
                common.warningMsg("OPENCONTRAIL_REPO_VERSION = ${OPENCONTRAIL_REPO_VERSION}")
                sourceSnapshotMeta = getSnapshotMeta("http://mirror.mirantis.com/${OPENCONTRAIL_REPO_VERSION}/opencontrail-${OPENCONTRAIL_VERSION}/${distribution}")
                common.warningMsg("sourceSnapshotMeta = ${sourceSnapshotMeta}")

                common.warningMsg("Getting repo packages list")
                def packagesUrl = "${sourceSnapshotMeta.repoUrl}/dists/${distribution}/${repoComponent}/source/Sources"
                def packages = sh(script: "curl -sSfL ${packagesUrl}", returnStdout: true)
                packageToPromoteList = packages.split('\n').findAll { it =~ /^Package:(?:(?!ifmap-server).)+$/ }.collect { it.split(': ')[-1]}
                common.warningMsg("packageToPromoteList = ${packageToPromoteList}")

                build(job: 'pkg-create-repo-snapshot', parameters: [
                        string(name: 'repoUrl', value: "${sourceSnapshotMeta['repoUrl']} ${distribution} ${repoComponent}"),
                        string(name: 'repoName', value: "${contrailRepoName}"),
                        string(name: 'repoOrigin', value: "${contrailRepoName}"),
                        string(name: 'remoteRepoPath', value: "custom-snapshots/${contrailRepoName}"),
                        string(name: 'repoSymlink', value: "${distribution}"),
                        string(name: 'packagesList', value: "${packageToPromoteList.join(' ')}"),
                        string(name: 'timestamp', value: "${sourceSnapshotMeta.timestamp}"),
                    ],
                    wait: true,
                )
            }

            def contrailRepoSnapshotHost = 'eu.mirror.infra.mirantis.net'
            def contrailRepoSnapshotUrl = "http://${contrailRepoSnapshotHost}/custom-snapshots/${contrailRepoName}/${distribution}"
            def contrailRepoSnapshotMeta = getSnapshotMeta(contrailRepoSnapshotUrl)
            common.infoMsg("contrailRepoSnapshotMeta = ${contrailRepoSnapshotMeta}")

            // TODO: fix this hack because of various directory structure at snapshots
            contrailRepoSnapshotMeta.repoUrl = "http://${contrailRepoSnapshotHost}/custom-snapshots/${contrailRepoName}"

            clusterModelOverrides = [
            "parameters._param.linux_system_repo_opencontrail_url ${contrailRepoSnapshotMeta.repoUrl} infra/init.yml",
            //"parameters._param.linux_system_repo_update_opencontrail_url ${contrailRepoSnapshotMeta.repoUrl} infra/init.yml",
            //"parameters._param.linux_system_repo_hotfix_opencontrail_url ${contrailRepoSnapshotMeta.repoUrl} infra/init.yml",
            "parameters._param.opencontrail_docker_image_tag ${contrailRepoSnapshotMeta.timestamp.replaceAll('-', '')} infra/init.yml",
            ].join('\n')

            common.infoMsg(clusterModelOverrides)


            stage('Deploy the environment'){

                heatTemplatesChange = gerrit.getGerritChange(gerritName, gerritHost, '34331', gerritCredentials, true)
                build(job: 'create-mcp-env', parameters: [
                        string(name: 'STACK_NAME', value: stackName),
                        string(name: 'OS_AZ', value: "nova"),
                        string(name: 'OS_PROJECT_NAME', value: projectName),
                        textParam(name: 'COOKIECUTTER_EXTRA_CONTEXT', value: "${contextString}"),
                        booleanParam(name: 'DELETE_STACK', value: false),
                        booleanParam(name: 'RUN_TESTS', value: false),
                        booleanParam(name: 'RUN_CVP', value: false),
                        booleanParam(name: 'COLLECT_LOGS', value: true),
                        textParam(name: 'CLUSTER_MODEL_OVERRIDES', value: "${clusterModelOverrides}"),
                        string(name: 'OPENSTACK_ENVIRONMENT', value: openstackEnvironment),
                        string(name: 'HEAT_TEMPLATES_REFSPEC', value: "${heatTemplatesChange.currentPatchSet.ref}"),
                        textParam(name: 'HEAT_STACK_CONTEXT', value: ""),
                        textParam(name: 'EXTRA_REPOS', value: "${extraReposString}"),
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
            stage('Opencontrail controllers health check') {
                python.setupPepperVirtualenv(pepperEnv, saltMasterUrl, saltMasterCredentials)
                try {
                    salt.enforceState(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'opencontrail.upgrade.verify', true, true)
                } catch (Exception er) {
                    common.errorMsg("OpenContrail controllers health check stage found issues with services. Please take a look at the logs above.")
                    throw er
                }
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
            // Temporary workaround for PROD-24982
            stage('Run tests (tempest)'){
                def testModel = "oc${env.OPENCONTRAIL_VERSION.replaceAll(/\./, '')}_${env.MCP_VERSION}_tempest"
                def testPattern = '^heat_tempest_plugin.tests*|^tempest.api.image*|^tempest_horizon*' +
                        '|^tempest.api.identity*|^tempest.api.network*|^tempest.api.compute*|^tempest.api.volume*|^tempest.scenario*' +
                        '|^tempest.api.object_storage*'
                testBuild = build(job: stackTestJob, parameters: testBuildParams +
                        string(name: 'TEST_MODEL', value: "${testModel}") +
                        string(name: 'TEST_PATTERN', value: "${testPattern}"),
                    wait: true,
                )
                testResult = testBuild.result
            }
            stage('Run tests (tungsten)'){
                def testModel = "oc${env.OPENCONTRAIL_VERSION.replaceAll(/\./, '')}_${env.MCP_VERSION}_tungsten"
                def testPattern = '^tungsten_tempest_plugin*'
                testBuild = build(job: stackTestJob, parameters: testBuildParams +
                        string(name: 'TEST_MODEL', value: "${testModel}") +
                        string(name: 'TEST_PATTERN', value: "${testPattern}"),
                        wait: true,
                )
                testResult = testBuild.result
            }

            // Perform package promotion
            stage('Promote packages'){
                if (env.PROMOTE_PACKAGES.toBoolean() == true) {
                    if (OPENCONTRAIL_REPO_VERSION == 'nightly') {
                        promotionBuild = build(job: mirrorsPromoteJob, parameters: [
                                string(name: 'repoUrl', value: "${sourceSnapshotMeta.repoUrl} ${linux_system_codename} ${repoComponent}"),
                                string(name: 'repoName', value: "opencontrail-${OPENCONTRAIL_VERSION}"),
                                string(name: 'repoDist', value: "${linux_system_codename}"),
                                string(name: 'packagesToPromote', value: packageToPromoteList.join(' ')),
                            ],
                            propagate: false,
                            wait: true,
                        )
                        if (promotionBuild.result != 'SUCCESS'){
                            error('Failed to promote snapshot from nightly to testing repo.')
                        }
                    } else {
                        common.warningMsg("Promotion skipped because OPENCONTRAIL_REPO_VERSION==${OPENCONTRAIL_REPO_VERSION}, is not 'nightly'")
                    }
                } else {
                    common.warningMsg("Promotion skipped because PROMOTE_PACKAGES==${env.PROMOTE_PACKAGES.toBoolean()}")
                }

            }
        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            throw e
        } finally {
            stage ("Delete stack") {
                if (env.BUILD_USER_ID) {
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
