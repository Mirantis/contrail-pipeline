FROM ubuntu:trusty

ARG artifactory_url
ARG extra_repo_url="deb http://apt.mirantis.com/trusty/ nightly extra"
ARG extra_repo_key_url="http://apt.mirantis.com/public.gpg"
ARG timestamp
ARG uid=1000

ENV ARTIFACTORY_URL $artifactory_url
ENV EXTRA_REPO_URL $extra_repo_url
ENV EXTRA_REPO_KEY_URL $extra_repo_key_url
ENV TIMESTAMP $timestamp
ENV JENKINS_UID $uid

## Configure APT mirror
RUN [ "x${ARTIFACTORY_URL}" != "x" ] && ( \
        apt-get update \
        && DEBIAN_FRONTEND=noninteractive apt-get install -y apt-transport-https wget \
        && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* \
        && echo | openssl s_client -showcerts -connect `echo $ARTIFACTORY_URL | cut -d '/' -f 3` 2>/dev/null | awk '/BEGIN CERTIFICATE/{print;flag=1;next}/END CERTIFICATE/{print;flag=0}flag' >/usr/local/share/ca-certificates/artifactory.crt \
        && update-ca-certificates \
        && echo "deb ${ARTIFACTORY_URL}/in-ubuntu-${TIMESTAMP} trusty main restricted universe" >/etc/apt/sources.list \
        && echo "deb ${ARTIFACTORY_URL}/in-ubuntu-${TIMESTAMP} trusty-updates main restricted universe" >>/etc/apt/sources.list \
        && wget -O - ${ARTIFACTORY_URL}/in-mirantis-mk-${TIMESTAMP}/public.gpg | apt-key add - \
        && echo "deb ${ARTIFACTORY_URL}/in-mirantis-mk-${TIMESTAMP}/trusty/ nightly extra" >>/etc/apt/sources.list \
    ) || ( \
        apt-get update \
        && DEBIAN_FRONTEND=noninteractive apt-get install -y apt-transport-https curl \
        && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* \
        && [ -z "${EXTRA_REPO_KEY_URL}" ] && [ "${EXTRA_REPO_KEY_URL}" != "null" ] || curl --insecure -ss -f "${EXTRA_REPO_KEY_URL}" | apt-key add - \
        && [ -z "${EXTRA_REPO_URL}" ] && [ "${EXTRA_REPO_URL}" != "null" ] || echo "${EXTRA_REPO_URL}" >>/etc/apt/sources.list \
    )

# Install requirements for Contrail build
RUN apt-get update && apt-get install -y \
        linux-headers-generic \
        build-essential \
        equivs \
        git \
        vim-nox \
        wget \
        scons \
        libxml2-utils \
        python-lxml \
        autoconf \
        automake \
        libtool \
        patch \
        unzip \
        pkg-config \
        javahelper \
        ant \
        python-setuptools \
        python-nose \
        sudo \
        nodejs-legacy \
        npm \
        devscripts \
        lsof \
        psmisc \
        eatmydata \
    && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

RUN useradd -s /bin/bash --uid $JENKINS_UID -m jenkins
RUN echo "ALL    ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers
RUN npm config set strict-ssl false -g

ENV LD_LIBRARY_PATH /usr/lib/libeatmydata
ENV LD_PRELOAD libeatmydata.so

ENV USER jenkins
