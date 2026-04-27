Feature: Document Management

  Background:
    Given the server is running and I am authenticated

  Scenario: Create and retrieve a document
    Given a person exists for document tests
    When I POST a document for the person with content "My first document"
    Then the response status is 201
    And the response body contains "My first document"
    When I GET the created document by id
    Then the response status is 200
    And the response body contains "My first document"

  Scenario: List documents for a person
    Given a person exists for document tests
    When I POST a document for the person with content "Listed document"
    Then the response status is 201
    When I GET documents for the person
    Then the response status is 200
    And the response body contains "Listed document"

  Scenario: Search documents
    Given a person exists for document tests
    When I POST a document for the person with content "Searchable content"
    Then the response status is 201
    When I POST to "/api/v1/documents/search" with body:
      """
      {"query": "Searchable"}
      """
    Then the response status is 200

  Scenario: Get non-existent document returns 404
    When I GET "/api/v1/documents/00000000-0000-0000-0000-000000000000"
    Then the response status is 404

  Scenario: Create document requires contentText
    Given a person exists for document tests
    When I POST to "/api/v1/documents" with body:
      """
      {"sourceType": "user_input", "files": [], "supersedesIds": []}
      """
    Then the response status is 400

  Scenario: Create document with supersedes reference
    Given a person exists for document tests
    When I POST a document for the person with content "Original document"
    Then the response status is 201
    When I POST a document superseding the created document with content "Updated document"
    Then the response status is 201
    And the response body contains "Updated document"
