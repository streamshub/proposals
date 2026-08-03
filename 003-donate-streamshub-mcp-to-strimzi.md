# Donation of streamshub-mcp to the Strimzi Organization

This proposal seeks formal agreement from StreamsHub to donate the [streamshub-mcp](https://github.com/streamshub/streamshub-mcp) repository to the [Strimzi](https://github.com/strimzi) organization.

## Current situation

The streamshub-mcp project was created under the StreamsHub organization as described in [proposal 002](./002-streamshub-mcp-strimzi.md).
It provides a Model Context Protocol (MCP) server for managing and troubleshooting Strimzi-managed Apache Kafka clusters.
The repository contains the Strimzi MCP server application along with shared modules (`common`, `metrics-prometheus`, `loki-log-provider`).

## Motivation

- The Strimzi community has expressed interest in adopting the MCP server as part of the Strimzi ecosystem.
- The MCP server is Strimzi-specific tooling — hosting it under the Strimzi organization provides better alignment with its primary domain.
- Moving the project under Strimzi opens it to a broader contributor base and tighter integration with Strimzi's development and release processes.

## Proposal

The entire `streamshub-mcp` repository, including all modules (`common`, `metrics-prometheus`, `loki-log-provider`, `strimzi-mcp`, `systemtest`), will be transferred to the Strimzi GitHub organization.

The donation is subject to the following conditions:

### Availability

All released components of MCP servers within Strimzi org has to be publicly available.
The destination may differ based on what Strimzi will be using for their artifacts (`quay.io` vs `ghcr.io` for example), but it has to be available for anyone.
Any upcoming features, improvements, or code changes has to be public as well.

### Write access

David Kornel must be granted component owner to the donated repository.
Jakub Stejskal is already a Strimzi maintainer and requires no additional access.

### Modules reusability

The following modules - `streamshub-mcp-common`, `metrics-prometheus`, `loki-log-provider` - must be published to Maven Central so that modules can be used by external consumers without needing to build from source or fork the Strimzi repository.

### Pluggable interfaces

The following pluggable interfaces must remain as extensible SPI/CDI contracts, allowing alternative implementations to be provided without forking:

- **Metrics** — `MetricsProvider` interface with provider selection via `mcp.metrics.provider` configuration.
- **Logs** — `LogCollectorProvider` interface with provider selection via `mcp.log.provider` configuration.
- **Auth** — currently not implemented, but users has to have option to replace auth & authz mechanism with their own implementation.
- **Guardrails** — `GuardrailFilter` interface for request/response filtering (input validation, rate limiting, log redaction, response size limits).

### Kafka MCP server

There is a plan to develop a Kafka MCP server providing direct access to Apache Kafka internals (topics, consumer groups, broker configuration).
Whether this server will live alongside the Strimzi MCP server in the donated repository or remain in StreamsHub will be subject to further discussion with the Strimzi community.

## Affected/not affected projects

### Affected

- [streamshub-mcp](https://github.com/streamshub/streamshub-mcp) — transferred to the Strimzi organization.
- [streamshub-site](https://github.com/streamshub/streamshub-site) - all upcoming releases will be done in Strimzi org and release page will be eventually hosted on Strimzi website.

### Not affected

- All other StreamsHub projects remain unchanged.

## Compatibility

Existing users of the MCP server are not affected.
The MCP protocol contract and Kubernetes API interactions remain the same regardless of which GitHub organization hosts the source code.
