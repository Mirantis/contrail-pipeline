#!/bin/bash -ex

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

# Initialize vars
repoDist=${repoDist:-"${target}"}
buildId=${buildId:-'nightly'}

storageDir="${storageDir:-${HOME}/repoStorage/${buildId}/${repoName}-${repoDist}}"

# Prevent race condition on repo update
job_lock "${storageDir}.lock" wait

stableDir=${storageDir}/stable
TIMESTAMP=${TIMESTAMP:-$(date +%Y-%m-%d-%H%M%S)}

# Sync new stable state to mirrors
trsyncVenv=${trsyncDir}/.venv
if [ ! -d "${trsyncVenv}" ]; then
    virtualenv "${trsyncVenv}"
    source "${trsyncVenv}/bin/activate"
    cd "${trsyncDir}"
    pip install .
else
    source "${trsyncVenv}/bin/activate"
fi

for mirror in ${mirrorList}; do
    trsync push \
        "${stableDir}/public" \
        "${buildId}-${repoName}-${repoDist}" \
        --dest "${mirror}:${remoteRepoPath}/" \
        --snapshots-dir ".snapshots" \
        --init-directory-structure \
        --timestamp "${TIMESTAMP}" \
        --extra '\\-e "ssh -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"'

    trsync symlink \
        --dest "${mirror}:${remoteRepoPath}/" \
        --target "../../.snapshots/${buildId}-${repoName}-${repoDist}-${TIMESTAMP}" \
        --symlinks "${buildId}/${repoName}/${repoDist}" \
        --update \
        --extra '\\-e "ssh -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"'
done

job_lock "${storageDir}.lock" unset
