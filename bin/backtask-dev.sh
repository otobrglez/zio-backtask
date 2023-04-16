#!/usr/bin/env bash
set -e

BACKTASK_DEV_HOME=${BACKTASK_DEV_HOME:=$(pwd)}
DOCKER_COMPOSE_FLAGS=${DOCKER_COMPOSE_FLAGS:=""}

if [[ -z "${BACKTASK_DEV_HOME}" ]]; then
  echo "BACKTASK_DEV_HOME environment variable is not set!" && exit 255
fi

# shellcheck disable=SC2068
cd "$BACKTASK_DEV_HOME" &&
  docker-compose \
    -f docker-compose.yml ${DOCKER_COMPOSE_FLAGS} \
    --project-name backtask \
    --project-directory . $@
