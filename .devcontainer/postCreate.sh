#!/bin/bash
set -euo pipefail

# Host-mounted workspace is often root-owned; mark it safe for git as user pebble.
git config --global --add safe.directory /workspaces/EZ_SOS

pebble --version
pebble sdk list
