#!/bin/bash -xue
set -o pipefail

source /root/keystonercv3

export projectName=${1:-test-workloads}

project_id=$(echo -e \
    "$(openstack project show -f shell ${projectName} \
     || openstack project create -f shell ${projectName})" \
    | awk -F '[="]' '/^id=/ {print $(NF-1)}')

role_id=$(openstack role assignment list -f json --project ${projectName} --user ${OS_USERNAME} \
    | awk -F '[:",]' '/Role/ {print $(NF-2)}')
openstack role show -f json ${role_id} | egrep -q 'name.*admin' \
    || openstack role add --project ${projectName} --user ${OS_USERNAME} admin

flavor_id=$(echo -e \
    "$(openstack flavor show -f shell ${projectName}-flavor \
     || openstack flavor create -f shell --ram 256 --vcpus 1 --disk 1 ${projectName}-flavor)" \
    | awk -F '[="]' '/^id=/ {print $(NF-1)}')

[ -f cirros-0.4.0-x86_64-disk.img ] \
    || curl -sSfL -O https://artifactory.mcp.mirantis.net/artifactory/test-images/cirros-0.4.0-x86_64-disk.img

image_id=$(echo -e \
    "$(openstack image show -f shell ${projectName}-image \
     || openstack image create -f shell --file cirros-0.4.0-x86_64-disk.img --public ${projectName}-image)" \
    | awk -F '[="]' '/^id=/ {print $(NF-1)}')

pub_net_id=$(echo -e \
    "$(openstack network show -f shell ${projectName}-pub-net \
     || openstack network create -f shell --share --external ${projectName}-pub-net)" \
    | awk -F '[="]' '/^id=/ {print $(NF-1)}')

openstack keypair show ${projectName}-keypair \
    && openstack keypair delete ${projectName}-keypair
openstack keypair create ${projectName}-keypair | tee ${projectName}-keypair.private

export OS_TENANT_NAME=${projectName}

openstack stack create -t ${0%/*}/update-test.yml \
    --parameter project_id=${project_id} \
    --parameter pub_net_id=${pub_net_id} \
    --parameter flavor_id=${flavor_id} \
    --parameter image_id=${image_id} \
     test-workloads
