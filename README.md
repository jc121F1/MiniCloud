# MiniCloud

MiniCloud is a lightweight, self-hosted cloud compute platform inspired by services such as **Amazon EC2**.

The project provides a REST API for managing compute instances while separating the cloud-control-plane logic from the underlying compute implementation. Instances are currently backed by Docker containers, allowing MiniCloud to provide a simple virtual-machine-like experience on a local machine or server.

> **Status:** Active development  
> **Language:** Java 25  
> **Build system:** Gradle  
> **API framework:** Javalin

## Overview

MiniCloud is designed as a small-scale implementation of the core concepts found in a cloud compute platform:

```text
                         ┌─────────────────────┐
                         │      REST API       │
                         │      Javalin        │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │   Instance Service  │
                         │                     │
                         │ Create / List /     │
                         │ Describe / Start /  │
                         │ Stop / Delete       │
                         └──────────┬──────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    ▼                               ▼
          ┌──────────────────┐            ┌──────────────────┐
          │ Instance Store   │            │ Compute Backend  │
          │                  │            │                  │
          │ DynamoDB         │            │ Docker           │
          └──────────────────┘            └────────┬─────────┘
                                                   │
                                                   ▼
                                          ┌──────────────────┐
                                          │ Docker Container │
                                          └──────────────────┘
```

The architecture intentionally uses interfaces around persistence and compute so that the control plane is not tightly coupled to Docker or a particular storage implementation.

## Features

### Instance lifecycle

MiniCloud currently supports the core instance lifecycle:

- Create an instance
- List instances
- Describe an instance
- Start an instance
- Stop an instance
- Delete an instance

Instances currently expose properties including:

- Instance ID
- Name
- CPU allocation
- Memory allocation
- Instance state
- Creation timestamp

The instance model is also mapped for persistence using the AWS DynamoDB Enhanced Client.

### Docker-backed compute

The current compute implementation uses Docker as the underlying execution environment.

Each MiniCloud instance is represented by a Docker container with resource constraints derived from the instance configuration:

- CPU count
- Memory allocation

The compute layer exposes an asynchronous interface for:

```text
create()
start()
stop()
delete()
```

This is deliberately abstracted behind `ComputeBackend`, making alternative compute implementations possible in the future.

The current Docker implementation uses the `jc121f1/alpine` image and tracks the relationship between MiniCloud instances and their Docker containers.

### REST API

The service is implemented using Javalin and listens on port **7070** by default.

Current routes include:

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | Service/root endpoint |
| `GET` | `/instances` | List instances |
| `POST` | `/instances` | Create an instance |
| `POST` | `/instances/delete` | Delete an instance |
| `POST` | `/instances/start` | Start an instance |
| `POST` | `/instances/stop` | Stop an instance |
| `POST` | `/instances/describe` | Describe an instance |

An optional shutdown endpoint is also available for development/debug configurations.

### Dependency injection

MiniCloud uses **Dagger** for compile-time dependency injection.

This keeps service construction separate from business logic and makes components such as the compute backend, persistence layer, configuration, and web handlers replaceable.

### Service discovery

The application includes optional **mDNS** support through JmDNS, allowing the service to advertise itself as:

```text
instance:7070
```

mDNS can be disabled, which is particularly useful for tests and environments where local network service discovery is not required.

### Testing and code quality

The project includes:

- JUnit 5
- Mockito
- AssertJ
- Javalin TestTools
- Awaitility
- JaCoCo
- Checkstyle
- SpotBugs
- GitHub Actions CI

The Gradle build generates a JaCoCo coverage report and treats Checkstyle and SpotBugs failures as build failures.

## Technology Stack

| Component | Technology |
|---|---|
| Language | Java 25 |
| Build | Gradle |
| Web framework | Javalin |
| Dependency injection | Dagger |
| Persistence | Amazon DynamoDB |
| Compute | Docker |
| JSON | Jackson |
| Service discovery | JmDNS |
| Logging | SLF4J + Logback |
| Testing | JUnit 5, Mockito, AssertJ |
| Coverage | JaCoCo |
| Static analysis | SpotBugs |
| Style checking | Checkstyle |
| CI | GitHub Actions |

## Requirements

To run MiniCloud locally you will need:

- Java 25
- Docker
- A running Docker daemon
- DynamoDB
- Gradle or the included Gradle wrapper

The project uses the Gradle wrapper, so installing Gradle separately is not normally necessary.

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/jc121F1/MiniCloud.git
cd MiniCloud
```

### 2. Start the required infrastructure

MiniCloud uses Docker for compute and DynamoDB for instance persistence.

Make sure Docker is running and that the DynamoDB endpoint/configuration expected by the application is available.

### 3. Build the project

Linux/macOS:

```bash
./gradlew build
```

Windows:

```powershell
.\gradlew.bat build
```

The build also produces the executable Shadow JAR.

### 4. Run MiniCloud

The application's entry point is:

```text
jc121f1.Main
```

The application starts the web service on port `7070`.

You can run it through Gradle with:

```bash
./gradlew run
```

or execute the generated JAR from the build output.

## Example API Usage

### Create an instance

```bash
curl -X POST http://localhost:7070/instances \
  -H "Content-Type: application/json" \
  -d '{
    "name": "example",
    "cpu": 2,
    "memory": 1024
  }'
```

### List instances

```bash
curl http://localhost:7070/instances
```

### Describe an instance

```bash
curl -X POST http://localhost:7070/instances/describe \
  -H "Content-Type: application/json" \
  -d '{
    "id": "INSTANCE_ID"
  }'
```

### Start an instance

```bash
curl -X POST http://localhost:7070/instances/start \
  -H "Content-Type: application/json" \
  -d '{
    "id": "INSTANCE_ID"
  }'
```

### Stop an instance

```bash
curl -X POST http://localhost:7070/instances/stop \
  -H "Content-Type: application/json" \
  -d '{
    "id": "INSTANCE_ID"
  }'
```

### Delete an instance

```bash
curl -X POST http://localhost:7070/instances/delete \
  -H "Content-Type: application/json" \
  -d '{
    "id": "INSTANCE_ID"
  }'
```

## Architecture

MiniCloud is structured around several layers.

### Web layer

The web layer is responsible for HTTP routing, request deserialization, response serialization, and translating API operations into service calls.

### Service layer

`InstanceService` contains the instance management operations:

```java
Instance get(GetInstanceRequest request);

Instance create(CreateInstanceRequest request);

List<Instance> list(ListInstanceRequest request);

Instance delete(DeleteInstanceRequest request);

Instance stop(StopInstanceRequest request);

Instance start(StartInstanceRequest request);
```

This keeps the API layer independent of the underlying implementation.

### Compute layer

The compute layer is represented by:

```java
public interface ComputeBackend {
    CompletableFuture<Void> create(Instance instance);
    CompletableFuture<Void> start(Instance instance);
    CompletableFuture<Void> stop(Instance instance);
    CompletableFuture<Void> delete(Instance instance);
}
```

This abstraction allows MiniCloud to evolve beyond Docker without rewriting the instance service.

### Persistence layer

Instance metadata is persisted separately from the compute environment.

The `Instance` model is annotated for use with the DynamoDB Enhanced Client, with the instance ID acting as the DynamoDB partition key.

This separation allows the control plane to retain instance metadata independently of the Docker container lifecycle.

## Project Structure

The major components are organized approximately as follows:

```text
src/main/java/jc121f1/
├── dagger/
│   ├── EnvironmentModule.java
│   ├── ServiceModule.java
│   └── WebserviceComponent.java
│
├── model/
│   └── instance/
│       ├── InstanceState.java
│       ├── api/
│       │   └── request/
│       └── dao/
│           └── Instance.java
│
├── services/
│   └── instance/
│       ├── InstanceService.java
│       ├── InstanceServiceImpl.java
│       └── compute/
│           ├── ComputeBackend.java
│           └── docker/
│               ├── DockerComputeBackend.java
│               └── DockerEventListener.java
│
├── wbs/
│   ├── WebService.java
│   └── handlers/
│
└── Main.java
```

## Design Goals

MiniCloud is primarily a learning and engineering project for exploring how a cloud infrastructure platform can be constructed from first principles.

The project focuses on:

- Clean separation between control plane and compute
- Explicit service abstractions
- Asynchronous compute operations
- Dependency injection
- Persistent instance state
- Infrastructure lifecycle management
- Automated testing
- Static analysis and code quality
- Local, reproducible infrastructure

The goal is not to reproduce the entire AWS API surface, but to progressively implement the architectural concepts behind a cloud platform.

## Roadmap

Planned areas of development include:

### Compute

- More instance configuration options
- Images
- Instance capabilities
- Better container networking
- Persistent storage
- Multiple compute backends
- Improved instance state reconciliation

### Networking

Introduce cloud networking primitives similar to those found in AWS VPC:

- VPCs
- Subnets
- Route tables
- Internet gateways
- Security groups
- Network interfaces
- Private/public networking

### Storage

- Persistent instance volumes
- Volume lifecycle management
- Snapshot support
- Object storage integration

### Control plane

- More robust asynchronous state management
- Instance reconciliation
- Improved failure handling
- Resource quotas
- Multi-instance operations
- API versioning

### Platform

- Authentication and authorization
- Multi-user support
- Metrics
- Distributed service architecture
- Production deployment
- High availability

## Development

Run the complete verification suite with:

```bash
./gradlew build
```

This runs the project's tests and quality checks and generates the JaCoCo coverage report.

For faster local iteration, individual tasks can also be run:

```bash
./gradlew test
./gradlew checkstyleMain
./gradlew checkstyleTest
./gradlew spotbugsMain
./gradlew jacocoTestReport
```

## Disclaimer

MiniCloud is an experimental and educational project.

It is **not intended to provide production-grade isolation, security, reliability, availability, or resource guarantees** comparable to a commercial cloud provider.

In particular, Docker containers should not be considered equivalent to dedicated virtual machines or hardware-level isolation.

## License

No license has currently been specified for this repository.


## Author

Created by [jc121F1](https://github.com/jc121F1).

Repository: https://github.com/jc121F1/MiniCloud