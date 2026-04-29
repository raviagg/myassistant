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

  Scenario: Get current fact by entity instance ID
    Given a person and document exist in the system
    When I POST a fact for the existing document with operation type "create"
    Then the fact response status is 201
    When I GET the current fact for the entity instance
    Then the response status is 200
    And the fact response body contains "entityInstanceId"

  Scenario: Get facts by domain
    Given a person and document exist in the system
    When I POST a fact for the existing document with operation type "create"
    Then the fact response status is 201
    When I GET facts by domain
    Then the response status is 200
    And the fact response body contains "entityInstanceId"

  Scenario: Create a delete-type fact
    Given a person and document exist in the system
    When I POST a fact for the existing document with operation type "create"
    Then the fact response status is 201
    When I POST a fact for the existing document with operation type "delete"
    Then the fact response status is 201
    And the fact response body contains "delete"

  Scenario: Get current facts returns empty list when no filter
    When I GET "/api/v1/facts/current"
    Then the response status is 200

  Scenario: Get non-existent fact by ID returns 404
    When I GET "/api/v1/facts/00000000-0000-0000-0000-000000000000/current"
    Then the response status is 404

  Scenario: Get fact history for non-existent entity returns 404
    When I GET "/api/v1/facts/00000000-0000-0000-0000-000000000000/history"
    Then the response status is 404

  Scenario: Create fact with non-existent document returns 409
    Given a person and document exist in the system
    When I POST a fact with non-existent document ID
    Then the fact response status is 409
