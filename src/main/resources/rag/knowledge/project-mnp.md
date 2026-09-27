# Mobile Number Portability Project Knowledge

## Overview
A telecom backend system supporting mobile number portability, where a customer changes operators while retaining the existing phone number.

## Modules
- Port-in
- Port-out
- Emergency Port-in
- Emergency Port-out

## Domain workflow
A portability request involves recipient and donor operator interactions, validation of portability information and controlled processing of normal and emergency flows. Correct routing and state transitions are important because multiple operators participate in the process.

## Engineering patterns
RESTful backend APIs, business validations, POJOs/DTOs, service and DAO layers, reusable components, third-party integrations, unit/integration testing and defect resolution.

## Technology themes
Java, Spring, Hibernate, REST, JUnit, Oracle, MongoDB, JSON, Maven, Git, Tomcat, Guvnor and Jersey.

## RAG relevance
Useful knowledge includes portability concepts, module responsibilities, workflow states, validation rules and integration patterns. Subscriber numbers and other telecom/customer PII must never be indexed into a general knowledge base.
