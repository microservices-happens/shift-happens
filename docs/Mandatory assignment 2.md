# Mandatory Assignment 2 Development of Large Systems

This assignment is part of the final project described in the final project description document.

## Implementation process

Start implementing your system based on the design you made in your 1st assignment. The implementation should follow an incremental and collaborative approach. Team members are expected to work independently on their assigned microservices while coordinating integration points with the rest of the system.

## At this stage, students should:

- Divide responsibilities so that each team member primarily owns one or more microservices.

- Define and agree on service contracts early (REST APIs, GraphQL schemas, message formats, events, authentication approach) to enable parallel development.

- Implement microservices independently while using mocks, stubs, or agreed contracts where dependent services are not yet available.

- Regularly integrate independently developed services and validate communication between them, especially asynchronous workflows using message queues.

- Continuously refine the architecture and communication patterns based on implementation experience and team feedback.

- Each team member is responsible for providing a development setup for their assigned microservice that enables other team members to integrate with it. This includes any required dependencies (e.g., database, message broker), environment configuration (e.g., .env file or equivalent), database initialization, and representative test data needed to run and test integrations locally (for example using docker-compose). The microservice should be configurable through external configuration (e.g., database URLs, ports, credentials, …) and should not rely on machine-specific or hardcoded values, so that it can run consistently across different team members’ environments.

The goal is not to complete the full system yet, but to demonstrate meaningful progress toward an integrated distributed system.


## Expected outcomes

## System

The result of this assignment should be a partially functional system in the development environment - use Docker images for microservices, databases, and message broker.

At least basic automated tests (unit or integration) should be implemented and executable as part of the CI/CD pipeline.

The system should demonstrate at least one asynchronous interaction between microservices using a message queue.

## Documentation

There should be already some documentation of the existing parts of the system.

## Deployment

The system does not need to be deployed yet but there should be a clear plan in place.

## Report

Continue writing the report that you started in your 1st mandatory assignment. Focus on the parts that were covered in this assignment.

## Presentation

- Each working group will present their results during the class.

- Discussion

- Feedback - what to improve

## Delivery to itsLearning before the deadline

- Provide a link to your group GitHub monorepo

- Upload your PowerPoint presentation

- Report  covering this part
