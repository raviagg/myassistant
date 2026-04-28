Feature: Reference Data

  Background:
    Given the server is running and I am authenticated

  Scenario: List life domains
    When I GET "/api/v1/reference/domains"
    Then the response status is 200
    And the response body contains "health"
    And the response body contains "finance"
    And the response body contains "todo"

  Scenario: List source types
    When I GET "/api/v1/reference/source-types"
    Then the response status is 200
    And the response body contains "user_input"
    And the response body contains "plaid_poll"

  Scenario: List kinship aliases
    When I GET "/api/v1/reference/kinship-aliases"
    Then the response status is 200

  Scenario: List kinship aliases filtered by language
    When I GET "/api/v1/reference/kinship-aliases?language=english"
    Then the response status is 200
