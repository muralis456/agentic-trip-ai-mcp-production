# Retail Cart and Checkout Project Knowledge

## Overview
A retail microservices platform supporting shopping cart and checkout workflows. The flow covers adding products to a bag, applying adjustments and coupons, delivery, billing, order submission and order integration.

## Representative services
- Bag
- Adjustment
- Best Coupon
- Delivery
- Billing
- Order
- Order Integration

## Workflow
Customer adds products to a cart -> cart and pricing/business validations run -> shipping and billing details are collected -> checkout validations are performed -> order is submitted -> downstream order processing is triggered.

## Engineering patterns
The project uses Spring REST APIs, microservices, business validation, reusable backend components, automated tests and cloud-based services. Backend work includes API implementation, monitoring, defect fixing and integration testing.

## Technology themes
Java, Spring, Spring Boot, Spring Cloud, Spring Data Cassandra, AWS services, REST APIs, Git, Gradle, Jenkins, JUnit and Mockito.

## RAG relevance
Useful durable knowledge includes service responsibilities, checkout workflow concepts, validation rules, integration boundaries and troubleshooting guidance. Production customer data, payment details and credentials must never be indexed.
