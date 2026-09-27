# Connected Vehicle Apps Project Knowledge

## Overview
A connected-vehicle application platform providing safe access to in-vehicle information and services.

## Functional areas
- Local Search
- Internet Radio
- Weather
- E-Navigator
- Yelp/places integration
- POI download
- News

## Architecture themes
The backend uses layered services with REST APIs, DTO/POJO models, service/DAO components and Spring dependency injection. Third-party integrations are exposed through backend services and validated before returning data to clients.

## Technology themes
Java, Spring, Spring Boot, Spring Cloud, Spring Data JPA, PostgreSQL, REST APIs, Git, Maven, Jenkins, JUnit and web technologies.

## RAG relevance
The project provides knowledge about integrating multiple external content providers, domain-oriented backend services, validation and reusable integration components. Current third-party API credentials or private customer/vehicle data must never be indexed.
