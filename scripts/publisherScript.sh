#!/bin/bash -ex

errExit() {
    >&2 echo "$@"
    exit 1
}

cleanUpOnFailure() {
    trap EXIT
    rm -rf "${stagingDir}"
    exit 1
}

job_lock() {
    [ -z "$1" ] && errExit "Lock file is not specified"
    local LOCKFILE=$1
    shift
    local fd=1000
    eval "exec $fd>>$LOCKFILE"
    case $1 in
        "set")
            flock -x -n $fd \
                || errExit "Process already running. Lockfile: $LOCKFILE"
            ;;
        "unset")
            flock -u $fd
            rm -f "$LOCKFILE"
            ;;
        "wait")
            local TIMEOUT=${2:-3600}
            echo "Waiting of concurrent process (lockfile: $LOCKFILE, timeout = $TIMEOUT seconds) ..."
            if flock -x -w "$TIMEOUT" $fd ; then
                echo DONE
            else
                errExit "Timeout error (lockfile: $LOCKFILE)"
            fi
            ;;
    esac
}

trap cleanUpOnFailure EXIT


# Initialize vars
aptlyRepoName=${repoName}-${repoDist}-${repoComponent}
aptlyArchs=${aptlyArchs:-"amd64,source"}
storageDir="${storageDir:-${HOME}/repoStorage/${buildId}/${repoName}-${repoDist}}"
stableDir=${storageDir}/stable
TIMESTAMP=$(date +%Y-%m-%d-%H%M%S)
stagingDir=${storageDir}/${TIMESTAMP}
aptlyConfigFile="${storageDir}/aptly.config"
signOpt="-skip-signing"
[ "${#signKey}" -gt 5 ] && signOpt="-gpg-key=${signKey}"

mkdir -p "${storageDir}"

# Prevent race condition on repo update
job_lock "${storageDir}.lock" wait

# Workaround for weak digest algorithm
# Fixed in aptly v0.9.7+
if dpkg --compare-versions "$(aptly version | awk '{print $NF}')" le 0.9.6 ; then
    gpgHome=${GNUPGHOME:-"${HOME}/.gnupg"}
    gpgConf=${gpgHome}/gpg.conf
    # shellcheck disable=SC2174
    [ ! -d "${gpgHome}" ] && mkdir -p -m 700 "${gpgHome}"
    grep -Eq "^digest-algo " "${gpgConf}" &>/dev/null \
        || echo 'digest-algo sha256' >> "${gpgConf}"
    sed -e 's|^digest-algo .*$|digest-algo sha256|' \
        -i "${gpgConf}"
fi

# Renew aptly config
cat > "${aptlyConfigFile}" <<EOF
{
  "rootDir": ".",
  "downloadConcurrency": 4,
  "downloadSpeedLimit": 0,
  "architectures": [],
  "dependencyFollowSuggests": false,
  "dependencyFollowRecommends": false,
  "dependencyFollowAllVariants": false,
  "dependencyFollowSource": false,
  "gpgDisableSign": false,
  "gpgDisableVerify": false,
  "downloadSourcePackages": false,
  "ppaDistributorID": "ubuntu",
  "ppaCodename": "",
  "skipContentsPublishing": false,
  "S3PublishEndpoints": {},
  "SwiftPublishEndpoints": {}
}
EOF

# Clone current repo state
dbDir="${stableDir}/db"
poolDir="${stableDir}/pool"
pubDir="${stableDir}/public"

mkdir -p "${stagingDir}"
[ -d "${dbDir}" ] && cp -R "${dbDir}" "${stagingDir}/"
[ -d "${poolDir}" ] && cp -Rl "${poolDir}" "${stagingDir}/"
[ -d "${pubDir}" ] && cp -Rl "${pubDir}" "${stagingDir}/"

# Get new source name
srcName=$(grep "^Source: " "${buildResultDir}"/*.dsc | awk '{print $NF}')

# Publish new packages
pushd "${stagingDir}" &>/dev/null

aptly --config="${aptlyConfigFile}" repo create "${aptlyRepoName}" 2>/dev/null  || :

# Remove old versions
aptly --config="${aptlyConfigFile}" repo remove "${aptlyRepoName}" \
    "\$Source (${srcName})" || :
aptly --config="${aptlyConfigFile}" repo remove "${aptlyRepoName}" \
    "Name (${srcName}), \$Architecture (source)" || :
aptly --config="${aptlyConfigFile}" db cleanup

aptly --config="${aptlyConfigFile}" repo add "${aptlyRepoName}" "${buildResultDir}"

# Republish repo
aptly --config="${aptlyConfigFile}" publish drop "${repoDist}" 2>/dev/null || :
aptly --config="${aptlyConfigFile}" publish repo \
    --architectures "${aptlyArchs}" \
    --distribution "${repoDist}" \
    --component "${repoComponent}" \
    --origin "Mirantis" \
    --label "${repoName}" \
    "${signOpt}" \
    "${aptlyRepoName}"

# Update pubkey file
pub_key_file="public/archive-${repoName}.key"
if [ "${#signKey}" -gt 5 ] ; then
    gpg -o "${pub_key_file}.tmp" --armor --export "$signKey"
    if diff -q "${pub_key_file}" "${pub_key_file}.tmp" &>/dev/null ; then
        rm "${pub_key_file}.tmp"
    else
        mv "${pub_key_file}.tmp" "${pub_key_file}"
    fi
fi

popd &>/dev/null

# Set new stable dir
[ -L "${stableDir}" ] && oldStableDir=$(readlink -f "${stableDir}") && rm "${stableDir}"
ln -s "${TIMESTAMP}" "${stableDir}"

trap EXIT

[ -d "${oldStableDir}" ] && rm -rf "${oldStableDir}"

job_lock "${storageDir}.lock" unset
