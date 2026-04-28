Feature: Schema Governance

  Background:
    Given the server is running and I am authenticated

  Scenario: List schemas returns seeded schemas
    When I GET "/api/v1/schemas"
    Then the response status is 200
    And the response body contains "todo_item"

  Scenario: Get current schema by domain and entity type
    When I GET the current schema for domain "todo" entity type "todo_item"
    Then the response status is 200
    And the response body contains "todo_item"
    And the response body contains "title"

  Scenario: Get schema by ID
    When I GET "/api/v1/schemas"
    Then the response status is 200
    When I GET the first schema by id
    Then the response status is 200
    And the response body contains "fieldDefinitions"

  Scenario: Propose a new schema
    When I propose a schema for domain "personal_details" entity type "address"
    Then the response status is 201
    And the response body contains "address"

  Scenario: List schemas filtered by domain
    When I GET schemas filtered by domain "health"
    Then the response status is 200
    And the response body contains "insurance_card"

  Scenario: Evolve an existing schema with a new version
    When I post a new version for domain "todo" entity type "todo_item" with body:
      """
      {
        "description": "Updated todo item schema",
        "fieldDefinitions": [{"name": "title", "type": "text", "required": true}, {"name": "status", "type": "text", "required": false}, {"name": "notes", "type": "text", "required": false}]
      }
      """
    Then the response status is 201
    And the response body contains "todo_item"

  Scenario: Get non-existent schema returns 404
    When I GET "/api/v1/schemas/00000000-0000-0000-0000-000000000000"
    Then the response status is 404

  Scenario: Propose schema with missing required fields returns 400
    When I POST to "/api/v1/schemas" with body:
      """
      {"entityType": "foo"}
      """
    Then the response status is 400

  Scenario: Deactivate a schema
    When I propose a schema for domain "finance" entity type "expense"
    Then the response status is 201
    When I deactivate the proposed schema for "finance" "expense"
    Then the response status is 204

  Scenario: Get current schema returns 404 after deactivation
    When I GET the current schema for domain "finance" entity type "expense"
    Then the response status is 404

  Scenario: Deactivate non-existent schema returns 404
    When I DELETE the active schema for domain "todo" entity type "nonexistent_type" with schema id "00000000-0000-0000-0000-000000000000"
    Then the response status is 404
