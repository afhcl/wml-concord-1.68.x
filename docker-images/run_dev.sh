#!/bin/bash

BASE_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

if [[ -z ${VERSION} ]]; then
    VERSION="latest"
fi
echo "VERSION: ${VERSION}"

if [[ -z ${DOCKER_PREFIX} ]]; then
    DOCKER_PREFIX="walmartlabs"
fi
echo "DOCKER_PREFIX: ${DOCKER_PREFIX}"

if [[ -z ${CONCORD_CFG_FILE} ]]; then
    CONCORD_CFG_FILE=${BASE_DIR}/server.conf
fi
echo "CONCORD_CFG_FILE: ${CONCORD_CFG_FILE}"

if grep 'AD_' "${CONCORD_CFG_FILE}"; then
    echo "To start, enter your AD/LDAP credentials into ${CONCORD_CFG_FILE}"
    exit 1
fi

echo "Removing old containers..."
docker rm -f db dind agent server console > /dev/null

docker run -d \
--name db \
-e 'POSTGRES_PASSWORD=q1' \
-e 'PGDATA=/var/lib/postgresql/data/pgdata' \
--mount source=concordDB,target=/var/lib/postgresql/data \
-p 5432:5432 \
"library/postgres:10.4-alpine"

docker run -d \
--link db \
--name server \
-p 8001:8001 \
-v "/tmp:/tmp" \
-v "${CONCORD_CFG_FILE}:${CONCORD_CFG_FILE}:ro" \
-e "CONCORD_CFG_FILE=${CONCORD_CFG_FILE}" \
-e 'DB_URL=jdbc:postgresql://db:5432/postgres' \
"${DOCKER_PREFIX}/concord-server:${VERSION}"

# wait for the server to start
echo -n "Waiting for the server to start"
until $(curl --output /dev/null --silent --head --fail "http://localhost:8001/api/v1/server/ping"); do
    printf '.'
    sleep 1
done
echo "done!"

docker run -d \
--name agent \
--link server \
-v "/tmp:/tmp" \
-v "${HOME}/.m2/repository:/home/concord/.m2/repository" \
-v "${BASE_DIR}/mvn.json:/opt/concord/conf/mvn.json:ro" \
-e 'CONCORD_MAVEN_CFG=/opt/concord/conf/mvn.json' \
-e 'CONCORD_DOCKER_LOCAL_MODE=false' \
-e 'SERVER_API_BASE_URL=http://server:8001' \
-e 'SERVER_WEBSOCKET_URL=ws://server:8001/websocket' \
"${DOCKER_PREFIX}/concord-agent:${VERSION}"

docker run -d \
--name console \
--link server \
-p 8080:8080 \
-v /tmp/concord/console/logs:/opt/concord/logs \
-v "${BASE_DIR}/console.conf:/opt/concord/console/nginx/app.conf:ro" \
"${DOCKER_PREFIX}/concord-console:${VERSION}"
