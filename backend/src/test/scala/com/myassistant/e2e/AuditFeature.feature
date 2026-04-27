Feature: Audit Log Interactions

  Background:
    Given the server is running and I am authenticated

  Scenario: Log a successful interaction
    When I POST to "/api/v1/audit/interactions" with body:
      """
      {
        "jobType": "user_input",
        "message": "User asked about health insurance",
        "response": "Here is your insurance information",
        "toolCalls": [],
        "status": "success"
      }
      """
    Then the response status is 201
    And the response body contains "success"
    And the response body contains "User asked about health insurance"

  Scenario: Log an interaction for a person
    Given the server is running and I am authenticated
    When I POST to "/api/v1/persons" with body:
      """
      {"fullName": "Audit Test Person", "gender": "Male"}
      """
    Then the response status is 201
    When I POST an audit interaction for the created person
    Then the response status is 201
    And the response body contains "partial"

  Scenario: Log a failed interaction with error
    When I POST to "/api/v1/audit/interactions" with body:
      """
      {
        "jobType": "gmail_poll",
        "message": "Failed operation",
        "toolCalls": [],
        "status": "failed",
        "error": "Something went wrong"
      }
      """
    Then the response status is 201
    And the response body contains "failed"
    And the response body contains "Something went wrong"

  Scenario: Log a job interaction
    When I POST to "/api/v1/audit/interactions" with body:
      """
      {
        "jobType": "plaid_poll",
        "message": "Fetched 5 transactions",
        "toolCalls": [],
        "status": "success"
      }
      """
    Then the response status is 201
    And the response body contains "plaid_poll"

  Scenario: Log interaction with invalid status returns 422
    When I POST to "/api/v1/audit/interactions" with body:
      """
      {
        "jobType": "user_input",
        "message": "Bad request",
        "toolCalls": [],
        "status": "invalid_status"
      }
      """
    Then the response status is 422

  Scenario: Log interaction with non-existent person returns 409
    When I POST to "/api/v1/audit/interactions" with body:
      """
      {
        "personId": "00000000-0000-0000-0000-000000000001",
        "message": "FK violation audit",
        "toolCalls": [],
        "status": "success"
      }
      """
    Then the response status is 409

  Scenario: Log interaction with both personId and jobType returns 422
    When I POST to "/api/v1/audit/interactions" with body:
      """
      {
        "personId": "00000000-0000-0000-0000-000000000001",
        "jobType": "user_input",
        "message": "Both owner fields set",
        "toolCalls": [],
        "status": "success"
      }
      """
    Then the response status is 422
