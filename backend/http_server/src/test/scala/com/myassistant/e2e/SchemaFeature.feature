Feature: Schema Governance

  Background:
    Given the server is running and I am authenticated

  Scenario: List schemas returns seeded schemas
    When I GET "/api/v1/schemas"
    Then the response status is 200
    And the response body contains "todo"

  Scenario: Get current schema by domain and entity type
    When I GET "/api/v1/schemas/current?domain=todo&entityType=todo_item"
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
    When I POST to "/api/v1/schemas" with body:
      """
      {
        "domain": "personal_details",
        "entityType": "address",
        "description": "A residential address",
        "fieldDefinitions": [{"name": "street", "type": "text", "mandatory": true}, {"name": "city", "type": "text", "mandatory": false}],
        "extractionPrompt": "Extract address details",
        "changeDescription": "Initial version"
      }
      """
    Then the response status is 201
    And the response body contains "address"
    And the response body contains "personal_details"

  Scenario: List schemas filtered by domain
    When I GET "/api/v1/schemas?domain=health"
    Then the response status is 200
    And the response body contains "health"

  Scenario: Evolve an existing schema with a new version
    When I POST to "/api/v1/schemas/todo/todo_item/versions" with body:
      """
      {
        "domain": "todo",
        "entityType": "todo_item",
        "description": "Updated todo item schema",
        "fieldDefinitions": [{"name": "title", "type": "text", "mandatory": true}, {"name": "status", "type": "text", "mandatory": false}, {"name": "notes", "type": "text", "mandatory": false}],
        "extractionPrompt": "Extract todo item details including notes",
        "changeDescription": "Added notes field"
      }
      """
    Then the response status is 201
    And the response body contains "todo_item"

  Scenario: Get non-existent schema returns 404
    When I GET "/api/v1/schemas/00000000-0000-0000-0000-000000000000"
    Then the response status is 404

  Scenario: Propose schema with missing domain returns 400
    When I POST to "/api/v1/schemas" with body:
      """
      {"entityType": "foo"}
      """
    Then the response status is 400

  Scenario: Deactivate a schema
    When I POST to "/api/v1/schemas" with body:
      """
      {
        "domain": "finance",
        "entityType": "expense",
        "description": "A single expense",
        "fieldDefinitions": [{"name": "amount", "type": "number", "mandatory": true}],
        "extractionPrompt": "Extract expense details"
      }
      """
    Then the response status is 201
    When I deactivate the proposed schema for "finance" "expense"
    Then the response status is 204

  Scenario: Get current schema returns 404 after deactivation
    When I GET "/api/v1/schemas/current?domain=finance&entityType=expense"
    Then the response status is 404

  Scenario: Deactivate non-existent schema returns 404
    When I DELETE "/api/v1/schemas/todo/todo_item/active?id=00000000-0000-0000-0000-000000000000"
    Then the response status is 404
