SOURCE_BRANCH ?= "R4.0"
DPDK_SOURCE_BRANCH ?= "contrail_dpdk_17_02"
GIT_CONTRAIL_BASE ?= https://github.com/Mirantis
CWD=$(shell pwd)

OS   ?= ubuntu
DIST ?= xenial
ARCH ?= amd64

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
	fetch-third-party \
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
