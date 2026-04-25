Feature: Fact Ingestion and Retrieval

  Background:
    Given the server is running and I am authenticated

  Scenario: Create a fact and retrieve it
    Given a person and document exist in the system
    When I POST a fact for the existing document with operation type "create"
    Then the fact response status is 201
    And the fact response body contains "create"

  Scenario: Get fact history returns all operations for an entity instance
    Given a person and document exist in the system
    When I POST a fact for the existing document with operation type "create"
    Then the fact response status is 201
    When I POST an update fact for the same entity instance with status "in_progress"
    Then the fact response status is 201
    When I GET the fact history for the entity instance
    Then the response status is 200
    And the fact history contains at least 2 entries

  Scenario: Listing facts for a document
    Given a person and document exist in the system
    When I POST a fact for the existing document with operation type "create"
    Then the fact response status is 201
    When I GET facts for the document
    Then the response status is 200
    And the response body contains "create"
