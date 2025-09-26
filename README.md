# Station Energy Management System

A production-grade Load Management Algorithm for Electric Vehicle charging stations with Battery Energy Storage System (BESS) integration.

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Algorithm](https://img.shields.io/badge/Algorithm-Proportional%20Fairness-orange.svg)](#algorithm)

## 🎯 Overview

This system implements a sophisticated **Load Management algorithm** for EV charging stations, similar to Electra's in-house Load Management Device (LMD). It orchestrates power distribution between multiple EV chargers while respecting grid constraints, optimizing energy usage, and integrating Battery Energy Storage Systems for peak shaving.

### Key Features

- **⚡ Proportional Fairness Algorithm**: Optimal balance of fairness and efficiency for power allocation
- **🔋 BESS Integration**: Peak shaving and valley filling with Li-ion battery management
- **🌐 Reactive API**: Sub-second response times using Spring WebFlux
- **🔒 Thread-Safe Design**: Concurrent session management with atomic operations
- **📊 Real-time Monitoring**: Comprehensive metrics and status endpoints
- **🧪 Test Scenarios**: Automated validation of all assignment requirements

## 🏗️ Architecture

### High-Level System Design

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   EV Chargers   │    │  Load Manager    │    │   BESS Controller│
│                 │◄──►│                  │◄──►│                 │
│ • CP001 (200kW) │    │ • PF Algorithm   │    │ • Peak Shaving  │
│ • CP002 (200kW) │    │ • Constraints    │    │ • SOC Management│
│ • CP003 (300kW) │    │ • Real-time      │    │ • Safety Limits │
└─────────────────┘    └──────────────────┘    └─────────────────┘
         │                        │                        │
         └────────────────────────┼────────────────────────┘
                                  │
                    ┌─────────────────────────┐
                    │    REST API Layer       │
                    │                         │
                    │ • Session Management    │
                    │ • Station Status        │
                    │ • Power Allocation      │
                    │ • Real-time Updates     │
                    └─────────────────────────┘
```

### Core Components

#### 1. **LoadManager** - Proportional Fairness Algorithm
- **Algorithm**: Water-filling iterative approach maximizing Σ log(allocated_i)
- **Constraints**: Multi-level (vehicle → connector → charger → grid → BESS)
- **Performance**: O(n*log(n)) convergence, <1s response time
- **Thread-Safety**: Lock-free concurrent execution

#### 2. **SessionManager** - Concurrent Session Handling
- **Data Structure**: ConcurrentHashMap for thread-safe operations
- **Connector Management**: Atomic availability checking and reservation
- **State Management**: Immutable session state with atomic updates

#### 3. **BessController** - Battery Management
- **SOC Management**: 10% minimum, 95% maximum for battery health
- **Peak Shaving**: Automatic discharge when grid demand > capacity
- **Valley Filling**: Opportunistic charging during low demand periods
- **Safety**: Emergency shutdown at 5% SOC

#### 4. **API Layer** - Reactive REST Endpoints
- **Technology**: Spring WebFlux for non-blocking I/O
- **Endpoints**: Session CRUD, power updates, station status
- **Performance**: Sub-second response times, reactive backpressure

## Algorithm Design Decisions

### Why Proportional Fairness (PF)?

**Mathematical Foundation**:
- **Objective**: Maximize Σ log(allocated_power_i) subject to capacity constraints
- **Convergence**: Guaranteed convergence via water-filling algorithm

**Alternatives Considered**:

| Algorithm | Fairness | Efficiency | Complexity | Real-time | Decision |
|-----------|----------|------------|------------|-----------|----------|
| **Greedy (FCFS)** | ❌ Poor | ✅ High | ✅ O(n) | ✅ Fast | ❌ Unfair to late arrivals |
| **Max-Min Fair** | ✅ Perfect | ❌ Lower | ✅ O(n) | ✅ Fast | ❌ Reduces total throughput |
| **Linear Programming** | ✅ Optimal | ✅ Optimal | ❌ O(n³) | ❌ Slow | ❌ Too complex for <1s requirement |
| **Proportional Fair** | ✅ Good | ✅ High | ✅ O(n log n) | ✅ Fast | ✅ **Selected** |

### Multi-Level Constraint Handling

```
Level 1: Vehicle Power Limits     (min(allocation, vehicle_max_power))
         ↓
Level 2: Connector Availability   (ensure connector not double-booked)
         ↓  
Level 3: Charger Power Limits     (Σ connectors ≤ charger_max_power)
         ↓
Level 4: Grid Capacity Limits     (Σ all_chargers ≤ grid_capacity - static_load)
         ↓
Level 5: BESS Integration         (additional capacity when SOC > min_level)
```

### BESS Peak Shaving Strategy

**Industry Best Practices**: Based on MDPI Energy review papers on battery energy storage for peak shaving applications.

**Strategy**:
- **Peak Shaving**: Discharge when total_load > (grid_capacity - safety_margin)
- **Valley Filling**: Charge when total_load < (grid_capacity * 0.7)
- **SOC Management**: Maintain 10-95% SOC for optimal Li-ion battery health
- **Safety**: Emergency cutoff at 5% SOC to prevent deep discharge

## 🚀 Quick Start

### Prerequisites

- **Java 17+** (OpenJDK or Oracle JDK)
- **Maven 3.6+** 
- **Git**

### Installation & Running

```bash
# Clone the repository
git clone https://github.com/CarlHad333/Energy-Management-System.git
cd Energy-Management-System

# Build the application (produces target/Energy-Management-System-1.0.0.jar)
mvn clean package -DskipTests

# Run the application (via Maven)
mvn spring-boot:run

# Run the application (via JAR)
java -jar target/Energy-Management-System-1.0.0.jar
```

The application will start on `http://localhost:8080`

### Quick Health Check

```bash
# Check application health
curl http://localhost:8080/api/v1/station/health

# Get station status
curl http://localhost:8080/api/v1/station/status

# View configuration
curl http://localhost:8080/api/v1/station/config
```

## Test Scenarios

### Running Test Scenarios

The system includes automated scenarios matching the assignment requirements:

```bash
# Run with scenario simulator enabled
mvn spring-boot:run -Dspring-boot.run.arguments=--simulator.enabled=true
```

### Scenario 1: Static Load Management
```
Configuration: gridCapacity=400kW, CP001 (200kW, 2 connectors)
Test: 2 vehicles @ 150kW max each
Expected: ~100kW each (fair split of 200kW charger capacity)
```

### Scenario 2: Dynamic Power Re-allocation
```
Configuration: gridCapacity=400kW, CP001 & CP002 (300kW each)
Timeline:
  T0: 2 vehicles charging @ 150kW each (300kW total)
  T1: 3rd vehicle arrives (150kW max)
  T2: 4th vehicle arrives (150kW max) 
  T3: 1st vehicle finishes and leaves
Expected: Power reallocation without grid violation (≤400kW total)
```

### Scenario 3: Battery Boost Integration
```
Configuration: Same as Scenario 2 + BESS (200kWh, 100kW power)
Expected: >400kW total allocation during BESS discharge
```

### Manual Testing via API

```bash
# Start a charging session
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"chargerId": "CP001", "connectorId": 1, "vehicleMaxPower": 150}'

# Update power consumption
curl -X POST http://localhost:8080/api/v1/sessions/{sessionId}/power-update \
  -H "Content-Type: application/json" \
  -d '{"consumedPower": 75, "vehicleMaxPower": 140}'

# Stop session
curl -X POST http://localhost:8080/api/v1/sessions/{sessionId}/stop

# Get real-time station status
curl http://localhost:8080/api/v1/station/status
```

## API Documentation (Swagger / OpenAPI)

Swagger UI is enabled via Springdoc and available at:
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

The API currently has no authentication; if enabled later, log in with the provided credentials.

## Running with Docker

### Build and Run (Docker CLI)
```bash
# Build image
docker build -t energy-management-system:1.0.0 .

# Run container
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e STATION_ID=ELECTRA_PARIS_15 \
  -e GRID_CAPACITY=400 \
  -e BATTERY_ENABLED=true \
  --name station-energy-management \
  energy-management-system:1.0.0
```

### Run with Docker Compose
```bash
docker compose up --build -d
docker compose logs -f station-energy-management
# Shutdown
docker compose down
```

Once running:
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Health: `http://localhost:8080/api/v1/station/health`

## Testing

### Unit Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=LoadManagerTest

# Run with coverage
mvn test jacoco:report
```

### Test Coverage

- **LoadManager**: Algorithm correctness, constraint validation, performance
- **SessionManager**: Thread safety, concurrent operations, data consistency
- **BessController**: SOC management, peak shaving, safety limits
- **Integration Tests**: End-to-end API scenarios

## Monitoring & Observability

### Metrics Endpoints

```bash
# Application health
GET /actuator/health

# Application metrics
GET /actuator/metrics
```

### Key Metrics

- **Power Allocation Efficiency**: Total allocated vs. grid capacity utilization
- **Fairness Index**: Jain's fairness index for power distribution
- **Response Time**: API response times (<1s requirement)
- **BESS Utilization**: Charge/discharge cycles and SOC trends
- **Session Statistics**: Active sessions, average session duration
