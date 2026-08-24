# AI Agent Project Guidelines

This document provides context and instructions for AI agents working on the Pixlit project.

## Project Context
- **Name:** Pixlit (Pixelfed Client)
- **Namespace:** `ovh.litapp.pixlit`
- **Main Goal:** Create a high-performance image uploader for Pixelfed, tailored for a single user (carlo-colombo). The primary focus is the upload flow; consumption (timeline/browsing) is explicitly out of scope.

## Architecture
- **Pattern:** MVVM (Model-View-ViewModel) with a focus on Unidirectional Data Flow.
- **Dependency Injection:** TBD (Plan to use Hilt or Koin).
- **Persistence:** Room (Planned).

## Coding Standards
- **Kotlin:** Follow idiomatic Kotlin practices. Prefer `val` over `var`, and use `Data` classes for models.
- **Compose:** Use state hoisting. Aim for reusable, stateless composables.
- **Naming:** Follow standard Android/Kotlin naming conventions (`PascalCase` for classes, `camelCase` for functions and variables).
- **Documentation:** Use KDoc for public APIs and complex logic.

## Tools & Libraries
- **Networking:** Retrofit for API definitions, OkHttp for the client.
- **Images:** Coil for asynchronous image loading.
- **Testing:** JUnit 4, Robolectric, and Compose Test Rule for UI testing.

## Working with Pixelfed API
- Refer to the official [Pixelfed API Documentation](https://docs.pixelfed.org/technical-documentation/api/).
- Authentication is handled via OAuth2.
