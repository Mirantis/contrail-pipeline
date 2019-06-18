#!groovy
/**
 *
 * contrail build, test, promote pipeline
 *
 * Expected parameters:
 *   ARTIFACTORY_URL        Artifactory server location
 *   ARTIFACTORY_OUT_REPO   local repository name to upload image
 *   ARTIFACTORY_SERVER_NAME  artifactory server to use (configuration of
 *                              artifactory plugin)
 *   DOCKER_REGISTRY_SERVER Docker server to use to push image
 *   DOCKER_REGISTRY_SSL    Docker registry is SSL-enabled if true
 *   APTLY_URL              URL to Aptly instance
 *   APTLY_REPO             Aptly repository to upload packages
 *   OS                     distribution name to build for (debian, ubuntu, etc.)
 *   DIST                   distribution version (jessie, trusty)
 *   ARCH                   comma-separated list of architectures to build
 *   FORCE_BUILD            Force build even when image exists
 *   PROMOTE_ENV            Environment for promotion (default "stable")
 *   KEEP_REPOS             Always keep input repositories even on failure
 *   SOURCE_URL             URL to source code base (component names will be
 *                          appended)
 *   SOURCE_BRANCH          Branch of opencontrail to build
 *   SOURCE_CREDENTIALS     Credentials to use to checkout source
 */

import java.text.SimpleDateFormat

// Load shared libs
def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def gerrit = new com.mirantis.mk.Gerrit()
def artifactory = new com.mirantis.mk.Artifactory()
def aptly = new com.mirantis.mk.Aptly()
def debian = new com.mirantis.mk.Debian()

def gerritProject
try {
  gerritProject = GERRIT_PROJECT
} catch (MissingPropertyException e) {
  gerritProject = ""
}

// Define global variables
def timestamp = common.getDatetime()
def version = SOURCE_BRANCH.replace('R', '') + "~${timestamp}"
def python_version = 'python'
if (SOURCE_BRANCH == "master")
    version = "666~${timestamp}"
String buildName = env.BUILD_NAME
String mcpBuildId = env.MCP_BUILD_ID ?: 'nightly'
String publisherNodeName = env.PUBLISHER_LABEL ?: 'pkg-publisher-v2'
String repoName = env.REPO_NAME ?: env.PPA.tokenize('/').last()
String repoDist = env.DIST
String repoComponent = env.REPO_COMPONENT ?: 'main'
String signKey = env.SIGN_KEY ?: '4C5289EF'
String trsyncPath = 'trsync'
String trsyncProject = env.TRSYNC_PROJECT ?: 'https://review.fuel-infra.org/infra/trsync'
String trsyncRef = env.TRSYNC_REFSPEC ?: 'stable/0.9'
String syncCredentials = env.RSYNC_CREDENTIALS_ID ?: 'mcp-ci-gerrit'
String mirrorList = env.MIRROR_LIST ?: 'jenkins@mirror-us.mcp.mirantis.net jenkins@mirror.mcp.mirantis.net jenkins@mirror.us.mirantis.com'
String dockerImagesGerritRepoUrl = 'ssh://mcp-jenkins@gerrit.mcp.mirantis.net:29418/mk/docker-opencontrail'
def containerizedReleases = ['R4.0', 'R4.1', 'R5.0', 'R5.1']

def publishRetryAttempts = 10


def components = [
    ["contrail-build", "tools/build", SOURCE_BRANCH],
    ["contrail-controller", "controller", SOURCE_BRANCH],
    ["contrail-vrouter", "vrouter", SOURCE_BRANCH],
    ["contrail-third-party", "third_party", SOURCE_BRANCH],
    ["contrail-sandesh", "tools/sandesh", SOURCE_BRANCH],
    ["contrail-packages", "tools/packages", SOURCE_BRANCH],
    ["contrail-nova-vif-driver", "openstack/nova_contrail_vif", SOURCE_BRANCH],
    ["contrail-neutron-plugin", "openstack/neutron_plugin", SOURCE_BRANCH],
    ["contrail-nova-extensions", "openstack/nova_extensions", SOURCE_BRANCH],
    ["contrail-heat", "openstack/contrail-heat", SOURCE_BRANCH],
    ["contrail-ceilometer-plugin", "openstack/ceilometer_plugin", "master"],
    ["contrail-web-storage", "contrail-web-storage", SOURCE_BRANCH],
    ["contrail-web-server-manager", "contrail-web-server-manager", SOURCE_BRANCH],
    ["contrail-web-controller", "contrail-web-controller", SOURCE_BRANCH],
    ["contrail-web-core", "contrail-web-core", SOURCE_BRANCH],
    ["contrail-webui-third-party", "contrail-webui-third-party", SOURCE_BRANCH],
    ["contrail-dpdk", "third_party/dpdk", DPDK_BRANCH]
]

def sourcePackages = [
    "contrail-web-core",
    "contrail-web-controller",
    "contrail",
    "contrail-vrouter-dpdk",
    "neutron-plugin-contrail",
    "ceilometer-plugin-contrail",
    "contrail-heat"
]

// only for R3.x
if (SOURCE_BRANCH ==~ /^R3\..*/) {
    sourcePackages.add("ifmap-server")
}

// only for R3.x and R4.x
if (SOURCE_BRANCH ==~ /^R3\..*|^R4\..*/) {
    components.add(["contrail-generateDS", "tools/generateds", SOURCE_BRANCH])
}

// only for R5.x
if (SOURCE_BRANCH ==~ /^R5\..*/) {
    components.add(["contrail-analytics", "src/contrail-analytics", SOURCE_BRANCH])
    components.add(["contrail-api-client", "src/contrail-api-client", SOURCE_BRANCH])
    components.add(["contrail-common", "src/contrail-common", SOURCE_BRANCH])
}

// R5.1 use python3 for fetching packages, so we need to enforce python version based on OC version
if (SOURCE_BRANCH == 'R5.1') {
    python_version = 'python3'
}

def inRepos = [
    "generic": [
        "in-dockerhub"
    ],
    "debian": [
        "in-debian",
        "in-debian-security",
        "in-tcpcloud-apt"
    ],
    "ubuntu": [
        "in-ubuntu",
        "in-tcpcloud-apt"
    ]
]

def art = null
try {
    art = artifactory.connection(
        ARTIFACTORY_URL,
        DOCKER_REGISTRY_SERVER,
        DOCKER_REGISTRY_SSL ?: true,
        ARTIFACTORY_OUT_REPO,
        "artifactory",
        ARTIFACTORY_SERVER_NAME ?: "default"
    )
} catch (MissingPropertyException e) {
    art = null
}

//SHA of last commit in given repo
def repositoryShas = []
def git_commit = [:]
def properties = [:]
def aptlyRepo = APTLY_REPO
if (gerritProject != "")
    aptlyRepo = "${APTLY_REPO}-exp"


def buildSourcePackageStep(img, pkg, version) {
    return {
        sh("rm -f src/build/packages/${pkg}_* || true")
        img.inside {
            sh("cd src; VERSION='${version}' make -f packages.make source-package-${pkg}")
        }
    }
}

def buildBinaryPackageStep(img, pkg, opts = '-b') {
    return {
        img.inside {
            sh("test -d src/build/${pkg} && rm -rf src/build/${pkg} || true")
            sh("dpkg-source -x src/build/packages/${pkg}_*.dsc src/build/${pkg}")
            sh("cd src/build/${pkg}; sudo apt-get update; dpkg-checkbuilddeps 2>&1|rev|cut -d : -f 1|rev|sed 's,(.*),,g'|xargs sudo apt-get install -y")
            sh("cd src/build/${pkg}; debuild --no-lintian -uc -us ${opts}")
        }
    }
}

List setRepositoryCommitIDs(commits){
    repositoryShas = []
    repositoryShas.add(["CEILOMETER_SHA", commits["contrail-ceilometer-plugin"]])
    repositoryShas.add(["HEAT_SHA", commits["contrail-heat"]])
    repositoryShas.add(["WEB_CONTROLLER_SHA", commits["contrail-controller"]])
    repositoryShas.add(["WEB_CORE_SHA", commits["contrail-web-core"]])
    repositoryShas.add(["CONTROLLER_SHA", commits["contrail-controller"]])
    repositoryShas.add(["NEUTRON_PLUGIN_SHA", commits["contrail-neutron-plugin"]])
    repositoryShas.add(["NOVA_CONTRAIL_SHA", commits["contrail-nova-vif-driver"]])
    repositoryShas.add(["THIRD_PARTY_SHA", commits["contrail-third-party"]])
    repositoryShas.add(["VROUTER_SHA", commits["contrail-vrouter"]])

    //Add resources specific to version 5.0
    if(SOURCE_BRANCH == "R5.0"){
        repositoryShas.add(["ANALYTICS_SHA", commits["contrail-analytics"]])
        repositoryShas.add(["CONTRAIL_API_SHA", commits["contrail-api-client"]])
    }
    return repositoryShas
}

// Populate each pkg with commitID from which pakage will be build
def populatePkgWithCodeSha(pkg, repositoryShas){
    controlFile = "src/tools/packages/debian/${pkg}/debian/control"
    for(sha in repositoryShas){
        sh "sed -i 's/XB-Private-MCP-Code-SHA: ${sha[0]}/XB-Private-MCP-Code-SHA: ${sha[1]}/g' ${controlFile}"
    }
}

node('docker') {
    try{
        checkout scm
        git_commit['contrail-pipeline'] = git.getGitCommit()

        String syncScript = readFile('scripts/syncScript.sh')
        String publisherScript = readFile('scripts/publisherScript.sh')

        stage("cleanup") {
            sh("rm -rf src || true")
        }

        stage("checkout") {
            for (component in components) {
                if ("contrail/${component[0]}" == gerritProject) {
                        gerrit.gerritPatchsetCheckout ([
                            path: "src/" + component[1],
                            credentialsId : SOURCE_CREDENTIALS,
                            depth : 1
                        ])
                    } else {
                        git.checkoutGitRepository(
                            "src/${component[1]}",
                            "${SOURCE_URL}/${component[0]}.git",
                            component[2],
                            SOURCE_CREDENTIALS,
                            true,
                            30,
                            1
                        )
                    }
            }

            for (component in components) {
                if ("contrail/${component[0]}" != gerritProject) {
                    dir("src/${component[1]}") {
                        commit = git.getGitCommit()
                        git_commit[component[0]] = commit
                        properties["git_commit_"+component[0].replace('-', '_')] = commit
                    }
                }
            }

            sh("test -e src/SConstruct || ln -s tools/build/SConstruct src/SConstruct")
            sh("test -e src/packages.make || ln -s tools/packages/packages.make src/packages.make")
            sh("test -d src/build && rm -rf src/build || true")

            repositoryShas = setRepositoryCommitIDs(git_commit)
        }

        if (art) {
            // Check if image of this commit hash isn't already built
            def results = artifactory.findArtifactByProperties(
                art,
                properties,
                art.outRepo
            )
            if (results.size() > 0) {
                println "There are already ${results.size} artefacts with same git commits"
                if (FORCE_BUILD.toBoolean() == false) {
                    common.abortBuild()
                }
            }
        }

        if (art) {
            stage("prepare") {
                // Prepare Artifactory repositories
                out = artifactory.createRepos(art, inRepos['generic']+inRepos[OS], timestamp)
                println "Created input repositories: ${out}"
            }
        }

        try {

            def jenkinsUID = sh (
                script: 'id -u',
                returnStdout: true
            ).trim()
            def imgName = "${OS}-${DIST}-${ARCH}"
            def img
            stage("build-source") {
                if (art) {
                    docker.withRegistry("${art.docker.proto}://in-dockerhub-${timestamp}.${art.docker.base}", "artifactory") {
                        // Hack to set custom docker registry for base image
                        sh "git checkout -f docker/${imgName}.Dockerfile; sed -i -e 's,^FROM ,FROM in-dockerhub-${timestamp}.${art.docker.base}/,g' docker/${imgName}.Dockerfile"
                        img = docker.build(
                            "${imgName}:${timestamp}",
                            [
                                "--build-arg uid=${jenkinsUID}",
                                "--build-arg artifactory_url=${art.url}",
                                "--build-arg timestamp=${timestamp}",
                                "-f docker/${imgName}.Dockerfile",
                                "docker"
                            ].join(' ')
                        )
                    }
                } else {
                    img = docker.build(
                        "${imgName}:${timestamp}",
                        [
                            "--build-arg uid=${jenkinsUID}",
                            "--build-arg timestamp=${timestamp}",
                            "-f docker/${imgName}.Dockerfile",
                            "docker"
                        ].join(' ')
                    )
                }

                img.inside {
                    sh("cd src/third_party; ${python_version} fetch_packages.py")
                    sh("cd src/contrail-webui-third-party; python fetch_packages.py -f packages.xml")
    	        sh("rm -rf src/contrail-web-core/node_modules")
            	sh("mkdir src/contrail-web-core/node_modules")
    	        sh("cp -rf src/contrail-webui-third-party/node_modules/* src/contrail-web-core/node_modules/")
                }

                buildSteps = [:]
                for (pkg in sourcePackages) {
                    populatePkgWithCodeSha(pkg, repositoryShas)
                    buildSteps[pkg] = buildSourcePackageStep(img, pkg, version)
                }
                //parallel buildSteps
                common.serial(buildSteps)

                archiveArtifacts artifacts: "src/build/packages/*.orig.tar.*"
                archiveArtifacts artifacts: "src/build/packages/*.debian.tar.*"
                archiveArtifacts artifacts: "src/build/packages/*.dsc"
                archiveArtifacts artifacts: "src/build/packages/*.changes"
            }

            //for (arch in ARCH.split(',')) {
            stage("build-binary-${ARCH}") {
                buildSteps = [:]
                for (pkg in sourcePackages) {
                    buildSteps[pkg] = buildBinaryPackageStep(img, pkg, '-b')
                }
                parallel buildSteps
                archiveArtifacts artifacts: "src/build/*.deb"
            }
            //}
        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            if (KEEP_REPOS.toBoolean() == false) {
                if (art) {
                    println "Build failed, cleaning up input repositories"
                    out = artifactory.deleteRepos(art, inRepos['generic']+inRepos[OS], timestamp)
                }
                println "Cleaning up docker images"
                sh("docker images | grep -E '[-:\\ ]+${timestamp}[\\.\\ /\$]+' | awk '{print \$3}' | xargs docker rmi -f || true")
            }
            throw e
        }

        if (gerritProject == "") {
            stage("publish packages") {
                timestampDT = new SimpleDateFormat('yyyyMMddHHmmss').parse(timestamp).format('yyyy-MM-dd-HHmmss')
                dir('src/build/') {
                    stash([
                       name: 'builtArtifacts',
                       allowEmpty: false,
                       includes: '*.deb'
                    ])
                }
                dir('src/build/packages') {
                    stash([
                       name: 'builtSources',
                       allowEmpty: false,
                       includes: '*.dsc, *.gz, *.xz, *.changes'
                    ])
                }
                String tmpSuffix = org.apache.commons.lang.RandomStringUtils
                    .random(9, true, true).toLowerCase()
                node(publisherNodeName) {
                    String buildResultDir = "${env.WORKSPACE}/buildresult-${tmpSuffix}"
                    // Checkout trsync
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: trsyncRef],],
                        userRemoteConfigs: [[url: trsyncProject],],
                        extensions: [
                            [
                                $class: 'RelativeTargetDirectory',
                                relativeTargetDir: trsyncPath,
                            ],
                        ],
                    ])
                    dir(buildResultDir){
                        unstash('builtArtifacts')
                        unstash('builtSources')
                    }
                    withEnv([
                        "buildId=${mcpBuildId}",
                        "buildResultDir=${buildResultDir}",
                        "repoName=${repoName}",
                        "repoDist=${repoDist}",
                        "repoComponent=${repoComponent}",
                        "signKey=${signKey}",
                        "TIMESTAMP=${timestampDT}",
                    ]) {
                        sh(publisherScript)
                    }
                    retry(publishRetryAttempts) {
                        withEnv([
                            "buildId=${mcpBuildId}",
                            "repoName=${repoName}",
                            "repoDist=${repoDist}",
                            "remoteRepoPath=/srv/aptly/public",
                            "mirrorList=${mirrorList}",
                            "trsyncDir=${trsyncPath}",
                            "TIMESTAMP=${timestampDT}",
                        ]) {
                            sshagent (credentials: [syncCredentials]) {
                                sh(syncScript)
                            }
                        }
                    }
                }
            }

            stage("upload packages to artifactory") {
                buildSteps = [:]
                debFiles = sh script: "ls src/build/*.deb", returnStdout: true
                for (file in debFiles.tokenize()) {
                    workspace = common.getWorkspace()
                    def fh = new File("${workspace}/${file}".trim())
                    if (art) {
                        buildSteps[fh.name.split('_')[0]] = retry(publishRetryAttempts) {
                            artifactory.uploadPackageStep(
                                art,
                                "src/build/${fh.name}",
                                properties,
                                DIST,
                                'main',
                                timestamp
                            )
                        }
                    }
                }
                parallel buildSteps
            }

            if (SOURCE_BRANCH in containerizedReleases) {
                stage("build docker images") {
                    // trigger OpenContrail Docker image build pipeline
                    build(job: "docker-build-images-opencontrail-${buildName}-${DIST}", parameters: [
                        string(name: 'OC_VERSION', value: buildName),
                        string(name: 'IMAGE_BRANCH', value: SOURCE_BRANCH),
                        string(name: 'IMAGE_GIT_URL', value: dockerImagesGerritRepoUrl),
                        string(name: 'IMAGE_CREDENTIALS_ID', value: SOURCE_CREDENTIALS),
                        string(name: 'REGISTRY_CREDENTIALS_ID', value: 'dockerhub'),
                        string(name: 'SYSTEM_CODENAME', value: DIST)]
                    )
                }
            }
        }

    } catch (Throwable e) {
       // If there was an exception thrown, the build failed
       currentBuild.result = "FAILURE"
       throw e
    } finally {
       common.sendNotification(currentBuild.result,"",["slack"])
       sh("rm -rf src buildresult-*")
    }
}
