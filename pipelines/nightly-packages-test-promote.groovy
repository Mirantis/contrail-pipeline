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
mkOpenstack = new com.mirantis.mk.Openstack()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()
mirror = new com.mirantis.mk.Mirror()
mcpCommon = new com.mirantis.mcp.Common()

String projectName
String contrailRepoName
String openstackCredentialsId = env.OPENSTACK_CREDENTIALS_ID ?: 'openstack-devcloud-credentials'
String saltMasterCredentials = env.SALT_MASTER_CREDENTIALS ?: 'salt-qa-credentials'

String mirrorList = env.MIRROR_LIST ?: 'jenkins@mirror-us.mcp.mirantis.net jenkins@mirror-eu.mcp.mirantis.net jenkins@mirror.us.mirantis.com'

// gerrit variables
gerritCredentials = env.CREDENTIALS_ID ?: 'gerrit'
gerritName = env.GERRIT_NAME ?: 'mcp-jenkins'
gerritHost = env.GERRIT_HOST ?: 'gerrit.mcp.mirantis.com'
gerritProtocol = 'https'

// test parameters
def stackTestJob = 'ci-contrail-tempest-runner'
def testConcurrency = '2'
def testPassThreshold = '95'
def testConf = '/home/rally/rally_reports/tempest_generated.conf'
def testTarget = 'cfg01*'
def openstackEnvironment = 'internal_cloud_v2_us'
def testResult

//promote parameters
def mirrorsPromoteJob = 'pkg-promote-from-snapshot'
def saltFormulasPromoteJob = 'pkg-promote'
def imagesPromoteJob = 'docker-images-mirror'
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
                    if (!['scmChange', 'timer'].contains(env.BUILD_USER_ID)) {
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

                if (!(OPENSTACK_ENABLED.toBoolean() ^ KUBERNETES_ENABLED.toBoolean())) {
                    error("Only one of the parameters (OPENSTACK_ENABLED, KUBERNETES_ENABLED) should be set to 'True'")
                }

                // opencontrail_version priority (by descending): testContext, env.OPENCONTRAIL_VERSION, 'core,openstack,contrail'
                setContextDefault(testContext, 'opencontrail_version', env.OPENCONTRAIL_VERSION ?: '4.1')
                setContextDefault(testContext, 'mcp_version', env.MCP_VERSION ?: 'testing')
                setContextDefault(testContext, 'openstack_enabled', (env.OPENSTACK_ENABLED == 'true' ? 'True' : 'False') ?: 'True')
                setContextDefault(testContext, 'kubernetes_enabled', (env.KUBERNETES_ENABLED == 'true' ? 'True' : 'False') ?: 'False')
                if (OPENSTACK_ENABLED.toBoolean() == true) {
                    setContextDefault(testContext, 'openstack_version', env.OPENSTACK_VERSION ?: 'queens')
                } else if (KUBERNETES_ENABLED.toBoolean() == true) {
                    setContextDefault(testContext, 'kubernetes_network_opencontrail_enabled', 'True')
                    setContextDefault(testContext, 'kubernetes_network_calico_enabled', 'False')
                    setContextDefault(testContext, 'openstack_cluster_size', 'k8s_contrail')
                }

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
                sourceSnapshotMeta = getSnapshotMeta("http://mirror.mirantis.com/.snapshots/${OPENCONTRAIL_REPO_VERSION}-opencontrail-${OPENCONTRAIL_VERSION}-${distribution}-latest")
                common.warningMsg("sourceSnapshotMeta = ${sourceSnapshotMeta}")

                common.warningMsg("Getting repo packages list")
                def packagesUrl = "${sourceSnapshotMeta.repoUrl}/dists/${distribution}/${repoComponent}/source/Sources"
                def packages = sh(script: "curl -sSfL ${packagesUrl}", returnStdout: true)
                packageToPromoteList = packages.split('\n').findAll { it =~ /^Package:(?:(?!ifmap-server).)+$/ }.collect { it.split(': ')[-1]}
                common.warningMsg("packageToPromoteList = ${packageToPromoteList}")

                build(job: 'pkg-create-repo-snapshot', parameters: [
                        string(name: 'repoUrl', value: "${sourceSnapshotMeta['repoUrl']} ${distribution} ${repoComponent}"),
                        string(name: 'repoName', value: "${contrailRepoName}"),
                        string(name: 'repoOrigin', value: "Mirantis"),
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

                deploy_build = build(job: 'create-mcp-env', parameters: [
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
                        string(name: 'REFSPEC', value: ""),
                        string(name: 'HEAT_TEMPLATES_REFSPEC', value: ""),
                        textParam(name: 'HEAT_STACK_CONTEXT', value: ""),
                        textParam(name: 'EXTRA_REPOS', value: "${extraReposString}"),
                        string(name: 'COOKIECUTTER_TEMPLATE_CONTEXT_FILE', value: "${COOKIECUTTER_TEMPLATE_CONTEXT_FILE}"),
                    ],
                    wait: true,
                )
            }

            stage('Get environment information'){

                saltMasterHost = "${deploy_build.description.tokenize(' ')[1]}"

                currentBuild.description = "${currentBuild.description}<br>${saltMasterHost}"
                saltMasterUrl = "http://${saltMasterHost}:6969"
                common.infoMsg("Salt API is accessible via ${saltMasterUrl}")

                saltMaster = salt.connection(saltMasterUrl, saltMasterCredentials)
                opencontrail_version = getPillarValues(saltMaster, 'I@salt:master', '_param:opencontrail_version')
                linux_repo_contrail_component = getPillarValues(saltMaster, 'I@salt:master', '_param:linux_repo_contrail_component')
                linux_system_architecture = getPillarValues(saltMaster, 'I@salt:master', '_param:linux_system_architecture')
                linux_system_codename = getPillarValues(saltMaster, 'I@salt:master', '_param:linux_system_codename')
            }

            // skip for k8s deployment
            if (OPENSTACK_ENABLED.toBoolean() == true) {
                stage('Opencontrail controllers health check') {
                    python.setupPepperVirtualenv(pepperEnv, saltMasterUrl, saltMasterCredentials)
                    try {
                        salt.enforceState(pepperEnv, 'I@opencontrail:control or I@opencontrail:collector', 'opencontrail.upgrade.verify', true, true)
                    } catch (Exception er) {
                        common.errorMsg("OpenContrail controllers health check stage found issues with services. Please take a look at the logs above.")
                        throw er
                    }
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
                    string(name: 'TESTRAIL_PLAN', value: "${testPlan}"),
                    booleanParam(name: 'FAIL_ON_TESTS', value: true),
            ]

            if (OPENSTACK_ENABLED.toBoolean() == true) {
                // Temporary workaround for PROD-31402, PROD-25619
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
                    salt.cmdRun(saltMaster, 'cfg*', 'route add -net 10.130.128.0/17 gw 10.10.0.131 || true')
                }
                stage('Run tests (tempest)'){
                    def testModel = "oc${env.OPENCONTRAIL_VERSION.replaceAll(/\./, '')}_${env.MCP_VERSION}_tempest"
                    def testPattern = '^heat_tempest_plugin.tests*|^tempest.api.image*|^tempest_horizon*' +
                            '|^tempest.api.identity*|^tempest.api.network*|^tempest.api.compute*|^tempest.api.volume*|^tempest.scenario*' +
                            '|^tempest.api.object_storage*|^tungsten_tempest_plugin*'
                    def tr_suite = "[${testMilestone}_" + env.OPENSTACK_VERSION.toUpperCase() + "]Tempest"
                    def tr_conf = "{'Contrail':'OC ${env.OPENCONTRAIL_VERSION}'}"
                    testBuild = build(job: stackTestJob, parameters: testBuildParams +
                            string(name: 'TESTRAIL_MILESTONE', value: "${testMilestone}") +
                            string(name: 'TESTRAIL_RUN', value: "${testModel}") +
                            string(name: 'TESTRAIL_SUITE', value: "${tr_suite}") +
                            string(name: 'TESTRAIL_CONFIGURATION', value: "${tr_conf}") +
                            string(name: 'TEST_PATTERN', value: "${testPattern}"),
                        wait: true,
                        propagate: false,
                    )
                    if ((testBuild.result == 'SUCCESS') || (testBuild.result == 'UNSTABLE')) {
                        testResult = 'SUCCESS'
                    } else {
                        error('Test result is failed.')
                    }
                }
            } else if (KUBERNETES_ENABLED.toBoolean() == true) {

                stage ('Run tests (conformance)') {
                    build (job: "mcp_k8s_run_conformance", parameters: [
                            string(name: 'STACK_NAME', value: stackName),
                            string(name: 'OPENSTACK_API_PROJECT', value: projectName),
                            string(name: 'K8S_API_SERVER', value: 'http://127.0.0.1:8080'),
                            string(name: 'K8S_MASTER_SALT_TARGET', value: 'I@kubernetes:master'),
                            string(name: 'K8S_CONFORMANCE_IMAGE', value: ''),
                            string(name: 'AUTODETECT', value: 'True'),
                        ]
                    )
                }

            }

            // Perform promotion
            stage('Promote artifacts'){
                if (env.PROMOTE_PACKAGES.toBoolean() == true) {
                    if (OPENCONTRAIL_REPO_VERSION == 'nightly') {

                        saltFormulasToPromote = 'salt-formula-opencontrail'

                        OC_VERSION = "oc${OPENCONTRAIL_VERSION.replaceAll(/\./, '')}"
                        imageList = """
                            docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}/opencontrail-agent:SUBS_SOURCE_IMAGE_TAG docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}
                            docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}/opencontrail-analytics:SUBS_SOURCE_IMAGE_TAG docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}
                            docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}/opencontrail-analyticsdb:SUBS_SOURCE_IMAGE_TAG docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}
                            docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}/opencontrail-base:SUBS_SOURCE_IMAGE_TAG docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}
                            docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}/opencontrail-controller:SUBS_SOURCE_IMAGE_TAG docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}
                            docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}/opencontrail-kube-manager:SUBS_SOURCE_IMAGE_TAG docker-prod-local.docker.mirantis.net/opencontrail-${OC_VERSION}
                        """.trim().replaceAll(/(?m)^ +/, '')

                        contrailPromotionBuild = build(job: mirrorsPromoteJob, parameters: [
                                string(name: 'repoUrl', value: "${sourceSnapshotMeta.repoUrl} ${linux_system_codename} ${repoComponent}"),
                                string(name: 'repoName', value: "opencontrail-${OPENCONTRAIL_VERSION}"),
                                string(name: 'repoDist', value: "${linux_system_codename}"),
                                string(name: 'packagesToPromote', value: packageToPromoteList.join(' ')),
                            ],
                            propagate: false,
                            wait: true,
                        )
                        if (contrailPromotionBuild.result != 'SUCCESS'){
                            error('Failed to promote opencontrail packages from nightly to testing repo.')
                        }

                        imagePromotionBuild = build(job: imagesPromoteJob, parameters: [
                                textParam(name: 'IMAGE_LIST', value: "${imageList}"),
                                string(name: 'SOURCE_IMAGE_TAG', value: "${contrailRepoSnapshotMeta.timestamp.replaceAll('-', '')}"),
                                string(name: 'IMAGE_TAG', value: "testing"),
                                string(name: 'REGISTRY_URL', value: "https://docker-prod-local.docker.mirantis.net"),
                                string(name: 'TARGET_REGISTRY_CREDENTIALS_ID', value: "artifactory"),
                            ],
                            propagate: false,
                            wait: true,
                        )
                        if (imagePromotionBuild.result != 'SUCCESS'){
                            error('Failed to promote opencontrail docker images from nightly to testing repo.')
                        }

                        if(env.MCP_VERSION == '2019.2.0') {
                            saltFormulaPromotionBuild = build(job: saltFormulasPromoteJob, parameters: [
                                    string(name: 'repoName', value: "salt-formulas"),
                                    string(name: 'repoDist', value: "${linux_system_codename}"),
                                    string(name: 'promoteFrom', value: "nightly"),
                                    string(name: 'promoteTo', value: "testing"),
                                    string(name: 'packagesToPromote', value: "${saltFormulasToPromote}"),
                                    string(name: 'mirrorList', value: "${mirrorList}"),
                                ],
                                propagate: false,
                                wait: true,
                            )
                            if (saltFormulaPromotionBuild.result != 'SUCCESS'){
                                error('Failed to promote salt-formula packages from nightly to testing repo.')
                            }
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
            wrap([$class: 'BuildUser']) {
                if (!['scmChange', 'timer'].contains(env.BUILD_USER_ID)) {
                    common.warningMsg("Skip environment deletion because of manual job run")
                } else {
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
                    } else {
                        common.warningMsg("Skip environment deletion because something went wrong. See logs")
                    }
                }
            }
        }
    }
}
