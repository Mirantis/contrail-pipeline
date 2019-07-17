/**
 *
 * Pipeline for tests execution on predeployed Openstack.
 * Pipeline stages:
 *  - Launch of tests on deployed environment. Currently
 *    supports only Tempest tests, support of Stepler
 *    will be added in future.
 *  - Archiving of tests results to Jenkins master
 *  - Processing results stage - triggers build of job
 *    responsible for results check and upload to testrail
 *
 * Expected parameters:
 *   LOCAL_TEMPEST_IMAGE          Path to docker image tar archive
 *   SALT_MASTER_URL              URL of Salt master
 *   SALT_MASTER_CREDENTIALS      Credentials to the Salt API
 *   TEST_IMAGE                   Docker image to run tempest
 *   TEST_CONF                    Tempest configuration file path inside container
 *                                In case of runtest formula usage:
 *                                    TEST_CONF should be align to runtest:tempest:cfg_dir and runtest:tempest:cfg_name pillars and container mounts
 *                                    Example: tempest config is generated into /root/rally_reports/tempest_generated.conf by runtest state.
 *                                             Means /home/rally/rally_reports/tempest_generated.conf on docker tempest system.
 *                                In case of predefined tempest config usage:
 *                                    TEST_CONF should be a path to predefined tempest config inside container
 *   TEST_DOCKER_INSTALL          Install docker
 *   TEST_TARGET                  Salt target to run tempest on e.g. gtw*
 *   TEST_PATTERN                 Tempest tests pattern
 *   TEST_CONCURRENCY             How much tempest threads to run
 *   TESTRAIL                     Whether upload results to testrail or not
 *   TEST_MILESTONE               Product version for tests
 *   TEST_MODEL                   Salt model used in environment
 *   OPENSTACK_VERSION            Version of Openstack being tested
 *   PROC_RESULTS_JOB             Name of job for test results processing
 *   FAIL_ON_TESTS                Whether to fail build on tests failures or not
 *   TEST_PASS_THRESHOLD          Persent of passed tests to consider build successful
 *   SLAVE_NODE                   Label or node name where the job will be run
 *   USE_PEPPER                   Whether to use pepper for connection to salt master
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()
salt = new com.mirantis.mk.Salt()
test = new com.mirantis.mk.Test()
python = new com.mirantis.mk.Python()

/**
 * Execute stepler tests
 *
 * @param dockerImageLink   Docker image link with stepler
 * @param target            Host to run tests
 * @param pattern           If not false, will run only tests matched the pattern
 * @param logDir            Directory to store stepler reports
 * @param sourceFile        Path to the keystonerc file in the container
 * @param set               Predefined set for tests
 * @param skipList          A skip.list's file name
 * @param localKeystone     Path to the keystonerc file in the local host
 * @param localLogDir       Path to local destination folder for logs
 */
def runSteplerTests(master, dockerImageLink, target, testPattern='', logDir='/home/stepler/tests_reports/',
                    set='', sourceFile='/home/stepler/keystonercv3', localLogDir='/root/rally_reports/',
                    skipList='skip_list_mcp_ocata.yaml', localKeystone='/root/keystonercv3') {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${localLogDir}"])
    def docker_run = "-e SOURCE_FILE=${sourceFile} " +
                     "-e LOG_DIR=${logDir} " +
                     "-e TESTS_PATTERN='${testPattern}' " +
                     "-e SKIP_LIST=${skipList} " +
                     "-e SET=${set} " +
                     "-v ${localKeystone}:${sourceFile} " +
                     "-v ${localLogDir}:${logDir} " +
                     '-v /etc/ssl/certs/:/etc/ssl/certs/ ' +
                     '-v /etc/hosts:/etc/hosts ' +
                     "${dockerImageLink} > docker-stepler.log"

    salt.cmdRun(master, "${target}", "docker run --rm --net=host ${docker_run}")
}

def installExtraFormula(saltMaster, formula) {
    def result
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'pkg.install', "salt-formula-${formula}")
    salt.checkResult(result)
    result = salt.runSaltProcessStep(saltMaster, 'cfg01*', 'file.symlink', ["/usr/share/salt-formulas/reclass/service/${formula}", "/srv/salt/reclass/classes/service/${formula}"])
    salt.checkResult(result)
}

def getPillarValues(saltMaster, target, pillar) {
    return salt.getReturnValues(salt.getPillar(saltMaster, target, pillar))
}

/**
 * Configure the node where runtest state is going to be executed
 *
 * @param nodename          nodename is going to be configured
 * @param testTarget        Salt target to run tempest on e.g. gtw*
 * @param tempestCfgDir     directory to tempest configuration file
 * @param logDir            directory to put tempest log files
 **/

def configureRuntestNode(saltMaster, nodeName, testTarget, tempestCfgDir, logDir, concurrency=2, mcpVersion='nightly') {
    // Set up test_target parameter on node level
    def fullnodename = salt.getMinions(saltMaster, nodeName).get(0)
    def saltMasterExpression = 'I@salt:master'
    def saltMasterTarget = ['expression': saltMasterExpression, 'type': 'compound']
    def extraFormulas = ['runtest', 'artifactory']
    def result

    common.infoMsg("Setting up mandatory runtest parameters in ${fullnodename} on node level")

//    salt.runSaltProcessStep(saltMaster, fullnodename, 'pkg.install', ["salt-formula-runtest", "salt-formula-artifactory"])
    for (extraFormula in extraFormulas) {
        installExtraFormula(saltMaster, extraFormula)
    }

    def classes_to_add = ['service.runtest.tempest']
    def params_to_update = ['tempest_test_target': testTarget,
                            'runtest_tempest_cfg_dir': tempestCfgDir,
                            'runtest_tempest_log_file': "${logDir}/tempest.log",
                            'runtest_tempest_concurrency': concurrency,
                            'glance_image_cirros_location': 'https://artifactory.mcp.mirantis.net/artifactory/test-images/cirros-0.3.5-x86_64-disk.img',
                            'glance_image_fedora_location': 'https://artifactory.mcp.mirantis.net/artifactory/test-images/Fedora-Cloud-Base-27-1.6.x86_64.qcow2',]

    if (salt.testTarget(saltMaster, 'I@nova:controller:barbican:enabled:true')){
        classes_to_add.add('service.runtest.tempest.barbican')
    }

    if (salt.testTarget(saltMaster, 'I@manila:api:enabled:true')){
        classes_to_add.add('service.runtest.tempest.services.manila.glance')
    }

    if (mcpVersion == '2018.4.0'){
        classes_to_add.add('system.neutron.client.service.contrail_public')
        classes_to_add.add('system.keystone.client.core')
        def cluster_name = salt.getReturnValues(salt.getPillar(saltMaster, 'I@salt:master', '_param:cluster_name'))
        // Some parameters aren't supported in runtest formula in 2018.4.0, so we should add them through custom class
        classes_to_add.add("cluster.${cluster_name}.openstack.scale-ci-patch.runtest")
        params_to_update['openstack_public_neutron_subnet_gateway'] = '10.13.0.1'
        params_to_update['openstack_public_neutron_subnet_cidr'] = '10.13.0.0/16'
        params_to_update['openstack_public_neutron_subnet_allocation_start'] = '10.13.128.0'
        params_to_update['openstack_public_neutron_subnet_allocation_end'] = '10.13.255.254'
    } else {
        // Fix for PROD-25148
        classes_to_add.add('service.runtest.tempest.tempest_net')
    }

    result = salt.runSaltCommand(saltMaster, 'local', saltMasterTarget, 'reclass.node_update', null, null, ['name': "${fullnodename}", 'classes': classes_to_add, 'parameters': params_to_update])
    salt.checkResult(result)

    common.infoMsg('Perform full refresh for all nodes')
    salt.fullRefresh(saltMaster, '*')

    salt.enforceState(saltMaster, 'I@salt:master', ['salt.minion'], true, false, null, false, 300, 2, true, [], 60)
    // run keystone client to create admin_identity
    salt.enforceState(saltMaster, 'I@salt:master', ['keystone.client'])

    common.infoMsg('Perform client states to create new resources')

    if (salt.testTarget(saltMaster, 'I@neutron:client:enabled')) {
        if (mcpVersion == '2018.4.0') {
            salt.enforceState(saltMaster, 'I@neutron:client:enabled and cfg01*', 'neutron.client')
        } else {
            salt.enforceState(saltMaster, 'I@neutron:client:enabled', 'neutron.client')
        }
    }
    // configure route target fot public network in case of contrail env
    salt.cmdRun(saltMaster, "ntw01*", 'salt-call contrail.virtual_network_create public ' +
            'route_target_list=\'["target:64512:10000"]\'')

    if (salt.testTarget(saltMaster, 'I@glance:client:enabled')) {
        salt.enforceState(saltMaster, 'I@glance:client:enabled', 'glance.client')
    }
    if (salt.testTarget(saltMaster, 'I@nova:client:enabled')) {
        salt.enforceState(saltMaster, 'I@nova:client:enabled', 'nova.client')
    }
}

 /**
 * Execute tempest tests
 *
 * @param dockerImageLink       Docker image link with rally and tempest
 * @param target                Host to run tests
 * @param args                  Arguments that we pass in tempest
 * @param logDir                Directory to store tempest reports in container
 * @param localLogDir           Path to local destination folder for logs on host machine
 * @param tempestConfLocalPath  Path to tempest config on host machine
 */

def runTempestTestsNew(master, target, dockerImageLink, args = '', localLogDir='/root/test/', logDir='/root/tempest/',
                       tempestConfLocalPath='/root/test/tempest_generated.conf') {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${localLogDir}"])
    salt.cmdRun(master, "${target}", 'docker run --net=host ' +
                                    "-e ARGS=${args} " +
                                    "-v ${tempestConfLocalPath}:/etc/tempest/tempest.conf " +
                                    "-v ${localLogDir}:${logDir} " +
                                    '-v /etc/ssl/certs/:/etc/ssl/certs/ ' +
                                    '-v /tmp/:/tmp/ ' +
                                    '-v /etc/hosts:/etc/hosts ' +
                                    "--rm ${dockerImageLink} " +
                                    '/bin/bash -c "run-tempest" ')
}

/** Archive Tempest results in Artifacts
 *
 * @param master              Salt connection.
 * @param target              Target node to install docker pkg
 * @param reportsDir          Source directory to archive
 */
def archiveTestArtifacts(master, target, reportsDir='/root/test', outputFile='test.tar') {
    def salt = new com.mirantis.mk.Salt()

    def artifacts_dir = '_artifacts/'

    salt.cmdRun(master, target, "tar --exclude='env' -cf /root/${outputFile} -C ${reportsDir} .")
    sh "mkdir -p ${artifacts_dir}"

    encoded = salt.cmdRun(master, target, "cat /root/${outputFile}", true, null, false)['return'][0].values()[0].replaceAll('Salt command execution success', '')
    writeFile file: "${artifacts_dir}${outputFile}", text: encoded

    // collect artifacts
    archiveArtifacts artifacts: "${artifacts_dir}${outputFile}"
}

// Define global variables
def saltMaster
def slave_node = 'python'

if (common.validInputParam('SLAVE_NODE')) {
    slave_node = SLAVE_NODE
}

def test_image
if (common.validInputParam('TEST_IMAGE')){
    test_image = TEST_IMAGE
}

timeout(time: 6, unit: 'HOURS') {
    node(slave_node) {

        def test_type = 'tempest'
        if (common.validInputParam('TEST_TYPE')){
            test_type = TEST_TYPE
        }

        def log_dir = '/root/tempest/'

        def reports_dir = '/root/test/'

        def date = sh(script: 'date +%Y-%m-%d', returnStdout: true).trim()
        def test_log_dir = "/var/log/${test_type}"
        def testrail = false
        def args = ''
        def test_pattern = '^tungsten_tempest_plugin*|smoke'
        def test_milestone = ''
        def test_model = ''
        def venv = "${env.WORKSPACE}/venv"
        def test_concurrency = '0'
        def use_pepper = true
        def test_dir = 'test'
        def openstack_version
        if (common.validInputParam('USE_PEPPER')){
            use_pepper = USE_PEPPER.toBoolean()
        }

        try {

            if (common.validInputParam('TESTRAIL') && TESTRAIL.toBoolean()) {
                testrail = true
                if (common.validInputParam('TEST_MILESTONE') && common.validInputParam('TEST_MODEL')) {
                    test_milestone = TEST_MILESTONE
                    test_model = TEST_MODEL
                    test_plan = TEST_PLAN
                } else {
                    error('WHEN UPLOADING RESULTS TO TESTRAIL TEST_MILESTONE, TEST_MODEL AND TEST_PLAN MUST BE SET')
                }
            }

            if (common.validInputParam('TEST_CONCURRENCY')) {
                test_concurrency = TEST_CONCURRENCY
            }

            stage ('Connect to salt master') {
                if (use_pepper) {
                    python.setupPepperVirtualenv(venv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS, true)
                    saltMaster = venv
                } else {
                    saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
                }
            }

            def mcpVersion = salt.getReturnValues(salt.getPillar(saltMaster, 'I@salt:master', '_param:mcp_version')) ?: salt.getReturnValues(salt.getPillar(saltMaster, 'I@salt:master', '_param:apt_mk_version'))

            configureRuntestNode(saltMaster, 'cfg01*', TEST_TARGET, reports_dir, log_dir, test_concurrency.toInteger(), mcpVersion)

            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.remove', ["${reports_dir}"])
            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.mkdir', ["${reports_dir}"])

            if (common.checkContains('TEST_DOCKER_INSTALL', 'true')) {
                test.install_docker(saltMaster, TEST_TARGET)
            }

            if (common.validInputParam('LOCAL_TEMPEST_IMAGE')) {
                salt.cmdRun(saltMaster, TEST_TARGET, "docker load --input ${LOCAL_TEMPEST_IMAGE}", true, null, false)
            }


            openstack_version = getPillarValues(saltMaster, 'I@salt:master', '_param:openstack_version')

            // TODO: implement stepler testing from this pipeline
            stage('Run OpenStack tests') {

                if (test_image.indexOf(':') == -1 && !common.validInputParam('LOCAL_TEMPEST_IMAGE')){
                    test_image = test_image + ':' + openstack_version
                }

                if (test_type == 'stepler') {
                    test_dir = test_type
                    runSteplerTests(saltMaster, test_image,
                        TEST_TARGET,
                        TEST_PATTERN,
                        '/home/stepler/tests_reports/',
                        '',
                        '/home/stepler/keystonercv3',
                        reports_dir)
                } else {

                    if (common.validInputParam('TEST_PATTERN')) {
                        test_pattern = TEST_PATTERN
                    }

                    test_dir = test_pattern

                    args = "\'-r ${test_pattern} -w ${test_concurrency}\'"

                    // Skip this state in case of contrail env
                    /*if (salt.testTarget(saltMaster, 'I@runtest:salttest')) {
                        salt.enforceState(saltMaster, 'I@runtest:salttest', ['runtest.salttest'], true)
                    }*/

                    if (salt.testTarget(saltMaster, 'I@runtest:tempest and cfg01*')) {
                        salt.enforceState(saltMaster, 'I@runtest:tempest and cfg01*', ['runtest'], true)
                        if (salt.testTarget(saltMaster, 'I@nova:controller:barbican:enabled:true')){
                            common.infoMsg('Barbican integration is detected, preparing environment for Barbican tests')

                            salt.enforceState(saltMaster, 'I@runtest:tempest and cfg01*', ['barbican.client'], true)
                            salt.enforceState(saltMaster, 'I@runtest:tempest and cfg01*', ['runtest.test_accounts'], true)
                            salt.enforceState(saltMaster, 'I@runtest:tempest and cfg01*', ['runtest.barbican_sign_image'], true)
                        }
                    } else {
                        common.warningMsg('Cannot generate tempest config by runtest salt')
                    }

                    def tempestCfgDir = salt.getReturnValues(salt.getPillar(saltMaster, 'I@runtest:tempest and cfg01*', '_param:runtest_tempest_cfg_dir'))
                    def tempestCfgName = salt.getReturnValues(salt.getPillar(saltMaster, 'I@runtest:tempest and cfg01*', '_param:runtest_tempest_cfg_name'))
                    def tempestCfgPath = tempestCfgDir + tempestCfgName

                    if (mcpVersion == '2018.4.0'){
                        def addTempestConf = 'contrail = True\\n' +
                                '\\n' +
                                '[patrole]\\n' +
                                'custom_policy_files = /etc/opencontrail/policy.json\\n' +
                                'enable_rbac = False\\n' +
                                '\\n' +
                                '[sdn]\\n' +
                                'service_name = opencontrail\\n' +
                                'endpoint_type = internal\\n' +
                                'catalog_type = contrail\\n' +
                                'contrail_version = 3.2'
                        salt.cmdRun(saltMaster, 'I@runtest:tempest and cfg01*', "echo '${addTempestConf}' >> ${tempestCfgPath}")
                        salt.cmdRun(saltMaster, 'I@runtest:tempest and cfg01*', "sed -i 's/\\[auth\\]/\\[auth]\\ntempest_roles = admin/g' ${tempestCfgPath}")
                    }

                    runTempestTestsNew(saltMaster, TEST_TARGET, test_image, args)

                    def tempest_stdout
                    tempest_stdout = salt.cmdRun(saltMaster, TEST_TARGET, "cat ${reports_dir}/report_*.log", true, null, false)['return'][0].values()[0].replaceAll('Salt command execution success', '')
                    common.infoMsg('Short test report:')
                    common.infoMsg(tempest_stdout)
                }
            }

            stage('Archive Test artifacts') {
                archiveTestArtifacts(saltMaster, TEST_TARGET, reports_dir)
            }

            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.mkdir', ["${test_log_dir}"])
            salt.runSaltProcessStep(saltMaster, TEST_TARGET, 'file.move', ["${reports_dir}", "${test_log_dir}/${test_dir}-${date}"])
            salt.fullRefresh(saltMaster, '*')

            stage('Processing results') {
                build(job: PROC_RESULTS_JOB, parameters: [
                    [$class: 'StringParameterValue', name: 'TARGET_JOB', value: "${env.JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'TARGET_BUILD_NUMBER', value: "${env.BUILD_NUMBER}"],
                    [$class: 'BooleanParameterValue', name: 'TESTRAIL', value: testrail.toBoolean()],
                    [$class: 'StringParameterValue', name: 'TEST_MILESTONE', value: test_milestone],
                    [$class: 'StringParameterValue', name: 'TEST_MODEL', value: test_model],
                    [$class: 'StringParameterValue', name: 'TEST_PLAN', value: test_plan],
                    [$class: 'StringParameterValue', name: 'OPENSTACK_VERSION', value: openstack_version],
                    [$class: 'StringParameterValue', name: 'TEST_DATE', value: date],
                    [$class: 'StringParameterValue', name: 'TEST_PASS_THRESHOLD', value: TEST_PASS_THRESHOLD],
                    [$class: 'BooleanParameterValue', name: 'FAIL_ON_TESTS', value: FAIL_ON_TESTS.toBoolean()]
                ])
            }

        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            throw e
        }
    }
}
