SOURCE_BRANCH ?= "R4.0"
DPDK_SOURCE_BRANCH ?= "contrail_dpdk_17_02"
GIT_CONTRAIL_BASE ?= https://github.com/Mirantis
CWD=$(shell pwd)

OS   ?= ubuntu
DIST ?= xenial
ARCH ?= amd64

REPOSITORY_TAGS := CEILOMETER_SHA HEAT_SHA WEB_CONTROLLER_SHA WEB_CORE_SHA CONTROLLER_SHA NEUTRON_PLUGIN_SHA NOVA_CONTRAIL_SHA THIRD_PARTY_SHA VROUTER_SHA

ifeq ($(SOURCE_BRANCH), "R5.0")
REPOSITORY_TAGS += ANALYTICS_SHA CONTRAIL_API_SHA
endif

all: checkout build-image build-source build-binary

help:
	@echo "all           Build everything"
	@echo "build-image   Build image for package build"
	@echo "shell         Enter shell in build container"
	@echo "build-shell   Enter build env for given PACKAGE"
	@echo "build-source  Build debian source packages"
	@echo "build-binary  Build debian binary packages"
	@echo "test          Run unit tests"
	@echo "clean         Cleanup after previous builds"

build-image:
	docker build -t build-$(OS)-$(DIST)-$(ARCH) -f docker/$(OS)-$(DIST)-$(ARCH).Dockerfile docker

shell:
	docker run -u 1000 -it -v $(CWD):$(CWD) -w $(CWD) --rm=true build-$(OS)-$(DIST)-$(ARCH) bash

build-shell:
	$(eval PACKAGE ?= contrail)
ifneq ($(KEEP_PACKAGE),yes)
	(rm -rf src/build/${PACKAGE} || true)
endif
	docker run -u 1000 -it -v $(CWD):$(CWD) -w $(CWD) --rm=true build-$(OS)-$(DIST)-$(ARCH) /bin/bash -c "dpkg-source -x src/build/packages/${PACKAGE}_*.dsc src/build/${PACKAGE}; \
		cd src/build/${PACKAGE}; sudo apt-get update; dpkg-checkbuilddeps 2>&1|rev|cut -d : -f 1|rev|sed 's,([^)]*),,g'|xargs sudo apt-get install -y; bash"

clean:
	rm -rf src/build

test: build-source
	docker run -u 1000 -t -v $(CWD):$(CWD) -w $(CWD)/src -e USER=jenkins --rm=true build-$(OS)-$(DIST)-$(ARCH) /bin/bash -c "../scripts/run_tests.sh"

build-source: \
	build-source-contrail-web-core \
	build-source-contrail-web-controller \
	build-source-contrail \
	build-source-contrail-vrouter-dpdk \
	build-source-ifmap-server \
	build-source-neutron-plugin-contrail \
	build-source-ceilometer-plugin-contrail \
	build-source-contrail-heat

fetch-third-party:
	docker run -u 1000 -t -v $(CWD):$(CWD) -w $(CWD)/src/third_party --rm=true build-$(OS)-$(DIST)-$(ARCH) python fetch_packages.py
	docker run -u 1000 -t -v $(CWD):$(CWD) -w $(CWD)/src/contrail-webui-third-party --rm=true build-$(OS)-$(DIST)-$(ARCH) python fetch_packages.py -f packages.xml
	rm -rf src/contrail-web-core/node_modules
	mkdir src/contrail-web-core/node_modules
	cp -rf src/contrail-webui-third-party/node_modules/* src/contrail-web-core/node_modules/

build-source-%:
	$(eval PACKAGE := $(patsubst build-source-%,%,$@))
	$(MAKE) add-code-specific-sha-$(PACKAGE)
	(rm -f src/build/packages/${PACKAGE}_* || true)
	docker run -u 1000 -t -v $(CWD):$(CWD) -w $(CWD)/src --rm=true build-$(OS)-$(DIST)-$(ARCH) make -f packages.make source-package-${PACKAGE}

build-binary: \
	build-binary-contrail-web-core \
	build-binary-contrail-web-controller \
	build-binary-contrail \
	build-binary-contrail-vrouter-dpdk \
	build-binary-ifmap-server \
	build-binary-neutron-plugin-contrail \
	build-binary-ceilometer-plugin-contrail \
	build-binary-contrail-heat

build-binary-%:
	$(eval PACKAGE := $(patsubst build-binary-%,%,$@))
	(rm -rf src/build/${PACKAGE} || true)
	docker run -u 1000 -t -v $(CWD):$(CWD) -w $(CWD) --rm=true build-$(OS)-$(DIST)-$(ARCH) /bin/bash -c "dpkg-source -x src/build/packages/${PACKAGE}_*.dsc src/build/${PACKAGE}; \
		cd src/build/${PACKAGE}; sudo apt-get update; dpkg-checkbuilddeps 2>&1|rev|cut -d : -f 1|rev|sed 's,([^)]*),,g'|xargs sudo apt-get install -y; \
		debuild --no-lintian ${OPTS} -uc -us"

checkout:
	SOURCE_BRANCH=${SOURCE_BRANCH} DPDK_SOURCE_BRANCH=${DPDK_SOURCE_BRANCH} GIT_CONTRAIL_BASE=${GIT_CONTRAIL_BASE} mr --trust-all -j4 update
	(test -e src/SConstruct || ln -s tools/build/SConstruct src/SConstruct)
	(test -e src/packages.make || ln -s tools/packages/packages.make src/packages.make)

add-code-specific-sha-%: set-shas
	$(eval PACKAGE := $(patsubst add-code-specific-sha-%,%,$@))
	$(foreach sha, $(REPOSITORY_TAGS), \
			(cd src/tools/packages/debian/$(PACKAGE)/debian;\
			sed -i 's/XB-Private-MCP-Code-SHA: $(sha)/XB-Private-MCP-Code-SHA: $($(sha))/g' control);)

set-shas:
ifeq ($(SOURCE_BRANCH), "R5.0")
	$(eval ANALYTICS_SHA := $(shell cd $(CWD)/src/src/contrail-analytics; git rev-parse HEAD))
	$(eval CONTRAIL_API_SHA := $(shell cd $(CWD)/src/src/contrail-api-client; git rev-parse HEAD))
endif
	$(eval CEILOMETER_SHA := $(shell cd $(CWD)/src/openstack/ceilometer_plugin; git rev-parse HEAD))
	$(eval HEAT_SHA := $(shell cd $(CWD)/src/openstack/contrail-heat; git rev-parse HEAD))
	$(eval WEB_CONTROLLER_SHA := $(shell cd $(CWD)/src/contrail-web-controller; git rev-parse HEAD))
	$(eval WEB_CORE_SHA := $(shell cd $(CWD)/src/contrail-web-core; git rev-parse HEAD))
	$(eval CONTROLLER_SHA := $(shell cd $(CWD)/src/controller; git rev-parse HEAD))
	$(eval NEUTRON_PLUGIN_SHA := $(shell cd $(CWD)/src/openstack/neutron_plugin; git rev-parse HEAD))
	$(eval NOVA_CONTRAIL_SHA := $(shell cd $(CWD)/src/openstack/nova_contrail_vif; git rev-parse HEAD))
	$(eval THIRD_PARTY_SHA := $(shell cd $(CWD)/src/third_party; git rev-parse HEAD))
	$(eval VROUTER_SHA := $(shell cd $(CWD)/src/vrouter; git rev-parse HEAD))
