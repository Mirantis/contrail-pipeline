#!groovy
/**
 *
 * contrail build, test, promote pipeline
 *
 * Expected parameters:
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def dockerLib = new com.mirantis.mk.Docker()

def server = Artifactory.server('mcp-ci')
def artTools = new com.mirantis.mcp.MCPArtifactory()
def artifactoryUrl = server.getUrl()
def pubRegistry = env.PUB_REGISTRY ?:'docker-dev-local.docker.mirantis.net/tungsten'
def floatingPubTag = "5.1-dev"
def dockerDevRepo = "${pubRegistry.tokenize('.')[0]}"
def dockerDevRegistry = "${pubRegistry.tokenize('/')[0]}"

imageNameSpace = pubRegistry.replaceFirst("${dockerDevRegistry}/", '')
publishRetryAttempts = 10

// Artifactory related paramters
String artifactoryRepo            = env.ARTIFACTORY_REPO ?: 'binary-dev-local'
String artifactoryNamespace       = env.ARTIFACTORY_NAMESPACE ?: "tungsten"
def artifactoryBuildInfo          = Artifactory.newBuildInfo()
server.credentialsId              = env.ARTIFACTORY_CREDENTIALS_ID ?: 'artifactory'

String artifactoryUploadPath      = "${artifactoryRepo}/${artifactoryNamespace}"
String artifactoryUploadPattern   = env.ARTIFACTORY_UPLOAD_PATTERN ?: '*'


def gerritChangeNum
try {
    gerritChangeNum = GERRIT_CHANGE_NUMBER
} catch (MissingPropertyException e) {
    gerritChangeNum = ""
}


// use docker slaves excluding jsl09.mcp.mirantis.net host for debug
node('docker && !jsl09.mcp.mirantis.net') {
    try{

        def timestamp = new Date().format("yyyyMMddHHmmss", TimeZone.getTimeZone('UTC'))
        println ("timestamp ${timestamp}")

        withEnv([
            "timestamp=${timestamp}",
            "AUTOBUILD=${AUTOBUILD}",
            "EXTERNAL_REPOS=${WORKSPACE}/src",
            "SRC_ROOT=${WORKSPACE}/contrail",
            "CANONICAL_HOSTNAME=${CANONICAL_HOSTNAME}",
            "IMAGE=${IMAGE}",
            "DEVENVTAG=${DEVENVTAG}",
            "SRCVER=5.1.${timestamp}",
        ]) {

            stage("prepare") {
              sh '''
                  sudo rm -rf *
                  git clone https://gerrit.mcp.mirantis.com/tungsten/tf-dev-env -b mcp/R5.1
                  cd tf-dev-env
                  if [ "${GERRIT_PROJECT##*/}" = "tf-dev-env" ]; then
                      git fetch $(git remote -v | awk '/^origin.*fetch/ {print $2}') ${GERRIT_REFSPEC:?GERRIT_REFSPEC is empty} > /dev/null \
                        && git checkout FETCH_HEAD > /dev/null
                  fi
                  echo "Using tf-dev-env version"
                  git log --decorate -n1
                  ./build.sh
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory clean
                  docker exec tf-developer-sandbox pip install future
              '''
            }

            writeFile file: "tf-dev-env/buildpipeline.env",
                text: "GERRIT_PROJECT=${env.GERRIT_PROJECT}\n" \
                    + "GERRIT_REFSPEC=${env.GERRIT_REFSPEC}\n"
            stage("sync") {
              sh '''
                if [ "${GERRIT_PROJECT##*/}" = "contrail-vnc" ]; then
                    VNC_REVISION=${GERRIT_REFSPEC}
                fi
                docker exec tf-developer-sandbox repo init --no-clone-bundle -q -u https://gerrit.mcp.mirantis.com/tungsten/contrail-vnc -b ${VNC_REVISION:-${VNC_BRANCH}}
                docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory sync
              '''
            }

            stage("checkout") {
              sh '''
                  if [ -n "${GERRIT_PROJECT}" ]; then
                      if [ "${GERRIT_PROJECT##*/}" != "tf-dev-env" ] && [ "${GERRIT_PROJECT##*/}" != "contrail-vnc" ] && [ "${GERRIT_PROJECT##*/}" != "contrail-container-builder" ]; then
                          docker exec tf-developer-sandbox ./tf-dev-env/checkout.sh ${GERRIT_PROJECT##*/} ${GERRIT_CHANGE_NUMBER}/${GERRIT_PATCHSET_NUMBER}
                          if echo ${GERRIT_CHANGE_COMMIT_MESSAGE} | base64 -d | egrep -i '^depends-on:'; then
                              DEP_URL_LIST=$(echo ${GERRIT_CHANGE_COMMIT_MESSAGE} | base64 -d | egrep -i '^depends-on:' | sed -r 's|/+$||g' | egrep -o '[^ ]+$')
                              for DEP_URL in ${DEP_URL_LIST}; do
                                  DEP_PROJECT_URL="${DEP_URL%/+/*}"
                                  DEP_PROJECT="${DEP_PROJECT_URL##*/}"
                                  DEP_CHANGE_ID="${DEP_URL##*/}"
                                  docker exec tf-developer-sandbox ./tf-dev-env/checkout.sh ${DEP_PROJECT} ${DEP_CHANGE_ID}
                              done
                          else
                              echo "There are no depends-on"
                          fi
                      else
                          echo "Skipping checkout because GERRIT_PROJECT is ${GERRIT_PROJECT}."
                      fi
                  else
                      echo "Skipping checkout because GERRIT_PROJECT does not specified"
                  fi
              '''
            }

            stage("fetch packages") {
              // TODO: downstream third parties
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory fetch_packages'
            }

            stage("setup") {
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory setup'
            }

            stage("dep") {
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory dep'
            }

            stage("info") {
              sh 'docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory info'
            }

            stage("rpm") {
              sh '''
                  #TODO: implement versioning
                  # Following Environment variables can be used for controlling Makefile behavior:
                  # DEBUGINFO = TRUE/FALSE - build debuginfo packages (default: TRUE)
                  # TOPDIR - control where packages will be built (default: SB_TOP)
                  # SCONSOPT = debug/production - select optimization level for scons (default: production)
                  # SRCVER - specify source code version (default from controller/src/base/version.info)
                  # KVERS - kernel version to build against (default: installed version of kernel-devel)
                  # BUILDTAG - additional tag for versioning (default: date +%m%d%Y%H%M)
                  # SKUTAG - OpenStack SKU (default: ocata)

                  #export VERSION="5.1.${timestamp}"
                  #//  def version = SOURCE_BRANCH.replace('R', '') + "~${timestamp}"
                  #//  def python_version = 'python'
                  #//  if (SOURCE_BRANCH == "master")
                  #//      version = "666~${timestamp}"
                  #export BUILDTAG=
                  docker exec tf-developer-sandbox make DEBUGINFO=TRUE -C ./tf-dev-env --no-print-directory rpm
                  #TODO: parse errors and archive logs. possible with junit
              '''
            }

            List listContainers
            List listDeployers

            stage("containers") {
                // TODO: update tf-dev-env/scripts/prepare-containers and prepare-deployers for
                // checkout to change request if needed
                // TODO: implement versioning
              sh '''
                  echo "INFO: make create-repo prepare-containers prepare-deployers   $(date)"
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory -j 3 create-repo prepare-containers prepare-deployers 
              '''
              listContainers = sh(script: "docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory list-containers", returnStdout: true).trim().tokenize()
              listDeployers = sh(script: "docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory list-deployers", returnStdout: true).trim().tokenize()

              sh '''
                  echo "INFO: make container-general-base $(date)"
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory container-general-base

                  echo "INFO: make containers-only deployers-only   $(date)"
                  docker exec tf-developer-sandbox make -C ./tf-dev-env --no-print-directory -j 2 containers-only deployers-only || EXIT_CODE=$?
                  #TODO: parse errors and archive logs. possible with junit
              '''
            }

            stage("contrail-api-client wheel") {
                  sh "docker exec -w /root/contrail/src/contrail-api-client/ tf-developer-sandbox pip install -U wheel setuptools"
                  sh "docker exec -w /root/contrail/src/contrail-api-client/base/ tf-developer-sandbox sed -i -r 's/(.*)/\\1.${timestamp}/' version.info"
                  sh "docker exec -w /root/contrail/src/contrail-api-client/ tf-developer-sandbox scons"
                  sh "docker exec -w /root/contrail/src/contrail-api-client/build/debug/api-lib/ tf-developer-sandbox python setup.py bdist_wheel --universal"
                  sh "docker exec -w /root/contrail/src/contrail-api-client/build/debug/api-lib/dist/ tf-developer-sandbox ls -l"
                  sh "ls -l contrail/src/contrail-api-client/build/debug/api-lib/dist"
                  String uploadSpec = """{
                    "files": [
                      {
                        "pattern": "contrail/src/contrail-api-client/build/debug/api-lib/dist/*.whl",
                        "target": "${artifactoryUploadPath}/contrail-api-client/"
                      }
                    ]
                  }"""
                  artTools.uploadBinariesToArtifactory(server, artifactoryBuildInfo, uploadSpec, true)
            }


            List brokenList
            List containerLogList
            String containerBuilderDir = "src/${CANONICAL_HOSTNAME}/tungsten/contrail-container-builder"

            stage("Upload images") {
                dir("tf-dev-env") {
                    List imageList = (listContainers + listDeployers).collect {
                        it.replaceAll(/^container/, 'contrail').replaceAll(/^deployer/, 'contrail').replaceAll('_' , '-')
                    }
                    common.infoMsg("imageList = ${imageList}")
                    docker.withRegistry("http://${dockerDevRegistry}/", 'artifactory') {
                        List commonEnv = readFile("common.env").split('\n')
                        common.infoMsg(commonEnv)
                        withEnv(commonEnv) {
                            dir ("../" + containerBuilderDir + "/containers/") {
                                containerLogList = findFiles (glob: "build-*.log")
                            }
                            currentBuild.description = "[<a href=\"https://${dockerDevRegistry}/artifactory/webapp/#/artifacts/browse/tree/General/${dockerDevRepo}/${imageNameSpace}\">tree</a>]"
                            def descJson = '{"packagePayload":[{"id":"dockerV2Tag","values":["' + "${SRCVER}" + '"]}],"selectedPackageType":{"id":"dockerV2","icon":"docker","displayName":"Docker"}}'
                            currentBuild.description = "<a href=\"https://${dockerDevRegistry}/artifactory/webapp/#/search/package/${descJson.bytes.encodeBase64().toString()}\">${SRCVER}</a> ${currentBuild.description}"
                            brokenList = containerLogList.collect {
                                it.getName().replaceFirst(/^build-/, '').replaceAll(/.log$/ , '')
                            }
                            common.infoMsg("brokenList = ${brokenList}")
                            imageList.each { image ->
                                if (! (image in brokenList)) {
                                    def localImage = "${CONTRAIL_REGISTRY}/${image}:${CONTRAIL_VERSION}"
                                    def publishTags = ["${SRCVER}"]
                                    // Add floating tag only in builds for merged code
                                    if (gerritChangeNum == "") {
                                        publishTags.add("${floatingPubTag}")
                                    }
                                    publishTags.each { pTag ->
                                        def publicImage = "${dockerDevRegistry}/${imageNameSpace}/${image}:${pTag}"
                                        sh "docker tag ${localImage} ${publicImage}"
                                        retry(publishRetryAttempts) {
                                            artTools.uploadImageToArtifactory(
                                                server,
                                                dockerDevRegistry,
                                                "${imageNameSpace}/${image}",
                                                "${pTag}",
                                                dockerDevRepo)
                                            sh "docker rmi ${publicImage}"
                                        }
                                        sh "echo '${publicImage}' >> ${WORKSPACE}/image-list.txt"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage("Process results") {
                archiveArtifacts artifacts: 'image-list.txt'
                if(!brokenList.isEmpty()) {
                    common.errorMsg("Failed to build some containers:\n${brokenList}\nSee log files at artifacts")
                    archiveArtifacts artifacts: containerBuilderDir + '/containers/build-*.log'
                    currentBuild.result = "FAILURE"
                }
            }

        }

    } catch (Throwable e) {
       // If there was an exception thrown, the build failed
       currentBuild.result = "FAILURE"
       throw e
    } finally {
       //common.sendNotification(currentBuild.result,"",["slack"])
       //sh("rm -rf src buildresult-*")
    }
}
